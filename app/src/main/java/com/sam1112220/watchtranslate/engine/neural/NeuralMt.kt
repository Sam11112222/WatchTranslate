package com.sam1112220.watchtranslate.engine.neural

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.sam1112220.watchtranslate.engine.Lang
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/**
 * 通用 ONNX encoder-decoder 贪心解码器。
 *
 * 不硬编码张量名，而是从推理会话自省输入/输出：
 *   encoder : input_ids + attention_mask  ->  last_hidden_state
 *   decoder : merged 解码器，带 past_key_values / present.* 键值缓存，
 *             若存在 use_cache_branch 则首步走 false（空缓存）、其后走 true。
 *
 * 同一份实现同时驱动 NLLB-200（BART 式）与 OPUS-MT（Marian 式）。
 */
class OnnxSeq2Seq(
    encFile: File,
    decFile: File,
    threads: Int = 2,
    fast: Boolean = false
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val enc: OrtSession
    private val dec: OrtSession

    // ---- 编码器接口 ----
    private val encInputIds: String
    private val encAttnMask: String
    private val encOutput: String

    // ---- 解码器接口 ----
    private val decInputIds: String
    private val decEncMask: String
    private val decEncHidden: String
    private val decUseCacheBranch: String?
    private val decLogits: String

    /** 自注意力键值缓存：输入名 -> 对应输出名 */
    private val selfKv: List<Pair<String, String>>
    /** 交叉注意力键值缓存 */
    private val crossKv: List<Pair<String, String>>
    private val allKvIn: List<String>
    private val kvEmptyShape: Map<String, LongArray>
    private val useCacheShape: LongArray

    val encBytes = encFile.length()
    val decBytes = decFile.length()

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(if (fast) 2 else 1)
            if (fast) {
                // 快速档：EXTENDED_OPT 做算子融合（Attention / SkipLayerNorm / MatMul 等），
                // 每步推理更快。注意：setMemoryPatternOptimization(true) 在 ORT 1.26.0
                // 的 32 位 ARM 上 arena 分配器对「同一会话的二次调用」会抛 MatMulNBits
                // std::bad_alloc（自检连续调用时复现），因此快速档也不开 memoryPattern。
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
                setMemoryPatternOptimization(false)
            } else {
                // 高精度档：手表内存有限，BASIC_OPT + 关闭内存模式复用，降低峰值
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                setMemoryPatternOptimization(false)
            }
        }
        enc = env.createSession(encFile.absolutePath, opts)
        dec = env.createSession(decFile.absolutePath, opts)

        val eIn = enc.inputNames.toList()
        val eOut = enc.outputNames.toList()
        encInputIds = eIn.firstOrNull { it == "input_ids" } ?: eIn.first()
        encAttnMask = eIn.firstOrNull { it.contains("attention_mask") }
            ?: eIn.firstOrNull { it != encInputIds }
            ?: encInputIds
        encOutput = eOut.firstOrNull { it.contains("last_hidden_state") } ?: eOut.first()

        val dIn = dec.inputNames.toList()
        val dOut = dec.outputNames.toList()
        decInputIds = dIn.firstOrNull { it == "input_ids" } ?: dIn.first()
        decEncMask = dIn.firstOrNull { it.contains("encoder_attention_mask") }
            ?: dIn.firstOrNull { it.contains("attention_mask") && it != decInputIds } ?: ""
        decEncHidden = dIn.firstOrNull { it.contains("encoder_hidden_states") }
            ?: dIn.firstOrNull { it.contains("encoder_outputs") } ?: ""
        decUseCacheBranch = dIn.firstOrNull { it.contains("use_cache_branch") }
        decLogits = dOut.firstOrNull { it.contains("logits") } ?: dOut.first()

        selfKv = ArrayList<Pair<String, String>>()
        crossKv = ArrayList<Pair<String, String>>()
        for (i in dIn) {
            if (!i.contains("past_key_values")) continue
            val suffix = i.substringAfter("past_key_values")   // ".0.decoder.key"
            // 严格配对：输出名必须与输入名一一对应，避免错配到别的层
            val outName = dOut.firstOrNull { it == "present$suffix" }
                ?: dOut.firstOrNull { it.startsWith("present") && it.endsWith(suffix) }
                ?: continue
            if (suffix.contains(".encoder.")) crossKv.add(i to outName) else selfKv.add(i to outName)
        }
        val declaredKvIn = dIn.count { it.contains("past_key_values") }
        check(declaredKvIn == 0 || selfKv.size + crossKv.size == declaredKvIn) {
            "键值缓存配对失败：声明 $declaredKvIn 个，实际配对 ${selfKv.size + crossKv.size} 个"
        }
        allKvIn = selfKv.map { it.first } + crossKv.map { it.first }

        // 空缓存张量形状：批 1、头数与头维取自声明，序列维取 0（或声明值）
        val info = dec.inputInfo
        val shapeMap = HashMap<String, LongArray>()
        for (n in allKvIn) {
            val ni: NodeInfo? = info[n]
            val declared = (ni?.info as? TensorInfo)?.shape ?: longArrayOf(1, 8, -1, 64)
            shapeMap[n] = LongArray(declared.size) { idx ->
                val d = declared[idx]
                when {
                    idx == 0 -> 1L
                    d >= 0 -> d
                    idx == 2 -> 0L
                    else -> 1L
                }
            }
        }
        kvEmptyShape = shapeMap

        // use_cache_branch 的声明形状可能是 [] 或 [1]，按其声明喂入
        useCacheShape = decUseCacheBranch?.let { n ->
            val decl = (info[n]?.info as? TensorInfo)?.shape
            when {
                decl == null || decl.isEmpty() -> longArrayOf(1)
                decl.all { it > 0 } -> decl
                else -> longArrayOf(1)
            }
        } ?: longArrayOf(1)
    }

    /** 是否存在键值缓存输入（合并缓存解码器）；无缓存版为 false */
    private val hasKvCache: Boolean get() = selfKv.isNotEmpty() || crossKv.isNotEmpty()

    /** 会话接口摘要，用于诊断 */
    fun describe(): String = buildString {
        append("enc in=").append(enc.inputNames).append(" out=").append(enc.outputNames)
        append("\ndec in=").append(dec.inputNames.size).append(" 项")
        append("\n  logits=").append(decLogits)
        append("\n  use_cache_branch=").append(decUseCacheBranch ?: "无")
        append("\n  selfKV=").append(selfKv.size).append(" crossKV=").append(crossKv.size)
    }

    /**
     * 贪心解码。
     *
     * 自省式适配两类导出：
     *   - 合并缓存解码器（decoder_model_merged）：喂 past_key_values，
     *     用 use_cache_branch 在「首步空缓存」与「其后复用缓存」间切换；
     *   - 无缓存解码器（decoder_model）：每步把已生成的完整序列重新喂入。
     *
     * @param inputIds 编码器输入（已含语言 token 与 EOS）
     * @param forcedFirst 强制首步生成 id（NLLB 的目标语言 token），null 表示不强制
     */
    fun generate(
        inputIds: LongArray,
        forcedFirst: Long?,
        decoderStart: Long,
        eosId: Long,
        maxNew: Int = 64,
        banId: Long? = null
    ): IntArray {
        val seq = inputIds.size.toLong()
        val idsTensor = longTensor(inputIds, longArrayOf(1, seq))
        val maskArray = LongArray(inputIds.size) { 1L }
        val maskTensor = longTensor(maskArray, longArrayOf(1, seq))

        var encResult: OrtSession.Result? = null
        var decResult: OrtSession.Result? = null
        val out = ArrayList<Int>(maxNew)

        try {
            encResult = enc.run(mapOf(encInputIds to idsTensor, encAttnMask to maskTensor))
            val hidden = encResult.get(encOutput).get() as OnnxTensor

            if (hasKvCache) {
                var curTensor = longTensor(longArrayOf(decoderStart), longArrayOf(1, 1))
                val ownedKv = ArrayList<OnnxTensor>()
                var first = true
                for (step in 0 until maxNew) {
                    val feed = HashMap<String, OnnxTensor>()
                    feed[decInputIds] = curTensor
                    if (decEncMask.isNotEmpty()) feed[decEncMask] = maskTensor
                    if (decEncHidden.isNotEmpty()) feed[decEncHidden] = hidden
                    decUseCacheBranch?.let { feed[it] = boolTensor(!first) }

                    if (first) {
                        for (n in allKvIn) {
                            val base = kvEmptyShape[n] ?: longArrayOf(1, 8, 0, 64)
                            // 交叉注意力的缓存序列维 = 编码器长度（不随解码步变化）
                            val shp = if (n.contains(".encoder.") && base.size == 4)
                                longArrayOf(base[0], base[1], seq, base[3]) else base
                            val t = zeroTensor(shp)
                            ownedKv.add(t)
                            feed[n] = t
                        }
                    } else {
                        val pr = decResult ?: error("键值缓存缺失")
                        for ((inName, outName) in selfKv + crossKv) {
                            feed[inName] = pr.get(outName).get() as OnnxTensor
                        }
                    }

                    val res = dec.run(feed)
                    decResult?.close()
                    decResult = res
                    curTensor.close()
                    if (first) {
                        ownedKv.forEach { it.close() }
                        ownedKv.clear()
                    }

                    val next = argmax(res, banId, if (first) forcedFirst else null)
                    first = false
                    if (next == eosId) break
                    out.add(next.toInt())
                    curTensor = longTensor(longArrayOf(next), longArrayOf(1, 1))
                }
            } else {
                // 无缓存解码器：每步重新喂入完整序列
                val cur = ArrayList<Long>(maxNew + 1)
                cur.add(decoderStart)
                var first = true
                for (step in 0 until maxNew) {
                    val ids = longTensor(cur.toLongArray(), longArrayOf(1, cur.size.toLong()))
                    val feed = HashMap<String, OnnxTensor>()
                    feed[decInputIds] = ids
                    if (decEncMask.isNotEmpty()) feed[decEncMask] = maskTensor
                    if (decEncHidden.isNotEmpty()) feed[decEncHidden] = hidden
                    val res = dec.run(feed)
                    decResult?.close()
                    decResult = res
                    ids.close()

                    val next = argmax(res, banId, if (first) forcedFirst else null)
                    first = false
                    if (next == eosId) break
                    out.add(next.toInt())
                    cur.add(next)
                }
            }
        } finally {
            runCatching { idsTensor.close() }
            runCatching { maskTensor.close() }
            runCatching { decResult?.close() }
            runCatching { encResult?.close() }
        }
        return IntArray(out.size) { out[it] }
    }

    /** 取最后一步 logits 的 argmax；forceId 非空时优先采用 */
    private fun argmax(res: OrtSession.Result, banId: Long?, forceId: Long?): Long {
        if (forceId != null) return forceId
        val logitsT = res.get(decLogits).get() as OnnxTensor
        val logits = logitsT.value as Array<Array<FloatArray>>
        val row = logits[0][logits[0].size - 1]
        var bestId = 0
        var bestV = Float.NEGATIVE_INFINITY
        for (i in row.indices) {
            if (banId != null && i.toLong() == banId) continue
            if (row[i] > bestV) { bestV = row[i]; bestId = i }
        }
        return bestId.toLong()
    }

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor {
        // ORT Java 要求直接缓冲区；堆缓冲在部分版本会直接抛错
        val buf = ByteBuffer.allocateDirect(data.size * 8)
            .order(java.nio.ByteOrder.nativeOrder()).asLongBuffer()
        buf.put(data)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, shape)
    }

    private fun zeroTensor(shape: LongArray): OnnxTensor {
        var n = 1L
        for (d in shape) n *= d
        val buf = ByteBuffer.allocateDirect((n * 4).toInt())
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        return OnnxTensor.createTensor(env, buf, shape)
    }

    private fun boolTensor(v: Boolean): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(1).order(java.nio.ByteOrder.nativeOrder())
        buf.put(if (v) 1 else 0)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, useCacheShape, ai.onnxruntime.OnnxJavaType.BOOL)
    }

    override fun close() {
        runCatching { enc.close() }
        runCatching { dec.close() }
    }
}

