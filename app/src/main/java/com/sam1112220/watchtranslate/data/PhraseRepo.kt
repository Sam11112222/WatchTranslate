package com.sam1112220.watchtranslate.data

import android.content.Context
import org.json.JSONArray

data class Phrase(val zh: String, val en: String)

data class PhraseCategory(
    val key: String,
    val icon: String,
    val items: List<Phrase>
)

/** 内置离线短语本：随包内置，点击即朗读，不联网。 */
class PhraseRepo(ctx: Context) {

    val categories: List<PhraseCategory> = runCatching {
        val raw = ctx.assets.open("phrasebook.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val its = o.getJSONArray("items")
            PhraseCategory(
                key = o.getString("key"),
                icon = o.optString("icon", "place"),
                items = (0 until its.length()).map { j ->
                    val p = its.getJSONArray(j)
                    Phrase(p.getString(0), p.getString(1))
                }
            )
        }
    }.getOrElse { emptyList() }

    fun totalCount(): Int = categories.sumOf { it.items.size }
}
