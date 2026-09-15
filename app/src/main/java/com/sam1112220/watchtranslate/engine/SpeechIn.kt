package com.sam1112220.watchtranslate.engine

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 端侧语音识别（自录音 + 本地 ASR 模型）。
 *
 * 为什么不用系统 SpeechRecognizer：
 *   本机唯一的 RecognitionService 属腾讯应用宝，绑定需 signature 级权限
 *   BIND_VOICE_INTERACTION，第三方应用调用必然抛
 *   "NOT allowed to access to service intent"。系统通路在这台表上是死的。
 *
 * 输入法（搜狗 / 讯飞）的语音输入是同一套思路：不碰系统识别服务，
 * 自己用 AudioRecord 采 PCM，再喂给本地 / 自建 ASR 引擎。本类即照此实现：
 *   AudioRecord(16kHz 单声道 PCM) → sherpa-onnx 流式 Zipformer（中英双语）
 *   → 增量出字（onPartial）→ 端点检测判定说话结束（onFinal）。
 *
 * 全程离线，不联网，模型随 APK 内置（assets/models/asr）。
 */
class SpeechIn(private val ctx: Context) {

    interface Callback {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onRms(rms: Float)
    }

    companion object {
        private const val TAG = "SpeechIn"

        /** 采样率必须与模型训练一致（Zipformer 双语模型 = 16kHz） */
        private const val SAMPLE_RATE = 16000

        /** 每帧读取的采样点数（100ms，兼顾实时性与 CPU 占用） */
        private const val FRAME = 1600

        /**
         * assets 下模型目录（相对 assets 根）。
         *
         * 【改用标准版 2023-02-20】原为 small 版，但真机实测中文
         * 「说话一快就只剩片段」，而英文完全正常——说明不是采集/算力问题，
         * 是 small 版（encoder 仅 41MB）中文声学建模能力不足。
         * 用户要求「体验最好」，故换成标准版：int8 全套约 189MB
         * （encoder 173.5MB），中文准确率显著提升。
         * 代价：APK 体积、首次加载时间（约十几秒）与内存占用均上升。
         */
        private const val ASR_DIR = "models/asr/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"

        /** 整段说话的最长时间保护（秒），防止无人说话时无限录音 */
        private const val MAX_SECONDS = 15

        /**
         * 连续静音达到该秒数才认为一句话结束。
         *
         * 【真机教训】原来是 1.6s，用户反馈「说话说到一半就停止识别」。
         * 原因：正常说话中的自然停顿（换气、思考、词组间隙）很容易达到 1.5~2 秒，
         * 被判成「说完了」。放宽到 3.0s 后，中途停顿不再误切句。
         */
        private const val SILENCE_STOP_SEC = 3.0f

        /**
         * 判定「这一帧有语音」的能量阈值（RMS，归一化到 0..1）。
         *
         * 【真机教训】原来是 0.012（约 -38 dBFS）。手表麦克风离嘴较远，
         * 轻声 / 远场说话时 RMS 常低于该值，于是被计成静音，
         * 静音时长提前累加，同样导致「说到一半就停」。
         * 降到 0.006（约 -44 dBFS）后，轻声说话也能被认作有声。
         */
        private const val VOICE_RMS = 0.006f
    }

    private var recognizer: OnlineRecognizer? = null
    private val recognizerLock = Any()
    private var stream: OnlineStream? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile private var running = false

    var available: Boolean = false
        private set
    var availabilityNote: String = ""
        private set

    /** 模型文件名（与 assets 内实际文件名严格一致，改模型时同步改这里） */
    private val modelFiles = arrayOf(
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.int8.onnx",
        "joiner-epoch-99-avg-1.int8.onnx",
        "tokens.txt",
    )

    /**
     * 模型是否已随包内置。逐个校验必需文件是否存在——
     * 只判目录非空是不够的：sherpa 原生层读不到文件会直接抛
     * "Load ... failed" 并让进程异常退出，必须在开麦前拦住。
     */
    private fun assetsReady(): Boolean = try {
        val listing = ctx.assets.list(ASR_DIR)?.toSet() ?: emptySet()
        listing.isNotEmpty() && modelFiles.all { it in listing }
    } catch (e: Exception) {
        false
    }

    /**
     * 探测识别能力。本地模型不需要向系统申请任何识别服务，
     * 只要模型资产在、麦克风权限有，就能用。
     */
    fun probe(): Boolean {
        available = assetsReady()
        availabilityNote = if (available) "本地识别模型就绪 · 全程离线" else "识别模型缺失 · 请用键盘输入"
        return available
    }