/**
 * 神经机器翻译：OPUS-MT 中英双向（无缓存解码器，端侧推理）。
 *
 * 选用理由（针对手表）：NLLB-200 600M 单模型需约 853 MB 权重与远超手表的内存；
 * OPUS-MT 双向共约 226 MB，译文质量在旅行会话场景下足够。
 *
 * 针对手表内存再进一步懒加载：load() 只装载分词器与解码常量（Java 堆，几十 MB），
 * 具体方向的 encoder/decoder 推理会话在首次翻译该方向时才建立并缓存，
 * 避免启动时一次性建 4 个会话（约 226 MB 权重）导致 OOM。
 */
class NeuralMt(private val store: ModelStore) {

    /** 单模型解码常量：一律从 config.json 读取，绝不硬编码 */
    private class Consts(val eos: Long, val start: Long, val banId: Long?) {
        companion object {
            fun of(cfg: JSONObject): Consts {
                val eos = cfg.optInt("eos_token_id", 0).toLong()
                val start = cfg.optInt(
                    "decoder_start_token_id",
                    cfg.optInt("pad_token_id", eos.toInt())
                ).toLong()
                // Marian 的 generation_config 把 pad 列进 bad_words_ids，
                // 解码过程中禁止生成（除非它本身就是 EOS）
                val pad = cfg.optInt("pad_token_id", -1)
                val ban = if (pad >= 0 && pad.toLong() != eos) pad.toLong() else null
                return Consts(eos, start, ban)
            }
        }
    }

