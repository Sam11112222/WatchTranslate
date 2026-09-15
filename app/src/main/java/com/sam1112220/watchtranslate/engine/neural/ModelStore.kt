package com.sam1112220.watchtranslate.engine.neural

import android.content.Context
import java.io.File

/**
 * 离线模型仓库。
 *
 * 模型随包内置在 assets/models 下（APK 内不压缩，aapt 已配置 noCompress），
 * 首次启动按需解压到应用私有目录；解压一次后常驻复用——
 * 重建 ONNX 推理会话的耗时远大于推理本身，运行期不再重复解压。
 */
class ModelStore(private val ctx: Context) {

    /** 相对 assets/models 的路径 */
    object Files {
        const val OPUS_ZH_EN = "opus/zh-en"
        const val OPUS_EN_ZH = "opus/en-zh"
        const val OPUS_ENC = "encoder_model_quantized.onnx"
        const val OPUS_DEC = "decoder_model_quantized.onnx"
        // 分词器用预处理的紧凑包（tokenizer.json 已在构建期转为 vocab.tsv + tokmeta.json）
        const val OPUS_VOCAB_TSV = "vocab.tsv"
        const val OPUS_TOKMETA = "tokmeta.json"
        const val OPUS_CFG = "config.json"

        /** 端侧模型（OPUS-MT 中英双向）所需文件 */
        fun slim(): List<String> = listOf(
            "$OPUS_ZH_EN/$OPUS_ENC", "$OPUS_ZH_EN/$OPUS_DEC",
            "$OPUS_ZH_EN/$OPUS_VOCAB_TSV", "$OPUS_ZH_EN/$OPUS_TOKMETA", "$OPUS_ZH_EN/$OPUS_CFG",
            "$OPUS_EN_ZH/$OPUS_ENC", "$OPUS_EN_ZH/$OPUS_DEC",
            "$OPUS_EN_ZH/$OPUS_VOCAB_TSV", "$OPUS_EN_ZH/$OPUS_TOKMETA", "$OPUS_EN_ZH/$OPUS_CFG",
        )
    }

    val root: File = File(ctx.filesDir, "models")

    fun destFile(rel: String): File = File(root, rel)

    /** assets 内的原始体积，用于界面显示真实占用 */
    fun assetBytes(rel: String): Long = runCatching {
        ctx.assets.open("models/$rel").use { it.available().toLong() }
    }.getOrDefault(0L)

    fun packBytes(files: List<String>): Long = files.sumOf { assetBytes(it) }

    /** 已解压到私有目录的总体积 */
    fun installedBytes(): Long {
        if (!root.exists()) return 0L
        fun walk(f: File): Long =
            if (f.isFile) f.length() else (f.listFiles()?.sumOf { walk(it) } ?: 0L)
        return walk(root)
    }

    fun isReady(rel: String): Boolean {
        val f = destFile(rel)
        val expect = assetBytes(rel)
        return f.exists() && f.length() > 0 && (expect <= 0 || f.length() >= expect)
    }

    fun allReady(files: List<String>): Boolean = files.all { isReady(it) }

    /**
     * 解压指定文件。已完整存在则跳过。
     * @param onProgress (done, total) 总字节进度，用于首次启动的装载界面
     */
    fun extract(files: List<String>, onProgress: (Long, Long) -> Unit) {
        val total = packBytes(files)
        var done = 0L
        for (rel in files) {
            val dest = destFile(rel)
            if (isReady(rel)) {
                done += dest.length()
                onProgress(done, total)
                continue
            }
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            runCatching {
                ctx.assets.open("models/$rel").use { input ->
                    tmp.outputStream().buffered(1 shl 16).use { output ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                if (tmp.length() <= 0) error("解压为空：$rel")
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            }.onFailure {
                tmp.delete()
                throw IllegalStateException("模型解压失败：$rel", it)
            }
        }
    }

    fun purge() {
        runCatching { root.deleteRecursively() }
    }

    /** 读取 assets 文本（配置与分词器直接在内存解析，无需落地） */
    fun readAssetText(rel: String): String =
        ctx.assets.open("models/$rel").bufferedReader(Charsets.UTF_8).use { it.readText() }
}