    /**
     * 懒加载识别器（首次约 3~6 秒，之后复用）。
     * 用锁保证预热线程与录音线程不会同时构建（sherpa 原生层非线程安全）。
     */
    private fun ensureRecognizer(): OnlineRecognizer {
        recognizer?.let { return it }
        synchronized(recognizerLock) {
            recognizer?.let { return it }
            val dir = ASR_DIR
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    // 统一 int8 量化版：标准版全套约 190MB，手表 CPU 可实时解码
                    encoder = "$dir/${modelFiles[0]}",
                    decoder = "$dir/${modelFiles[1]}",
                    joiner = "$dir/${modelFiles[2]}",
                ),
                tokens = "$dir/${modelFiles[3]}",
                // 【说话快丢字 · 修复 2】解码线程 2 -> 4。
                // zipformer 的解码是纯 CPU 计算，手表的 SoC 通常有 4 个以上核心，
                // 多给它线程可显著降低实时率（RTF），说话越快收益越明显。
                numThreads = 4,
                modelType = "zipformer",
                debug = false,
            ),
            // 端点检测：说完一句自动断句并出最终结果，无需手动停止。
            //
            // 【真机教训】rule 的静音阈值原来偏小（rule1=2.4s、rule2=1.6s），
            // 用户「说到一半就停止识别」。放宽后：
            //   rule1 4.0s：一直没识别出内容又长时间安静 → 收尾（避免空等）
            //   rule2 3.0s：已识别出内容 + 尾部静音 3s → 判定说完
            //   rule3      ：最长 15s 强制切分，防止无限录音
            endpointConfig = EndpointConfig(
                rule1 = com.k2fsa.sherpa.onnx.EndpointRule(false, 4.0f, 0.0f),
                rule2 = com.k2fsa.sherpa.onnx.EndpointRule(true, SILENCE_STOP_SEC, 0.0f),
                rule3 = com.k2fsa.sherpa.onnx.EndpointRule(false, 0.0f, MAX_SECONDS.toFloat()),
            ),
            enableEndpoint = true,
            // 【解码策略：标准模型必须配 greedy_search，不能配 beam search】
            //
            // 前一版曾用 modified_beam_search 救中文准确率（原理：同时保留多条候选，
            // 用后文回头纠正中文同音字误判；英文音素区分度高所以本来就没事）。
            // 但换成标准版模型后必须调回 greedy，核心原因是**实时率（RTF）**：
            //
            //   标准版 encoder 173.5MB ≈ small 版(41MB) 的 4.2 倍，
            //   greedy 下 RTF 已到 0.5~0.6；beam search 再乘约 2~3 倍，
            //   会越过 1.0 —— 即解码比说话还慢，音频持续积压，
            //   表现就是丢字 + 越说越延迟，与「要流畅」直接冲突。
            //
            // 所以最终取舍：**准确率交给大模型，速度交给 greedy**，
            // 这才是「体验最好 + 流畅」两者兼顾的组合。
            // 注：maxActivePaths 只在 beam search 下生效，当前配置里它不起作用，
            //     保留是为了日后若要切回 modified_beam_search 时能直接改一行。
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
        )
            val r = OnlineRecognizer(assetManager = ctx.assets, config = config)
            recognizer = r
            return r
        }
    }

    fun start(lang: Lang, cb: Callback) {
        if (!probe()) { cb.onError(availabilityNote); return }
        stop()

        // 引擎首次加载要读约 190MB 模型 + 建 ONNX 会话，放主线程必然卡死
        // （Choreographer Skipped 388 frames）。因此把「建引擎 + 开麦 + 读循环」
        // 整体放到同一个后台线程，主线程立即返回，界面不会假死。
        running = true
        worker = thread(name = "asr-worker", isDaemon = true) {
            val rec = try {
                ensureRecognizer()
            } catch (e: Throwable) {
                Log.e(TAG, "create recognizer failed", e)
                available = false
                availabilityNote = "识别引擎初始化失败 · 请用键盘补充"
                running = false
                cb.onError(availabilityNote)
                return@thread
            }

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            // 【说话快丢字 · 修复 1】缓冲区从 400ms 提到 2 秒。
            //
            // 原来 bufSize = FRAME*2*4 = 12800B = 6400 samples = 400ms。
            // 录音与解码在同一线程串行执行，说话快时每帧解码耗时上升，
            // 一旦某帧处理超过 400ms，AudioRecord 的内部环形缓冲就会绕回去
            // 覆盖尚未读走的样本 —— 音频凭空丢失，表现为「只能识别出片段」。
            // 缓冲区只影响「能容忍多久的处理延迟」，不影响首字响应，故可放心加大。
            val bufSize = maxOf(minBuf, FRAME * 2 * 20)

            // 音频源回退链：VOICE_RECOGNITION 在部分厂商（如 OPPO 手表）的音频
            // 策略里会走专用语音通道，若该通道的 HAL 未就绪，AudioRecord 构造会
            // 一直阻塞在 IAudioFlinger::openRecord（binder 调用），
            // 超时后 audioserver 被系统看门狗（TimeCheck）杀掉，
            // 表现为 "Abort message: 'TimeCheck timeout for IAudioFlinger command 2'"
            // （command 2 == OPEN_RECORD），而应用进程本身并不会崩。
            //
            // 这里按优先级依次尝试，任一成功即用；全部失败才报错。
            // 顺序依据：VOICE_RECOGNITION 自带厂商语音前处理（降噪/AGC）最利于识别，
            // 故仍作首选，仅在它不可用时才退到通用 MIC。
            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT,
            )
            var audio: AudioRecord? = null
            var lastErr: Throwable? = null
            for (src in sources) {
                try {
                    val a = AudioRecord(
                        src,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                    )
                    if (a.state == AudioRecord.STATE_INITIALIZED) {
                        audio = a
                        Log.i(TAG, "AudioRecord ready, source=$src")
                        break
                    }
                    a.release()
                    Log.w(TAG, "AudioRecord not initialized, source=$src")
                } catch (e: Throwable) {
                    lastErr = e
                    Log.e(TAG, "AudioRecord create failed, source=$src", e)
                }
            }
            val audioRec = audio
            if (audioRec == null) {
                Log.e(TAG, "all audio sources failed", lastErr)
                running = false
                cb.onError("麦克风不可用")
                return@thread
            }
            if (!running) { // 期间用户已取消
                audioRec.release()
                return@thread
            }

            val st = rec.createStream()
            stream = st
            recorder = audioRec
            // 双语模型自带中英判别，无需按 lang 切换；保留参数以便日后接入单语模型
            audioRec.startRecording()

            val buf = ShortArray(FRAME)
            var lastVoiceAt = System.currentTimeMillis()
            var finalized = false
            var frameIdx = 0
            val startedAt = System.currentTimeMillis()
            try {
                while (running) {
                    val n = audioRec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    frameIdx++

                    // 计算本帧能量 → 驱动的麦克风电平动画 + 静音计时
                    var sum = 0.0
                    for (i in 0 until n) {
                        val s = buf[i] / 32768.0
                        sum += s * s
                    }
                    val rms = sqrt(sum / n).toFloat()
                    // 【修复 3】UI 回调节流：onRms 会写 Compose 状态驱动电平动画，
                    // 每 100ms 一帧都推肉眼看不出差别，却要和解码抢同一个线程。
                    if (frameIdx % 2 == 0) {
                        cb.onRms((rms * 100f).coerceIn(0f, 12f) - 2f)
                    }
                    if (rms > VOICE_RMS) lastVoiceAt = System.currentTimeMillis()

                    val f = FloatArray(n) { buf[it] / 32768.0f }
                    st.acceptWaveform(f, SAMPLE_RATE)

                    while (rec.isReady(st)) rec.decode(st)

                    val text = rec.getResult(st).text
                    // 同理：onPartial 每帧回调会触发 Compose 重组，说话越快越拖累解码。
                    // 降到每 3 帧（约 300ms）刷新一次，观感无差别。
                    if (text.isNotBlank() && frameIdx % 3 == 0) cb.onPartial(text)

                    // 说话人已停口且已有内容 → 收尾出最终结果
                    val silentFor = (System.currentTimeMillis() - lastVoiceAt) / 1000f
                    val hasText = text.isNotBlank()
                    val tooLong = (System.currentTimeMillis() - startedAt) / 1000f > MAX_SECONDS
                    if (hasText && (rec.isEndpoint(st) || silentFor >= SILENCE_STOP_SEC || tooLong)) {
                        cb.onFinal(text.trim())
                        finalized = true
                        break
                    }
                    if (!hasText && tooLong) break // 全程无人说话
                }
            } catch (e: Throwable) {
                Log.e(TAG, "asr loop error", e)
                if (running) cb.onError("识别中断：${e.message?.take(60) ?: "未知错误"}")
            } finally {
                running = false
                runCatching { audioRec.stop() }
                runCatching { audioRec.release() }
                recorder = null
                runCatching { st.release() }
                stream = null
                if (!finalized) cb.onRms(0f)
            }
        }
    }

    fun stop() {
        running = false
        // 主动打断录音阻塞（read() 会立刻返回），让工作线程尽快走到 finally。
        runCatching { recorder?.stop() }
        // 只短等：引擎加载可能长达数秒，不能在主线程死等（会再造成一次 UI 卡顿）。
        // 线程自身在 finally 里负责释放 audio / stream，并会检查 running 提前退出。
        worker?.let { runCatching { it.join(400) } }
        worker = null
        runCatching { recorder?.release() }
        recorder = null
        runCatching { stream?.release() }
        stream = null
    }

    fun isRunning(): Boolean = running

    /**
     * 预热：在后台把识别引擎建好，用户真正点麦克风时即可立刻开录，
     * 不必再等标准版模型（约 190MB）的加载时间，首次可能需十几秒。
     * 可重复调用，已就绪则直接返回。
     */
    fun prewarm() {
        if (recognizer != null || !assetsReady()) return
        thread(name = "asr-prewarm", isDaemon = true) {
            runCatching { ensureRecognizer() }
                .onFailure { Log.w(TAG, "prewarm failed", it) }
        }
    }

    /** 释放引擎（进程退出 / 长时间不用时调用） */
    fun release() {
        stop()
        runCatching { recognizer?.release() }
        recognizer = null
    }
}
