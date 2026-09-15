package com.sam1112220.watchtranslate.engine

/** 语种 */
enum class Lang(val code: String, val label: String) {
    ZH("zh", "中文"),
    EN("en", "English");

    val other: Lang get() = if (this == ZH) EN else ZH
}

object LangDetect {
    /** 中日韩统一表意文字及中文标点 */
    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF ||
            c.code in 0x3400..0x4DBF ||
            c.code in 0xF900..0xFAFF ||
            c.code in 0x3000..0x303F ||
            c.code in 0xFF00..0xFFEF

    /**
     * 端侧语种判定：统计 CJK 字符占比。
     * 占比 >= 0.25 判为中文，否则判为英文。纯数字 / 符号沿用 fallback。
     */
    fun detect(text: String, fallback: Lang = Lang.ZH): Lang {
        val s = text.trim()
        if (s.isEmpty()) return fallback
        var cjk = 0
        var letter = 0
        for (c in s) {
            if (c.isWhitespace()) continue
            if (isCjk(c)) { cjk++; letter++ } else if (c.isLetter()) letter++
        }
        if (letter == 0) return fallback
        return if (cjk.toFloat() / letter >= 0.25f) Lang.ZH else Lang.EN
    }
}
