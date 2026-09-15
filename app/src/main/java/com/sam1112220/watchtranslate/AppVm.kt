package com.sam1112220.watchtranslate

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sam1112220.watchtranslate.data.Fmt
import com.sam1112220.watchtranslate.data.Device
import com.sam1112220.watchtranslate.data.HistoryDb
import com.sam1112220.watchtranslate.data.HistoryItem
import com.sam1112220.watchtranslate.data.PhraseCategory
import com.sam1112220.watchtranslate.data.PhraseRepo
import com.sam1112220.watchtranslate.data.Prefs
import com.sam1112220.watchtranslate.data.ShapeMode
import com.sam1112220.watchtranslate.data.SpeedTier
import com.sam1112220.watchtranslate.engine.Lang
import com.sam1112220.watchtranslate.engine.LangDetect
import com.sam1112220.watchtranslate.engine.MlKitEngine
import com.sam1112220.watchtranslate.engine.SpeechIn
import com.sam1112220.watchtranslate.engine.SpeechOut
import com.sam1112220.watchtranslate.engine.neural.ModelStore
import com.sam1112220.watchtranslate.engine.neural.NeuralMt
import com.sam1112220.watchtranslate.ui.round.Module
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =====================================================================
// 页面路由
// =====================================================================
enum class Route(val module: Module, val title: String) {
    TRANSLATE(Module.TRANSLATE, "文本翻译"),
    HISTORY(Module.TRANSLATE, "历史记录"),
    PHRASES(Module.TRANSLATE, "常用短语"),

    DIALOG_LANG(Module.DIALOG, "对话模式"),
    DIALOG_MIC(Module.DIALOG, "对话模式"),
    DIALOG_RESULT(Module.DIALOG, "对话模式"),

    SETTINGS(Module.SETTINGS, "设置"),
    SOUND(Module.SETTINGS, "播放声音"),
    SCREENFIT(Module.SETTINGS, "屏幕适配"),
    MODEL(Module.SETTINGS, "离线模型"),
    VOICE(Module.SETTINGS, "语音输出"),

    ABOUT(Module.ABOUT, "关于");
}

enum class Stage { IDLE, DONE }

