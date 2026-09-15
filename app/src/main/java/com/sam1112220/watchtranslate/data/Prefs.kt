package com.sam1112220.watchtranslate.data

import android.content.Context
import androidx.core.content.edit

enum class ShapeMode { AUTO, ROUND, RECT }

/**
 * 翻译速度档位。
 * FAST：多线程 + 更激进的图优化 + 结果缓存，适合日常短句；
 * ACCURATE：单线程 BASIC_OPT，内存峰值最低，质量与稳定性优先。
 */
enum class SpeedTier { FAST, ACCURATE }

/** 全部设置项本地持久化（SharedPreferences），不上传、不同步。 */
class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("wt_prefs", Context.MODE_PRIVATE)

    var shapeMode: ShapeMode
        get() = runCatching { ShapeMode.valueOf(sp.getString(K_SHAPE, "AUTO")!!) }
            .getOrDefault(ShapeMode.AUTO)
        set(v) = sp.edit { putString(K_SHAPE, v.name) }

    /** 首次自动识别结果 */
    var detectedShape: ShapeMode
        get() = runCatching { ShapeMode.valueOf(sp.getString(K_DETECTED, "ROUND")!!) }
            .getOrDefault(ShapeMode.ROUND)
        set(v) = sp.edit { putString(K_DETECTED, v.name) }

    /** 自动朗读译文 */
    var autoSpeak: Boolean
        get() = sp.getBoolean(K_AUTO_SPEAK, true)
        set(v) = sp.edit { putBoolean(K_AUTO_SPEAK, v) }

    /** 音量 0..100 */
    var volume: Int
        get() = sp.getInt(K_VOL, 72)
        set(v) = sp.edit { putInt(K_VOL, v.coerceIn(0, 100)) }

    /** 语速 0.5..2.0 */
    var rate: Float
        get() = sp.getFloat(K_RATE, 1.1f)
        set(v) = sp.edit { putFloat(K_RATE, v.coerceIn(0.4f, 2.2f)) }

    /** 音调 0.6..1.6，默认 1.15（略清亮，听感更"活"） */
    var pitch: Float
        get() = sp.getFloat(K_PITCH, 1.15f)
        set(v) = sp.edit { putFloat(K_PITCH, v.coerceIn(0.6f, 1.6f)) }

    var voiceZh: String?
        get() = sp.getString(K_VOICE_ZH, null)
        set(v) = sp.edit { putString(K_VOICE_ZH, v) }

    var voiceEn: String?
        get() = sp.getString(K_VOICE_EN, null)
        set(v) = sp.edit { putString(K_VOICE_EN, v) }

    /** 我说 */
    var myLang: String
        get() = sp.getString(K_MY, "ZH")!!
        set(v) = sp.edit { putString(K_MY, v) }

    /** 对方说 */
    var peerLang: String
        get() = sp.getString(K_PEER, "EN")!!
        set(v) = sp.edit { putString(K_PEER, v) }

    /** 精简模式 */
    var slimMode: Boolean
        get() = sp.getBoolean(K_SLIM, false)
        set(v) = sp.edit { putBoolean(K_SLIM, v) }

    /** 翻译速度档位（默认高精度，保持既有行为） */
    var speedTier: SpeedTier
        get() = runCatching { SpeedTier.valueOf(sp.getString(K_SPEED, "ACCURATE")!!) }
            .getOrDefault(SpeedTier.ACCURATE)
        set(v) = sp.edit { putString(K_SPEED, v.name) }

    /** 最近一次语对 */
    var lastFrom: String
        get() = sp.getString(K_FROM, "ZH")!!
        set(v) = sp.edit { putString(K_FROM, v) }

    var lastTo: String
        get() = sp.getString(K_TO, "EN")!!
        set(v) = sp.edit { putString(K_TO, v) }

    private companion object {
        const val K_SHAPE = "shape_mode"
        const val K_DETECTED = "detected_shape"
        const val K_AUTO_SPEAK = "auto_speak"
        const val K_VOL = "volume"
        const val K_RATE = "rate"
        const val K_PITCH = "pitch"
        const val K_VOICE_ZH = "voice_zh"
        const val K_VOICE_EN = "voice_en"
        const val K_MY = "my_lang"
        const val K_PEER = "peer_lang"
        const val K_SLIM = "slim_mode"
        const val K_SPEED = "speed_tier"
        const val K_FROM = "last_from"
        const val K_TO = "last_to"
    }
}
