package com.tokyoxpa3.androidproxy

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 應用內日誌環形緩衝：`Log.*` 的替代品，寫 logd 的同時留一份在記憶體裡。
 *
 * ## 為什麼需要它
 *
 * `Log.*` 只寫進 logd，一般使用者拿不到 —— 而回報問題的人幾乎都不會用 adb
 * （issue #8 就是這樣：只給了一句 `SocketTimeoutException`，看不出是哪一步）。
 * 把最近 [CAPACITY] 行留在 process 記憶體裡，讓 UI 一鍵複製，才有機會拿到第一手證據。
 *
 * ## 範圍
 *
 * 只涵蓋 Kotlin/Java 側。native 的 `__android_log_print` 不經過這裡，由
 * [LogExporter] 以 `logcat --pid=<本行程>` 補齊。
 *
 * ## 為什麼是環形緩衝而不是直接寫檔
 *
 * 寫檔要處理 fsync、輪替、儲存空間不足，而這些日誌只在「使用者按下複製」那一刻
 * 才有意義。留在記憶體裡，App 重啟就清空，也天然避免把舊問題的痕跡混進新回報。
 */
object AppLog {

    /** 保留行數。1500 行約 150~250 KB，對記憶體與剪貼簿都還在合理範圍。 */
    private const val CAPACITY = 1500

    private val lines = ArrayDeque<String>(CAPACITY)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 寫入 logd 並留下一行。時間戳在鎖內取，確保緩衝內的順序與時間單調一致。
     * 刻意不用 `ThreadLocal<SimpleDateFormat>`：這裡不是熱路徑，一把鎖更省事。
     */
    @Synchronized
    private fun record(level: Char, tag: String, msg: String, tr: Throwable?) {
        val line = StringBuilder(96)
            .append(timeFormat.format(Date()))
            .append(' ').append(level).append('/').append(tag).append(": ").append(msg)
        if (tr != null) line.append('\n').append(Log.getStackTraceString(tr))
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast(line.toString())
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        record('D', tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        record('I', tag, msg, null)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        record('W', tag, msg, null)
    }

    fun w(tag: String, msg: String, tr: Throwable) {
        Log.w(tag, msg, tr)
        record('W', tag, msg, tr)
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        record('E', tag, msg, null)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
        record('E', tag, msg, tr)
    }

    /** 目前緩衝內容（換行分隔）。空的時候回空字串，呼叫端自己決定要顯示什麼。 */
    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "" else lines.joinToString("\n")

    @Synchronized
    fun size(): Int = lines.size

    /** 只取最後 [n] 行，給「診斷報告」這種不該無限膨脹的地方用。 */
    @Synchronized
    fun tail(n: Int): String {
        if (lines.isEmpty()) return ""
        if (n <= 0) return ""
        if (n >= lines.size) return lines.joinToString("\n")
        return lines.drop(lines.size - n).joinToString("\n")
    }
}