class AppVm(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)
    val historyDb = HistoryDb(app)
    val phraseRepo = PhraseRepo(app)
    val speechOut = SpeechOut(app)
    val speechIn = SpeechIn(app)

    // ---------------- 端侧神经引擎 ----------------
    val modelStore = ModelStore(app)
    private val neuralMt = NeuralMt(modelStore)
    /** ML Kit 离线翻译（快速档，unbundled，无需 GMS） */
    val mlKit = MlKitEngine()

    init {
        // 把所有依赖 ONNX Runtime 的 jni 都强行先载入一次，顺序固定为：
        // libonnxruntime.so → libsherpa-onnx-jni.so → libonnxruntime4j_jni.so。
        // 三者共用同一个 SONAME，RTLD_LOCAL 下谁先谁独占符号（详见 App.kt 注释）。
        // 这里再兜一次底，确保即使 Application 的预加载被裁剪/异常，也不会出现
        // "cannot locate symbol OrtGetApiBase" 导致识别或翻译整个失效。
        runCatching { System.loadLibrary("onnxruntime") }
        runCatching { System.loadLibrary("sherpa-onnx-jni") }
        runCatching { System.loadLibrary("onnxruntime4j_jni") }
    }

    /** 装载阶段：EXTRACT 解压模型 / INIT 建立推理会话 / READY 就绪 / FAILED 失败 */
    var loadPhase by mutableStateOf("IDLE")
        private set
    var loadDone by mutableStateOf(0L)
        private set
    var loadTotal by mutableStateOf(0L)
        private set
    var loadNote by mutableStateOf("端侧模型未装载")
        private set
    var neuralReady by mutableStateOf(false)
        private set
    var lastUsedNeural by mutableStateOf(false)
        private set

    /** 首次推理失败的原因，显示在模型页，便于无调试器时定位 */
    var neuralError by mutableStateOf<String?>(null)
        private set

    fun startModelLoad() {
        if (loadPhase == "EXTRACT" || loadPhase == "INIT") return
        viewModelScope.launch {
            // 快速档用 ML Kit：与下方 ONNX 解压并行预下载语言包（首次联网一次）
            if (speedTier == SpeedTier.FAST) {
                launch(Dispatchers.IO) { mlKit.ensureModels() }
            }
            val files = ModelStore.Files.slim()
            loadPhase = "EXTRACT"
            loadNote = "正在展开端侧模型…"
            try {
                withContext(Dispatchers.IO) {
                    modelStore.extract(files) { d, t ->
                        loadDone = d
                        loadTotal = t
                    }
                }
                loadPhase = "INIT"
                loadNote = "正在装载分词器…"
                withContext(Dispatchers.Default) {
                    // ONNX 仅用于高精度档，固定用其 ACCURATE 配置
                    neuralMt.load(1, false)
                }
                neuralReady = true
                loadPhase = "READY"
                loadNote = "OPUS-MT 中英双向已就绪 · ${tierLabelForUi()} · 推理会话按需建立"
                refreshStats()
            } catch (e: Throwable) {
                neuralReady = false
                loadPhase = "FAILED"
                loadNote = "神经引擎装载失败：" +
                    (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun neuralDiagnostics(): String = runCatching { neuralMt.describe() }
        .getOrElse { "未加载" }

    /** 端侧引擎自检报告（全屏显示，便于无调试器时远程定位） */
    var selfTestReport by mutableStateOf<String?>(null)
        private set

    fun closeSelfTest() { selfTestReport = null }

    // ---------------- 全屏查看（译文 / 原文） ----------------
    var fullTitle by mutableStateOf("")
        private set
    var fullText by mutableStateOf<String?>(null)
        private set

    /** 点译文 → 全屏可滚动查看 */
    fun openFullResult() {
        val t = result
        if (t.isBlank()) return
        fullTitle = "${toLang.label} · 译文（共 ${t.length} 字）"
        fullText = buildString {
            append(t)
            if (input.isNotBlank()) append("\n\n—— 原文 ——\n").append(input)
            append("\n\n耗时 ${resultMs} ms · ${tierLabelForUi()}")
        }
    }

    fun closeFull() { fullText = null }

    // ---------------- 翻译速度档 ----------------
    var speedTier by mutableStateOf(prefs.speedTier)
        private set

    fun tierLabelForUi(): String =
        if (speedTier == SpeedTier.FAST) "快速档" else "高精度档"

    /**
     * 切换速度档：重建推理会话（下一次翻译时按新档位建立）。
     *
     * 重要：**不能**在这个点击回调（主线程）里直接调用 `neuralMt.load()`。
     * load() 会读取并解析 tokmeta.json、为其中的 AddedToken 编译正则
     * （见 HfTokenizer.kt），实测在 armeabi-v7a 表上耗时可达数秒；主线程被
     * 阻塞超过 5s 会被系统判为「Input dispatching timed out」ANR 并杀掉进程
     * —— v1.0.6 首轮真机验证就是这样挂的（dropbox: data_app_anr）。
     * 因此这里只做状态变更，装载放到 Dispatchers.Default。
     */
    fun applySpeedTier(v: SpeedTier) {
        if (v == speedTier) return
        val label = if (v == SpeedTier.FAST) "快速档" else "高精度档"
        speedTier = v
        prefs.speedTier = v
        translateCache.clear()
        neuralError = null
        stage = Stage.IDLE
        result = ""
        loadNote = "正在切换到$label…"
        viewModelScope.launch {
            if (v == SpeedTier.FAST) {
                // 快速档：预下载 ML Kit 语言包（首次联网一次，之后瞬时返回）
                val ok = withContext(Dispatchers.IO) { mlKit.ensureModels() }
                if (ok) {
                    loadPhase = "READY"
                    loadNote = "已切换为快速档 · ML Kit 端侧翻译"
                } else {
                    loadPhase = "FAILED"
                    loadNote = "快速档语言包下载失败 · 请联网后重试"
                    neuralError = "ML Kit 语言包下载失败"
                }
            } else {
                // 高精度档：重载本地 Marian / ONNX
                val err = withContext(Dispatchers.Default) {
                    runCatching {
                        neuralMt.unload()
                        neuralMt.load(threadsFor(v), false)
                    }.exceptionOrNull()
                }
                if (err == null) {
                    neuralReady = true
                    loadPhase = "READY"
                    loadNote = "已切换为高精度档 · 下次翻译重建会话"
                } else {
                    neuralReady = false
                    loadPhase = "FAILED"
                    loadNote = "切换失败：${err.message ?: err.javaClass.simpleName}"
                    neuralError = "${err.javaClass.simpleName}: ${err.message ?: ""}".take(300)
                }
            }
        }
    }

    private fun threadsFor(v: SpeedTier): Int = if (v == SpeedTier.FAST) 4 else 1

    /** 结果缓存：快速档下重复句直接命中，省掉整次推理 */
    private val translateCache = object : LinkedHashMap<String, String>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 32
    }

    fun runSelfTest() {
        viewModelScope.launch {
            selfTestReport = "自检运行中…"
            val text = if (speedTier == SpeedTier.FAST) {
                // 快速档：ML Kit（unbundled，无需 ONNX）
                if (mlKit.ready) "ML Kit 快速档已就绪\n语言包：已下载 · 端侧推理"
                else "ML Kit 快速档\n语言包：${if (mlKit.downloading) "下载中…" else "未下载（首次联网）"}"
            } else {
                withContext(Dispatchers.Default) {
                    if (!neuralReady) {
                        "神经引擎未就绪\n装载阶段：$loadPhase\n说明：$loadNote"
                    } else {
                        runCatching { neuralMt.selfTest() }
                            .getOrElse { "自检抛出 ${it.javaClass.simpleName}: ${it.message ?: ""}" }
                    }
                }
            }
            // 再走一遍「按翻译按钮」的真实路径（doTranslate），覆盖状态机 + 历史库写入，
            // 这样无输入设备（或输入法挡住 adb 注入）时也能验证按钮链路。
            val probe = "这个多少钱？"
            val shortLine = probeThroughButton(probe)
            // 长句探针：这块表的输入框无法用 adb 注入文本（合成按键、停用输入法都不生效），
            // 这是唯一能自动覆盖「长译文 + 全屏滚动」路径的办法；并且把长结果留在界面上，
            // 便于紧接着点下带验证全屏浮层是否真的可滚动。
            val longProbe = "请问你们这里可以刷卡吗？我想买两张去北京的火车票，" +
                "另外还需要一张发票，谢谢。"
            val longLine = probeThroughButton(longProbe)
            selfTestReport = text +
                "\n\n[按钮路径 doTranslate]\n«$probe» -> $shortLine" +
                "\n\n[长句路径 · 全屏滚动]\n«$longProbe» -> $longLine"
        }
    }

    /** 把一段文本走一遍真实按钮链路（doTranslate），返回一行摘要 */
    private suspend fun probeThroughButton(src: String): String {
        input = src
        doTranslate()
        // doTranslate 的协程是异步启动的，先等 busy 变 true（最多 3s）
        var w = 0
        while (!busy && w < 3000) { delay(50); w += 50 }
        var waited = 0
        while (busy && waited < 90_000) {
            delay(200)
            waited += 200
        }
        return if (result.isBlank()) "无产出（busy=$busy 等待 ${waited}ms）"
        else "$result  [${resultMs}ms]  stage=$stage"
    }

    private val ctx: Context get() = getApplication()

    // ---------------- 导航 ----------------
    var route by mutableStateOf(Route.TRANSLATE)
        private set
    private val backStack = ArrayDeque<Route>()

    // ---------------- 屏幕形状 ----------------
    var systemRound: Boolean = Device.isRound(app)
        private set
    var shape by mutableStateOf(resolveShape())
        private set

    private fun resolveShape(): ShapeMode {
        val auto = if (Device.isRound(getApplication())) ShapeMode.ROUND else ShapeMode.RECT
        prefs.detectedShape = auto
        return when (prefs.shapeMode) {
            ShapeMode.AUTO -> auto
            else -> prefs.shapeMode
        }
    }

    val isRound: Boolean get() = shape == ShapeMode.ROUND

    /** 当前屏幕尺寸描述，用于屏幕适配页脚注 */
    val screenSizeLabel: String get() = Device.screenSizeDp(getApplication())

    fun setShapeMode(m: ShapeMode) {
        prefs.shapeMode = m
        shape = resolveShape()
    }

    fun reapplyShape() {
        systemRound = Device.isRound(getApplication())
        shape = resolveShape()
    }

    // ---------------- 板块 1 文本翻译 ----------------
    var input by mutableStateOf("")
    var fromLang by mutableStateOf(runCatching { Lang.valueOf(prefs.lastFrom) }.getOrDefault(Lang.ZH))
    var toLang by mutableStateOf(runCatching { Lang.valueOf(prefs.lastTo) }.getOrDefault(Lang.EN))
    var stage by mutableStateOf(Stage.IDLE)
    var result by mutableStateOf("")
    var resultMs by mutableStateOf(0L)
    var busy by mutableStateOf(false)

    fun swapLangs() {
        val t = fromLang; fromLang = toLang; toLang = t
        prefs.lastFrom = fromLang.name; prefs.lastTo = toLang.name
        if (result.isNotBlank()) doTranslate()
    }

    fun setFrom(l: Lang) { fromLang = l; prefs.lastFrom = l.name }
    fun setTo(l: Lang) { toLang = l; prefs.lastTo = l.name }

    fun appendInput(s: String) { if (input.length < 500) input += s }

    fun doTranslate() {
        val src = input.trim()
        if (src.isEmpty()) return
        val cacheKey = "${fromLang.name}>${toLang.name}:$src"
        viewModelScope.launch {
            busy = true
            val t0 = System.nanoTime()
            val cached = translateCache[cacheKey]
            val text = if (cached != null) {
                cached
            } else {
                withContext(Dispatchers.Default) {
                    if (speedTier == SpeedTier.FAST) {
                        // 快速档：ML Kit（unbundled，无需 GMS，首次联网下载模型后离线）
                        runCatching { mlKit.translate(src, fromLang, toLang) }
                            .onFailure {
                                neuralError = "${it.javaClass.simpleName}: ${it.message ?: ""}".take(300)
                            }
                            .getOrNull()
                    } else if (!neuralReady) {
                        neuralError = "引擎未就绪（$loadPhase）· ${loadNote.take(160)}"
                        null
                    } else {
                        // 高精度档：本地 Marian / ONNX
                        runCatching { neuralMt.translate(src, fromLang, toLang) }
                            .onFailure {
                                neuralError = "${it.javaClass.simpleName}: ${it.message ?: ""}".take(300)
                            }
                            .getOrNull()
                    }
                }
            }
            if (text != null) translateCache[cacheKey] = text
            val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            resultMs = ms
            lastUsedNeural = cached == null && speedTier != SpeedTier.FAST
            busy = false
            stage = Stage.DONE
            if (text == null) {
                result = "翻译失败 · " + (neuralError ?: "未知原因").take(200)
                return@launch
            }
            result = text
            lastHistoryId = withContext(Dispatchers.IO) {
                historyDb.add(src, text, fromLang.name, toLang.name, ms)
            }
            reloadHistory()
            // 文本翻译不再自动朗读：用户明确要求「单纯翻译的界面不要翻译后自动播放语音」，
            // 朗读改为点中心圆钮手动触发。自动朗读只保留在对话模式。
        }
    }

    fun resetTranslate() {
        input = ""; result = ""; resultMs = 0L; stage = Stage.IDLE
        neuralError = null
    }

    // ---------------- 历史 ----------------
    val historyItems = mutableStateListOf<HistoryItem>()
    var historyCount by mutableStateOf(0)
        private set
    var lastHistoryId by mutableStateOf(-1L)

    fun reloadHistory() {
        val list = historyDb.list(200)
        historyItems.clear()
        historyItems.addAll(list)
        historyCount = list.size
    }

    fun clearHistory() {
        historyDb.clear()
        reloadHistory()
    }

    fun toggleFav(item: HistoryItem) {
        historyDb.setFav(item.id, !item.fav)
        reloadHistory()
    }

    // ---------------- 短语本 ----------------
    val phraseCats: List<PhraseCategory> get() = phraseRepo.categories
    var phraseCatIndex by mutableStateOf(0)

    // ---------------- 板块 2 对话 ----------------
    var dialogStage by mutableStateOf(Stage.IDLE)
    var dialogMy by mutableStateOf(LangDetect.detect("", Lang.ZH))
    var dialogPeer by mutableStateOf(Lang.EN)
    var recogText by mutableStateOf("")
    var recogDst by mutableStateOf("")
    var recogFrom by mutableStateOf(Lang.ZH)
    var listening by mutableStateOf(false)
    var partial by mutableStateOf("")
    var micLevel by mutableStateOf(0f)
    var speechErr by mutableStateOf<String?>(null)

    init {
        dialogMy = runCatching { Lang.valueOf(prefs.myLang) }.getOrDefault(Lang.ZH)
        dialogPeer = runCatching { Lang.valueOf(prefs.peerLang) }.getOrDefault(Lang.EN)
    }

    fun applyMyLang(l: Lang) { dialogMy = l; prefs.myLang = l.name }
    fun applyPeerLang(l: Lang) { dialogPeer = l; prefs.peerLang = l.name }

    fun startDialog() {
        dialogStage = Stage.IDLE
        recogText = ""; recogDst = ""; partial = ""; speechErr = null
    }

    /**
     * 对话结果页「重置」：清掉上一轮的识别/译文，回到麦克风待机，开始下一句。
     *
     * 修复的问题：识别一次后 dialogStage 变成 DONE，DialogResult 页没有任何入口
     * 把状态复位，导致点 3 点导航再回对话板块仍然落在结果页，用户被卡住、
     * 无法说下一句（真机复现：说完一句后只能换板块，回来还是老结果）。
     */
    fun resetDialog() {
        stopListening()
        dialogStage = Stage.IDLE
        recogText = ""; recogDst = ""; partial = ""; speechErr = null
        navigate(Route.DIALOG_MIC)
    }

    fun startListening() {
        speechErr = null
        partial = ""
        if (!speechIn.probe()) {
            speechErr = speechIn.availabilityNote
            listening = false
            return
        }
        listening = true
        speechIn.start(dialogMy, object : SpeechIn.Callback {
            override fun onPartial(text: String) { partial = text }
            override fun onFinal(text: String) {
                listening = false
                micLevel = 0f
                onRecognized(text)
            }
            override fun onError(message: String) {
                listening = false
                micLevel = 0f
                speechErr = message
            }
            override fun onRms(rms: Float) {
                micLevel = ((rms + 2f) / 12f).coerceIn(0.05f, 1f)
            }
        })
    }

    fun stopListening() {
        listening = false
        speechIn.stop()
        micLevel = 0f
    }

    /** 识别结果 -> 自动判定方向 -> 翻译 */
    fun onRecognized(text: String) {
        recogText = text
        // 双向对话：自动判断「现在谁在说话」
        val detected = LangDetect.detect(text, dialogMy)
        val to = if (detected == dialogMy) dialogPeer else dialogMy
        recogFrom = detected
        // 识别一结束就切到结果页：先让用户看到「识别到了什么」，
        // 译文由下面的协程补上（原来只翻译完才置 DONE 且从不 navigate，
        // 真机上表现为「说完一句不跳转、一直停在麦克风页」）。
        dialogStage = Stage.DONE
        recogDst = ""
        navigate(Route.DIALOG_RESULT)
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                // 与主页 doTranslate() 保持同一套档位逻辑：
                // 快速档走 ML Kit，高精度档才需要神经引擎。
                // 原来这里无条件 if (!neuralReady) null，导致快速档下
                // 对话模式永远拿不到译文（真机表现为「卡进翻译界面但没有结果」）。
                if (speedTier == SpeedTier.FAST) {
                    runCatching { mlKit.translate(text, detected, to) }
                        .onFailure {
                            neuralError = "${it.javaClass.simpleName}: ${it.message ?: ""}".take(300)
                        }
                        .getOrNull()
                } else if (!neuralReady) {
                    neuralError = "引擎未就绪（$loadPhase）· ${loadNote.take(160)}"
                    null
                } else {
                    runCatching { neuralMt.translate(text, detected, to) }
                        .onFailure {
                            neuralError = "${it.javaClass.simpleName}: ${it.message ?: ""}".take(300)
                        }
                        .getOrNull()
                }
            }
            recogDst = r ?: ("翻译失败 · " + (neuralError ?: "未知原因").take(200))
            if (r != null && r.isNotBlank()) {
                historyDb.add(text, r, detected.name, to.name, 0)
                reloadHistory()
                if (prefs.autoSpeak && speechOut.isReady) {
                    speechOut.speak(r, to, prefs.volume / 100f, prefs.rate, voiceFor(to), prefs.pitch)
                }
            }
        }
    }

    /** 键盘兜底输入（端侧识别不可用时的通路） */
    fun manualRecognize(text: String) {
        if (text.isBlank()) return
        onRecognized(text)
    }

    // ---------------- 板块 3 设置 ----------------
    var autoSpeak by mutableStateOf(prefs.autoSpeak)
        private set
    var volume by mutableStateOf(prefs.volume)
        private set
    var rate by mutableStateOf(prefs.rate)
        private set
    var pitch by mutableStateOf(prefs.pitch)
        private set
    var voiceZh by mutableStateOf(prefs.voiceZh)
        private set
    var voiceEn by mutableStateOf(prefs.voiceEn)
        private set

    var ttsReady by mutableStateOf(false)
        private set
    var ttsLabel by mutableStateOf("正在检测端侧语音…")
        private set
    var speaking by mutableStateOf(false)
        private set

    var assetBytes by mutableStateOf(0L)
        private set
    var appDataBytes by mutableStateOf(0L)
        private set
    var freeBytes by mutableStateOf(0L)
        private set

    fun refreshStats() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            withContext(Dispatchers.IO) {
                assetBytes = Device.assetBytes(app, ModelStore.Files.slim())
                appDataBytes = Device.appDataBytes(app) + historyDb.dbBytes()
                freeBytes = Device.freeBytes()
            }
        }
    }

    fun initSpeech(onReady: () -> Unit) {        speechOut.onStateChanged = {
            ttsReady = speechOut.isReady
            ttsLabel = speechOut.engineLabel
        }
        speechOut.onSpeakingChanged = { speaking = it }
        speechOut.init {
            ttsReady = speechOut.isReady
            ttsLabel = speechOut.engineLabel
            onReady()
        }
    }

    fun voiceFor(l: Lang): String? = if (l == Lang.ZH) voiceZh else voiceEn

    fun applyAutoSpeak(v: Boolean) { autoSpeak = v; prefs.autoSpeak = v }
    fun applyVolume(v: Int) { volume = v.coerceIn(0, 100); prefs.volume = volume }
    fun applyRate(v: Float) { rate = v.coerceIn(0.4f, 2.2f); prefs.rate = rate }
    fun applyPitch(v: Float) { pitch = v.coerceIn(0.6f, 1.6f); prefs.pitch = pitch }
    fun applyVoiceZh(n: String?) { voiceZh = n; prefs.voiceZh = n }
    fun applyVoiceEn(n: String?) { voiceEn = n; prefs.voiceEn = n }

    val zhVoiceNames: List<String> get() = speechOut.voices(Lang.ZH).map { it.name }
    val enVoiceNames: List<String> get() = speechOut.voices(Lang.EN).map { it.name }

    fun speakSample(text: String, l: Lang) {
        if (!ttsReady) return
        speechOut.speak(text, l, volume / 100f, rate, voiceFor(l), pitch)
    }

    fun speak(text: String, l: Lang) = speakSample(text, l)

    fun stopSpeak() = speechOut.stop()

    // ---------------- 通用 ----------------
    fun copyToClipboard(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("translate", text))
    }

    fun navigate(r: Route) {
        if (route == r) return
        backStack.addLast(route)
        route = r
        onEnter(r)
    }

    /** 返回上一页；在根页（翻译）返回 false 表示不可再退 */
    fun back(): Boolean {
        if (route == Route.TRANSLATE) return false
        val prev = backStack.removeLastOrNull() ?: Route.TRANSLATE
        route = prev
        return true
    }

    fun goTab(m: Module) {
        // 进入对话板块即预热端侧 ASR 引擎：标准版模型约 190MB，首次加载需十几秒，
        // 提前在后台建好，用户点麦克风就能立刻开口（真机实测否则要等 3~6 秒）。
        if (m == Module.DIALOG) speechIn.prewarm()
        val target = when (m) {
            Module.TRANSLATE -> Route.TRANSLATE
            Module.DIALOG -> if (dialogStage == Stage.DONE) Route.DIALOG_RESULT else Route.DIALOG_LANG
            Module.SETTINGS -> Route.SETTINGS
            Module.ABOUT -> Route.ABOUT
        }
        navigate(target)
    }

    /** 右滑：返回上一页（等同返回键） */
    fun goBackOne() {
        back()
    }

    /** 左滑：前进到下一个板块 */
    fun goForward() {
        val next = when (route) {
            Route.TRANSLATE -> Module.DIALOG
            Route.HISTORY, Route.PHRASES -> Module.TRANSLATE
            Route.DIALOG_LANG, Route.DIALOG_MIC, Route.DIALOG_RESULT -> Module.SETTINGS
            Route.SETTINGS, Route.SOUND, Route.SCREENFIT, Route.MODEL, Route.VOICE -> Module.ABOUT
            Route.ABOUT -> Module.TRANSLATE
        }
        goTab(next)
    }

    private fun onEnter(r: Route) {
        when (r) {
            Route.HISTORY -> reloadHistory()
            Route.MODEL, Route.SCREENFIT -> refreshStats()
            Route.DIALOG_LANG -> { dialogStage = Stage.IDLE; stopListening() }
            else -> Unit
        }
    }

    override fun onCleared() {
        speechOut.shutdown()
        speechIn.stop()
        historyDb.close()
        super.onCleared()
    }
}
