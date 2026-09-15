package com.sam1112220.watchtranslate.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("MM-dd", Locale.getDefault())

    fun time(ts: Long): String = timeFmt.format(Date(ts))

    fun relTime(ts: Long): String {
        val now = System.currentTimeMillis()
        val d = now - ts
        return when {
            d < 60_000 -> "刚刚"
            d < 3600_000 -> "${d / 60_000} 分钟前"
            isSameDay(ts, now) -> time(ts)
            d < 48 * 3600_000L -> "昨天"
            else -> dateFmt.format(Date(ts))
        }
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
            ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
    }

    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb < 1) "${(bytes / 1024.0).toInt()} KB"
        else if (mb < 1024) String.format(Locale.US, "%.1f MB", mb)
        else String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    /** 用于「1.1 GB」这种大字徽标 */
    fun sizeShort(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb < 1024) String.format(Locale.US, "%.0f MB", mb)
        else String.format(Locale.US, "%.1f GB", mb / 1024.0)
    }

    fun nowTime(): String = timeFmt.format(Date())
}

object Device {
    /** 屏幕形状判定：圆形 / 长方形 */
    fun isRound(ctx: Context): Boolean =
        ctx.resources.configuration.isScreenRound

    fun faceLabel(ctx: Context): String = if (isRound(ctx)) "圆形表盘" else "长方形表盘"

    fun screenSizeDp(ctx: Context): String {
        val m: DisplayMetrics = ctx.resources.displayMetrics
        return "${(m.widthPixels / m.density).toInt()} × ${(m.heightPixels / m.density).toInt()}"
    }

    fun deviceLabel(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** 应用私有目录占用 */
    fun appDataBytes(ctx: Context): Long = dirSize(ctx.filesDir) + dirSize(ctx.cacheDir) +
        dirSize(File(ctx.filesDir.parentFile, "databases"))

    private fun dirSize(f: File?): Long {
        if (f == null || !f.exists()) return 0
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { dirSize(it) } ?: 0
    }

    /** 内置资源占用（assets 解出的语言包大小） */
    fun assetBytes(ctx: Context, names: List<String>): Long =
        names.sumOf { n ->
            runCatching { ctx.assets.open(n).use { it.available().toLong() } }.getOrDefault(0L)
        }

    fun freeBytes(): Long = runCatching {
        val st = StatFs(Environment.getDataDirectory().path)
        st.availableBytes
    }.getOrDefault(0L)

    fun totalBytes(): Long = runCatching {
        val st = StatFs(Environment.getDataDirectory().path)
        st.totalBytes
    }.getOrDefault(0L)
}
