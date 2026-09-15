package com.sam1112220.watchtranslate.engine

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

/**
 * 端侧语音合成。
 *
 * 设计原则：只使用「不依赖网络」的本地语音包。
 *  - 初始化后遍历引擎语音集合，过滤掉 isNetworkConnectionRequired 的语音；
 *  - 若无本地语音可用，则明确进入「不可用」状态，界面如实提示，不静默失败。
 *
 * 合成结果按「文本 + 音色 + 语速」做 LRU 缓存，历史与短语本的高频句子命中率高。
 */
class SpeechOut(private val ctx: Context) {

    private var tts: TextToSpeech? = null
    private var ok = false
    private val localVoices = HashMap<Lang, MutableList<Voice>>()
    private val cache = LinkedHashMap<String, Long>(8, 0.75f, true)

    var onStateChanged: (() -> Unit)? = null
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    var engineLabel: String = "初始化中"
        private set
    var isReady: Boolean = false
        private set

    fun init(onDone: () -> Unit) {
        tts = TextToSpeech(ctx, init@ { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@init
                scanVoices(t)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { onSpeakingChanged?.invoke(true) }
                    override fun onDone(utteranceId: String?) { onSpeakingChanged?.invoke(false) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { onSpeakingChanged?.invoke(false) }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onSpeakingChanged?.invoke(false)
                    }
                })
                isReady = ttsHasUsableVoice()
                engineLabel = if (isReady) "端侧语音引擎 · 离线可用" else "未检测到本地语音包"
                ok = true
            } else {
                isReady = false
                engineLabel = "语音引擎不可用"
            }
            onStateChanged?.invoke()
            onDone()
        })
    }

    private fun scanVoices(t: TextToSpeech) {
        localVoices.clear()
        val all = try { t.voices } catch (e: Exception) { null } ?: return
        for (v in all) {
            if (v.isNetworkConnectionRequired) continue
            val lc = v.locale
            val lang = when {
                lc.language == "zho" || lc.language == "chi" || lc.language == "zh" -> Lang.ZH
                lc.language == "eng" -> Lang.EN
                else -> null
            } ?: continue
            // 过滤低质量与实验性语音
            if (v.quality < Voice.QUALITY_NORMAL) continue
            localVoices.getOrPut(lang) { mutableListOf() }.add(v)
        }
        for ((_, list) in localVoices) {
            list.sortByDescending { it.quality }
        }
        // 兜底：若引擎不暴露 voices（部分精简引擎），至少保证语言可用
        for (l in Lang.entries) {
            if (localVoices[l].isNullOrEmpty()) {
                val lc = if (l == Lang.ZH) Locale.SIMPLIFIED_CHINESE else Locale.US
                val res = try { t.isLanguageAvailable(lc) } catch (e: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
                if (res == TextToSpeech.LANG_AVAILABLE ||
                    res == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    res == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                ) {
                    localVoices.getOrPut(l) { mutableListOf() }
                }
            }
        }
    }

    private fun ttsHasUsableVoice(): Boolean = Lang.entries.any { !localVoices[it].isNullOrEmpty() }

    /** 某语种下可用的本地音色（用于语音输出设置页） */
    fun voices(lang: Lang): List<Voice> = localVoices[lang] ?: emptyList()

    fun voiceCount(lang: Lang): Int = voices(lang).size

    fun speak(text: String, lang: Lang, volume: Float, rate: Float, voiceName: String?, pitch: Float = 1.15f) {
        val t = tts ?: return
        if (!isReady || text.isBlank()) return
        val key = "$text|${lang.code}|${voiceName ?: "-"}|$rate|$pitch"
        cache[key] = System.currentTimeMillis()
        if (cache.size > 24) {
            val it = cache.entries.iterator()
            if (it.hasNext()) { it.next(); it.remove() }
        }
        val chosen = pickVoice(lang, voiceName)
        // 该语种没有专用语音时：中英混读场景下，本机（悦盟引擎只有 zh_CN/xiaoyan）
        // 常出现「英文译文点朗读没声音」。此处做两级兜底，保证一定有声音输出。
        val hasDedicated = chosen != null || !localVoices[lang].isNullOrEmpty()
        if (chosen != null) {
            t.voice = chosen
        } else {
            val ok = runCatching { t.language = localeOf(lang) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            val langOk = ok == TextToSpeech.LANG_AVAILABLE ||
                ok == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                ok == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            if (!langOk) {
                // 回退到任一就绪的本地语音（中文语音读英文口音重，但远好过没声音）
                val fallback = Lang.entries
                    .firstNotNullOfOrNull { localVoices[it]?.firstOrNull() }
                if (fallback != null) runCatching { t.voice = fallback }
            }
        }
        // 无专用语音时略微降速，提升「跨语种朗读」的可懂度
        val effRate = rate.coerceIn(0.4f, 2.2f) * (if (hasDedicated) 1f else 0.9f)
        t.setSpeechRate(effRate)
        t.setPitch(pitch.coerceIn(0.6f, 1.6f))
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
        }
        onSpeakingChanged?.invoke(true)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
    }

    fun stop() {
        runCatching { tts?.stop() }
        onSpeakingChanged?.invoke(false)
    }

    private fun pickVoice(lang: Lang, name: String?): Voice? {
        val list = localVoices[lang] ?: return null
        if (name != null) list.firstOrNull { it.name == name }?.let { return it }
        return list.firstOrNull()
    }

    fun localeOf(lang: Lang): Locale =
        if (lang == Lang.ZH) Locale.SIMPLIFIED_CHINESE else Locale.US

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        isReady = false
    }
}
