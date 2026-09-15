package com.sam1112220.watchtranslate.engine.neural

import org.json.JSONObject
import java.io.File

/**
 * 纯 Kotlin 实现的 HuggingFace tokenizer 流水线（完全离线，无外部依赖）。
 *
 * 覆盖本工程用到的两类模型族：
 *   NLLB-200 : 归一化 Precompiled(≈NFKC) → 预切分 Metaspace → 词表 BPE(51.4 万 merge)
 *   OPUS-MT  : 归一化 无 → 预切分 Sequence[WhitespaceSplit, Metaspace] → 词表 Unigram
 *
 * 词表来自预处理后的 vocab.tsv（行号即 id），merge 来自 merges.tsv，
 * 避免在手表上解析 16.5 MB 的 tokenizer.json。
 */
class HfTokenizer private constructor(
    private val idToToken: Array<String>,
    private val tokenToId: HashMap<String, Int>,
    private val scores: FloatArray,
    private val merges: HashMap<String, Int>,
    private val meta: JSONObject,
    private val modelType: String,
    private val unkId: Int,
    private val byteFallback: Boolean,
    private val specialIds: HashSet<Int>,
    private val addedByContent: HashMap<String, Int>
) {

    private val normalizerChain: List<(String) -> String> = buildNormalizer(meta.opt("normalizer"))
    private val preTokenizers: List<JSONObject> = buildPreTokenizers(meta.opt("preTokenizer"))
    private val decoderChain: List<DecodeOp> = buildDecoder(meta.opt("decoder"))

    private val maxTokenChars: Int = run {
        var m = 1
        val limit = minOf(idToToken.size, 200_000)
        for (i in 0 until limit) if (idToToken[i].length > m) m = idToToken[i].length
        m.coerceAtMost(32)
    }

    /** added token 按长度降序，用于最长优先切分（NLLB 的 , ? 。都是 added token） */
    private val addedSorted: List<String> = addedByContent.keys
        .filter { it.isNotEmpty() }
        .sortedByDescending { it.length }

    val vocabSize: Int get() = idToToken.size

    // ==================================================================
    // 归一化
    // ==================================================================
    private fun buildNormalizer(node: Any?): List<(String) -> String> {
        val out = ArrayList<(String) -> String>()
        fun walk(n: Any?) {
            val o = n as? JSONObject ?: return
            when (o.optString("type")) {
                "Sequence" -> o.optJSONArray("normalizers")?.let { arr ->
                    for (i in 0 until arr.length()) walk(arr.opt(i))
                }
                // Precompiled(charsmap) 等价于 sentencepiece nmt_nfkc，
                // CJK 场景下 Java 的 NFKC 与之等价度最高。
                "Precompiled", "NFKC" -> out.add { s ->
                    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
                }
                "Lowercase" -> out.add { s -> s.lowercase() }
                "Strip" -> {
                    val l = o.optBoolean("strip_left", true)
                    val r = o.optBoolean("strip_right", true)
                    out.add { s ->
                        var t = s
                        if (l) t = t.trimStart()
                        if (r) t = t.trimEnd()
                        t
                    }
                }
                "Prepend" -> {
                    val p = o.optString("prepend", "")
                    out.add { s -> p + s }
                }
                "Replace" -> {
                    val pat = patternOf(o.opt("pattern"))
                    if (pat != null) {
                        val content = o.optString("content", "")
                        out.add { s -> pat.replace(s, content) }
                    }
                }
            }
        }
        walk(node)
        return out
    }

    private fun patternOf(p: Any?): Regex? {
        val o = p as? JSONObject ?: return null
        return when {
            o.has("String") -> Regex(Regex.escape(o.getString("String")))
            o.has("Regex") -> runCatching { Regex(o.getString("Regex")) }.getOrNull()
            else -> null
        }
    }

    private fun normalize(s: String): String {
        var t = s
        for (f in normalizerChain) t = f(t)
        return t
    }

    // ==================================================================
    // 预切分
    // ==================================================================
    private fun buildPreTokenizers(node: Any?): List<JSONObject> {
        val o = node as? JSONObject ?: return emptyList()
        return if (o.optString("type") == "Sequence") {
            val arr = o.optJSONArray("pretokenizers") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } else listOf(o)
    }

    /**
     * 语义等价于 HF 的预切分链：
     *   WhitespaceSplit 先按空白切词，随后 Metaspace 给每个词补 ▁ 前缀，
     *   最后把各片段直接拼接（▁ 本身即代表词间空格）。
     */
    private fun applyPreTokenizer(s: String): String {
        if (preTokenizers.isEmpty()) return s
        var words: List<String>? = null
        var cur = s
        for (pt in preTokenizers) {
            when (pt.optString("type")) {
                "WhitespaceSplit", "Whitespace" -> {
                    words = cur.trim().split(WHITESPACE).filter { it.isNotEmpty() }
                    cur = words.joinToString(" ")
                }
                "Metaspace" -> {
                    val rep = pt.optString("replacement", "▁").firstOrNull() ?: '▁'
                    val addPrefix = pt.optBoolean("add_prefix_space", true)
                    val w = words
                    cur = if (w != null) {
                        if (addPrefix) w.joinToString("") { "$rep$it" } else w.joinToString("")
                    } else {
                        var t = cur.replace(' ', rep)
                        if (addPrefix && (t.isEmpty() || t[0] != rep)) t = "$rep$t"
                        t
                    }
                    words = null
                }
                else -> Unit
            }
        }
        return cur
    }

    // ==================================================================
    // 编码
    // ==================================================================
    fun encode(text: String, prependIds: IntArray = IntArray(0), appendEos: Int = -1): LongArray {
        val seq = applyPreTokenizer(normalize(text))
        val ids = ArrayList<Int>(seq.length + 4)
        for (p in prependIds) ids.add(p)
        if (seq.isNotEmpty()) {
            for (b in encodeSequence(seq)) ids.add(b)
        }
        if (appendEos >= 0) ids.add(appendEos)
        return LongArray(ids.size) { ids[it].toLong() }
    }

    /**
     * 先按 added token 最长优先切分，其余片段再走词表模型。
     * 这一步不可省：NLLB 把 , ? 。等标点登记为 added token，
     * 若不做切分，这些字符会落到词表外，导致整句 Viterbi 失败退化为逐字符 unk。
     */
    private fun encodeSequence(seq: String): IntArray {
        val out = ArrayList<Int>(seq.length)
        val buf = StringBuilder()
        var i = 0
        while (i < seq.length) {
            var hit: String? = null
            for (c in addedSorted) {
                if (seq.startsWith(c, i)) { hit = c; break }
            }
            if (hit != null) {
                if (buf.isNotEmpty()) { out.addAll(modelEncode(buf.toString()).toList()); buf.setLength(0) }
                out.add(addedByContent[hit]!!)
                i += hit.length
            } else {
                buf.append(seq[i]); i++
            }
        }
        if (buf.isNotEmpty()) out.addAll(modelEncode(buf.toString()).toList())
        return IntArray(out.size) { out[it] }
    }

    private fun modelEncode(s: String): IntArray =
        if (s.isEmpty()) IntArray(0) else if (modelType == "BPE") bpe(s) else unigram(s)

    /** Unigram：Viterbi 最优路径 */
    private fun unigram(s: String): IntArray {
        val n = s.length
        val best = FloatArray(n + 1) { NEG }
        val from = IntArray(n + 1) { -1 }
        val tok = IntArray(n + 1) { -1 }
        best[0] = 0f
        for (i in 1..n) {
            val lo = maxOf(0, i - maxTokenChars)
            var j = i - 1
            while (j >= lo) {
                if (best[j] > NEG) {
                    val id = tokenToId[s.substring(j, i)]
                    if (id != null) {
                        val sc = best[j] + scores[id]
                        if (sc > best[i]) { best[i] = sc; from[i] = j; tok[i] = id }
                    }
                }
                j--
            }
        }
        if (best[n] <= NEG) {
            // 尾部存在词表外字符时，保留可达前缀的最优路径，仅对尾部退化处理
            var reach = n - 1
            while (reach > 0 && best[reach] <= NEG) reach--
            val head = ArrayList<Int>()
            var p = reach
            while (p > 0 && from[p] >= 0) { head.add(tok[p]); p = from[p] }
            val out = ArrayList<Int>(head.size + 8)
            for (k in head.indices.reversed()) out.add(head[k])
            for (k in reach until n) out.addAll(fallbackCharWise(s[k].toString()).toList())
            return IntArray(out.size) { out[it] }
        }
        val rev = ArrayList<Int>()
        var p = n
        while (p > 0 && from[p] >= 0) { rev.add(tok[p]); p = from[p] }
        val out = IntArray(rev.size)
        for (k in rev.indices) out[k] = rev[rev.size - 1 - k]
        return out
    }

    /** BPE：反复合并当前最低 rank 的词对（一次合并全部同形出现） */
    private fun bpe(s: String): IntArray {
        var syms = ArrayList<String>(s.length)
        for (c in s) syms.add(c.toString())
        while (syms.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestA = ""
            var bestB = ""
            for (i in 0 until syms.size - 1) {
                val r = merges["${syms[i]} ${syms[i + 1]}"] ?: continue
                if (r < bestRank) { bestRank = r; bestA = syms[i]; bestB = syms[i + 1] }
            }
            if (bestRank == Int.MAX_VALUE) break
            val merged = bestA + bestB
            val out = ArrayList<String>(syms.size)
            var i = 0
            while (i < syms.size) {
                if (i < syms.size - 1 && syms[i] == bestA && syms[i + 1] == bestB) {
                    out.add(merged); i += 2
                } else {
                    out.add(syms[i]); i++
                }
            }
            syms = out
        }
        return IntArray(syms.size) { tokenToId[syms[it]] ?: unkId }
    }

    private fun fallbackCharWise(s: String): IntArray {
        val out = ArrayList<Int>(s.length)
        for (c in s) {
            val id = tokenToId[c.toString()]
            when {
                id != null -> out.add(id)
                byteFallback -> {
                    for (b in c.toString().toByteArray(Charsets.UTF_8)) {
                        val hex = "%02X".format(b.toInt() and 0xFF)
                        out.add(tokenToId["<$hex>"] ?: unkId)
                    }
                }
                else -> out.add(unkId)
            }
        }
        return IntArray(out.size) { out[it] }
    }

    /** NLLB 需要把源语言 token 前置到编码器输入 */
    fun langTokenId(code: String): Int? = addedByContent[code]

    // ==================================================================
    // 解码
    // ==================================================================
    private sealed class DecodeOp {
        class Metaspace(val rep: Char, val addPrefix: Boolean) : DecodeOp()
        class Replace(val re: Regex, val content: String) : DecodeOp()
        class Strip(val left: Boolean, val right: Boolean) : DecodeOp()
        object Passthrough : DecodeOp()
    }

    private fun buildDecoder(node: Any?): List<DecodeOp> {
        val out = ArrayList<DecodeOp>()
        fun walk(n: Any?) {
            val o = n as? JSONObject
            if (o == null) { out.add(DecodeOp.Passthrough); return }
            when (o.optString("type")) {
                "Sequence" -> o.optJSONArray("decoders")?.let { arr ->
                    for (i in 0 until arr.length()) walk(arr.opt(i))
                }
                "Metaspace" -> out.add(
                    DecodeOp.Metaspace(
                        o.optString("replacement", "▁").firstOrNull() ?: '▁',
                        o.optBoolean("add_prefix_space", true)
                    )
                )
                "Replace" -> patternOf(o.opt("pattern"))?.let {
                    out.add(DecodeOp.Replace(it, o.optString("content", "")))
                }
                "Strip" -> out.add(
                    DecodeOp.Strip(o.optBoolean("strip_left", true), o.optBoolean("strip_right", true))
                )
                "ByteLevel", "WordPiece", "Fuse", "ByteFallback" -> out.add(DecodeOp.Passthrough)
            }
        }
        walk(node)
        if (out.isEmpty()) out.add(DecodeOp.Passthrough)
        return out
    }

    fun decode(ids: IntArray, skipSpecial: Boolean = true): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id < 0 || id >= idToToken.size) continue
            if (skipSpecial && specialIds.contains(id)) continue
            sb.append(idToToken[id])
        }
        var s = sb.toString()
        for (op in decoderChain) {
            s = when (op) {
                is DecodeOp.Metaspace -> {
                    var t = s.replace(op.rep, ' ')
                    if (op.addPrefix && t.startsWith(" ")) t = t.substring(1)
                    t
                }
                is DecodeOp.Replace -> op.re.replace(s, op.content)
                is DecodeOp.Strip -> {
                    var t = s
                    if (op.left) t = t.trimStart()
                    if (op.right) t = t.trimEnd()
                    t
                }
                DecodeOp.Passthrough -> s
            }
        }
        return s
    }

    fun isSpecial(id: Int): Boolean = specialIds.contains(id)

    companion object {
        private const val NEG = -1.0e30f
        private val WHITESPACE = Regex("\\s+")

        fun load(dir: File): HfTokenizer {
            val vocabFile = File(dir, "vocab.tsv")
            require(vocabFile.exists()) { "缺少 vocab.tsv: ${vocabFile.path}" }
            val meta = JSONObject(File(dir, "tokmeta.json").readText(Charsets.UTF_8))
            val modelType = meta.optString("modelType", "Unigram")

            val idToToken = ArrayList<String>(262144)
            val scores = ArrayList<Float>(262144)
            vocabFile.bufferedReader(Charsets.UTF_8, 1 shl 16).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val tab = line.lastIndexOf('\t')
                    if (tab < 0) { idToToken.add(unescape(line)); scores.add(0f) }
                    else {
                        idToToken.add(unescape(line.substring(0, tab)))
                        scores.add(line.substring(tab + 1).toFloatOrNull() ?: 0f)
                    }
                }
            }

            val tokenToId = HashMap<String, Int>(idToToken.size * 2)
            for (i in idToToken.indices) tokenToId.putIfAbsent(idToToken[i], i)

            val merges = HashMap<String, Int>(1 shl 20)
            val mergeFile = File(dir, "merges.tsv")
            if (mergeFile.exists()) {
                mergeFile.bufferedReader(Charsets.UTF_8, 1 shl 16).use { r ->
                    var rank = 0
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.isNotEmpty()) merges[line] = rank
                        rank++
                    }
                }
            }

            val specialIds = HashSet<Int>()
            val addedByContent = HashMap<String, Int>()
            meta.optJSONArray("addedTokens")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optInt("id", -1)
                    if (id < 0) continue
                    addedByContent[o.optString("content", "")] = id
                    if (o.optBoolean("special", false)) specialIds.add(id)
                }
            }

            return HfTokenizer(
                Array(idToToken.size) { idToToken[it] },
                tokenToId,
                FloatArray(scores.size) { scores[it] },
                merges,
                meta,
                modelType,
                tokenToId["<unk>"] ?: 0,
                meta.optBoolean("byteFallback", false),
                specialIds,
                addedByContent
            )
        }

        private fun unescape(s: String): String =
            s.replace("\\n", "\n").replace("\\t", "\t")
    }
}