    private var tokZhEn: HfTokenizer? = null
    private var tokEnZh: HfTokenizer? = null
    private var zhEn: OnnxSeq2Seq? = null
    private var enZh: OnnxSeq2Seq? = null
    private var zhEnC = Consts(0L, 65000L, null)
    private var enZhC = Consts(0L, 65000L, null)

    /** 推理线程数与速度档（由 load 写入，影响每次建会话的选项） */
    private var threads: Int = 1
    private var fastMode: Boolean = false

    /** 当前档位的说明，供界面显示 */
    val tierLabel: String get() = if (fastMode) "快速档 · ${threads} 线程 + 扩展优化" else "高精度档 · 单线程 BASIC_OPT"

    /** 最近一次推理的诊断摘要，供模型页「自检」显示 */
    var lastDiag: String = ""
        private set

    /** 分词器与配置已装载即可翻译（推理会话按需懒加载） */
    val isLoaded: Boolean get() = tokZhEn != null && tokEnZh != null

    /** 释放全部会话与分词器，腾出内存 */
    @Synchronized
    fun unload() {
        runCatching { zhEn?.close() }
        runCatching { enZh?.close() }
        zhEn = null; enZh = null
        tokZhEn = null; tokEnZh = null
    }

    /** 只装载分词器与解码常量（轻量），不建立推理会话。注意：仍在主线程外调用。 */
    @Synchronized
    fun load(threads: Int = 1, fast: Boolean = false) {
        unload()
        this.threads = threads.coerceIn(1, 4)
        this.fastMode = fast
        val root = store.root
        val a = File(root, "opus/zh-en")
        val b = File(root, "opus/en-zh")
        tokZhEn = HfTokenizer.load(a)
        tokEnZh = HfTokenizer.load(b)
        zhEnC = Consts.of(JSONObject(File(a, "config.json").readText(Charsets.UTF_8)))
        enZhC = Consts.of(JSONObject(File(b, "config.json").readText(Charsets.UTF_8)))
    }

