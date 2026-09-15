package com.sam1112220.watchtranslate.engine

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit 离线翻译（unbundled 版，不依赖 Google Play Services），用于「快速档」。
 *
 * 关键点：`com.google.mlkit:translate` 的 unbundled 版把运行时打进 APK，
 * 语言包通过 HTTPS 直接从 Google 模型托管下载一次（中英双向共用约 44MB），
 * 之后完全在设备端推理、不传文本到服务器 —— 因此**无需 GMS**。
 *
 * 参考实现：WatchTranslatorGlass 的 TranslatorManager（已确认在 OPPO Watch
 * OWW251 / Android 11 / 无 GMS 环境上下载并跑通）。
 */
class MlKitEngine {

    private val enZh: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()
    )
    private val zhEn: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    @Volatile
    var downloading = false
        private set
    @Volatile
    var ready = false
        private set

    /** 预下载两个方向的语言包（仅首次联网，之后瞬时返回）。 */
    suspend fun ensureModels(): Boolean {
        if (ready) return true
        downloading = true
        return try {
            download(enZh)
            download(zhEn)
            ready = true
            true
        } catch (e: Exception) {
            false
        } finally {
            downloading = false
        }
    }

    /**
     * 翻译。模型未就绪时会先联网下载一次（阻塞直到下载完成）。
     * @return 译文；失败返回 null（上层显示错误）
     */
    suspend fun translate(text: String, from: Lang, to: Lang): String? {
        if (from == to) return text
        val t = if (from == Lang.ZH) zhEn else enZh
        if (!ready) {
            downloading = true
            try {
                download(t)
                ready = true
            } finally {
                downloading = false
            }
        }
        return suspendCancellableCoroutine { cont ->
            t.translate(text)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    private suspend fun download(t: Translator) {
        suspendCancellableCoroutine<Unit> { cont ->
            t.downloadModelIfNeeded()
                .addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    fun close() {
        runCatching { enZh.close() }
        runCatching { zhEn.close() }
    }
}
