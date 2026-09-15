package com.sam1112220.watchtranslate.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryItem(
    val id: Long,
    val src: String,
    val dst: String,
    val from: String,
    val to: String,
    val ts: Long,
    val ms: Long,
    val fav: Boolean
)

/** 翻译历史：本地 SQLite，永不外传。 */
class HistoryDb(ctx: Context) : SQLiteOpenHelper(ctx, "wt_history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE history(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              src TEXT NOT NULL,
              dst TEXT NOT NULL,
              from_lang TEXT NOT NULL,
              to_lang TEXT NOT NULL,
              ts INTEGER NOT NULL,
              ms INTEGER NOT NULL DEFAULT 0,
              fav INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ts ON history(ts DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS history")
        onCreate(db)
    }

    fun add(src: String, dst: String, from: String, to: String, ms: Long): Long {
        val v = ContentValues().apply {
            put("src", src); put("dst", dst)
            put("from_lang", from); put("to_lang", to)
            put("ts", System.currentTimeMillis()); put("ms", ms); put("fav", 0)
        }
        return writableDatabase.insert("history", null, v)
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM history", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun list(limit: Int = 200): List<HistoryItem> {
        val out = ArrayList<HistoryItem>()
        readableDatabase.rawQuery(
            "SELECT id,src,dst,from_lang,to_lang,ts,ms,fav FROM history ORDER BY ts DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    HistoryItem(
                        c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getString(4), c.getLong(5), c.getLong(6),
                        c.getInt(7) == 1
                    )
                )
            }
        }
        return out
    }

    fun setFav(id: Long, fav: Boolean) {
        writableDatabase.execSQL(
            "UPDATE history SET fav=? WHERE id=?",
            arrayOf<Any>(if (fav) 1 else 0, id)
        )
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM history")
    }

    fun dbBytes(): Long {
        val f = readableDatabase.path ?: return 0L
        return java.io.File(f).let { if (it.exists()) it.length() else 0L }
    }
}