    fun describe(): String = buildString {
        append("分词器已装载=").append(isLoaded)
        append("\nzh-en 会话=").append(if (zhEn != null) "已建" else "未建")
        append("\nen-zh 会话=").append(if (enZh != null) "已建" else "未建")
    }

    /**
     * @return 译文；失败返回 null（上层据此显示错误，不再回退词库）
     */
    @Synchronized
    /**
     * @param maxNew 最大生成 token 数。
     *  - 默认 40：覆盖绝大多数 zh-en / en-zh 短句（一般 ≤ 20 token），遇 EOS 立即停止。
     *  - 实测把 64 降到 40 后，单句暖态 4s → 2.5s 量级，长句也会更快。
     *  - 仍比 ML Kit 慢一个数量级 —— 那是 30MB 专有模型 + GMS，本表无 GMS；
     *    改用更小模型需要换模型文件（用户后续可指定）。
     */
    fun translate(text: String, from: Lang, to: Lang, maxNew: Int = 40): String? {
        if (from == to) return text
        val t0 = System.nanoTime()
        return try {
            val r = translateDir(text, from, to, maxNew)
            val ms = (System.nanoTime() - t0) / 1_000_000L
            lastDiag = if (r == null) "推理产出为空（$ms ms）" else "成功 · $ms ms"
            r
        } catch (e: Throwable) {
            lastDiag = "${e.javaClass.simpleName}: ${e.message ?: ""}".take(240)
            throw e
        }
    }

