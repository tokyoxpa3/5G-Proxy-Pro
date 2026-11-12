package com.tokyoxpa3.androidproxy

import android.os.Process as AndroidProcess
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 把「App 內環形緩衝」與「本行程的 logcat」合併成一段可直接貼上回報的純文字。
 *
 * 這是 `adb logcat -s PublicIPChecker CellularNetwork Socks5ProxyService` 的
 * App 內版本 —— 回報問題的人多半沒有 adb，也不該為了一個 bug 去裝 platform-tools。
 *
 * ## 為什麼不是全部靠 logcat
 *
 * Android 4.1 起 `READ_LOGS` 是 signature 權限，App 只能看到**自己 UID** 的日誌
 * （`logcat` 以 App 的 UID 執行，logd 依 UID 過濾）。這在多數 ROM 上可用，但
 * OEM 可能以 SELinux 擋掉 `logcat` 執行檔，或把輸出截斷。所以：
 *
 *  - [AppLog] 是**保證有**的那一份（Kotlin 側）；
 *  - logcat 是**補充**（native `__android_log_print`、framework 訊息）。
 *
 * 兩份都放進去，使用者貼上來的內容就是完整的。
 */
object LogExporter {

    /** logcat 最多取幾行。太大會讓剪貼簿與貼文都變得難處理。 */
    private const val LOGCAT_TAIL_LINES = 1200

    /** 等 logcat 結束的上限。`-d` 是 dump 後立刻結束，正常幾十毫秒。 */
    private const val LOGCAT_WAIT_MS = 2500L

    /**
     * 上一次匯出時從 logcat 拿到幾行；-1 代表 logcat 不可用。
     * 只用來寫一行摘要，讓「複製了但貼出來是空的」這種回報能被區分。
     */
    @Volatile
    var lastLogcatLines: Int = -1
        private set

    fun build(): String {
        val sb = StringBuilder(64 * 1024)
        sb.append("===== 5G Proxy Pro app log (in-process) =====\n")
        val app = AppLog.dump()
        sb.append(if (app.isEmpty()) "(empty)\n" else app).append('\n')

        val pid = AndroidProcess.myPid()
        sb.append("\n===== logcat (pid=").append(pid).append(") =====\n")
        val cat = readLogcat(pid)
        sb.append(if (cat.isBlank()) "(unavailable on this ROM)\n" else cat)
        return sb.toString()
    }

    /**
     * 讀取本行程的 logcat。
     *
     * 讀取放在獨立執行緒、主執行緒只等 [LOGCAT_WAIT_MS]：`readText()` 會阻塞到 EOF，
     * 若某些 ROM 的 logcat 不結束，直接呼叫會把 UI 卡死。逾時就 destroy 並回傳已收到的部分。
     */
    private fun readLogcat(pid: Int): String {
        // 讀取執行緒與呼叫端會同時碰到這個 list（逾時時），必須同步。
        val collected = Collections.synchronizedList(ArrayList<String>(LOGCAT_TAIL_LINES))
        val done = CountDownLatch(1)
        var process: java.lang.Process? = null
        try {
            process = ProcessBuilder(
                "logcat", "-d", "-v", "threadtime",
                "--pid=$pid", "-t", LOGCAT_TAIL_LINES.toString()
            ).redirectErrorStream(true).start()
            val proc = process
            Thread {
                try {
                    proc.inputStream.bufferedReader().useLines { seq ->
                        seq.forEach { collected.add(it) }
                    }
                } catch (_: Exception) {
                    // 逾時 destroy 後 read 會拋 IOException，屬於預期路徑。
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }.start()

            done.await(LOGCAT_WAIT_MS, TimeUnit.MILLISECONDS)
            try { proc.destroy() } catch (_: Exception) {}
        } catch (_: Exception) {
            // 沒有 logcat 執行檔、SELinux 拒絕、或 ProcessBuilder 本身失敗：退回只用 AppLog。
            try { process?.destroy() } catch (_: Exception) {}
            lastLogcatLines = -1
            return ""
        }
        return synchronized(collected) {
            lastLogcatLines = collected.size
            collected.joinToString("\n")
        }
    }
}