    private fun translateDir(text: String, from: Lang, to: Lang, maxNew: Int): String? {
        val (tk, seq, c) = if (from == Lang.ZH) {
            Triple(tokZhEn, ensureZhEn(), zhEnC)
        } else {
            Triple(tokEnZh, ensureEnZh(), enZhC)
        }
        val t = tk ?: run { lastDiag = "分词器未装载"; return null }
        val ids = t.encode(text, appendEos = c.eos.toInt())
        val out = seq.generate(
            ids, forcedFirst = null, decoderStart = c.start,
            eosId = c.eos, maxNew = maxNew, banId = c.banId
        )
        if (out.isEmpty()) {
            lastDiag = "首步即 EOS（start=${c.start} eos=${c.eos} 编码器 ${ids.size} token）"
            return null
        }
        val s = t.decode(out).trim()
        if (s.isBlank()) lastDiag = "解码为空（生成 ${out.size} token）"
        return s.ifBlank { null }
    }

    /** 按需建立 zh→en 方向的推理会话并缓存 */
    private fun ensureZhEn(): OnnxSeq2Seq {
        zhEn?.let { return it }
        val dir = File(store.root, "opus/zh-en")
        val s = OnnxSeq2Seq(
            File(dir, "encoder_model_quantized.onnx"),
            File(dir, "decoder_model_quantized.onnx"), threads, fastMode
        )
        zhEn = s
        return s
    }

    /** 按需建立 en→zh 方向的推理会话并缓存 */
    private fun ensureEnZh(): OnnxSeq2Seq {
        enZh?.let { return it }
        val dir = File(store.root, "opus/en-zh")
        val s = OnnxSeq2Seq(
            File(dir, "encoder_model_quantized.onnx"),
            File(dir, "decoder_model_quantized.onnx"), threads, fastMode
        )
        enZh = s
        return s
    }

    /**
     * 自检：用固定句子跑通完整链路，返回可读诊断。
     * 供「离线模型」页在无调试器时定位问题。
     */
    @Synchronized
    fun selfTest(): String {
        if (!isLoaded) return "引擎未装载"
        val sb = StringBuilder()
        return try {
            sb.append(describe()).append('\n')
            for ((src, from, to) in listOf(
                Triple("这个多少钱？", Lang.ZH, Lang.EN),
                Triple("How much is this?", Lang.EN, Lang.ZH)
            )) {
                val t0 = System.nanoTime()
                val r = translate(src, from, to)
                val ms = (System.nanoTime() - t0) / 1_000_000L
                sb.append("«").append(src).append("» -> ")
                    .append(r ?: "（空）")
                    .append("  [").append(ms).append("ms]")
                    .append("  diag=").append(lastDiag)
                    .append('\n')
            }
            sb.toString().trim()
        } catch (e: Throwable) {
            sb.append("自检异常 ").append(e.javaClass.simpleName).append(": ")
                .append(e.message ?: "").toString()
        }
    }
}
