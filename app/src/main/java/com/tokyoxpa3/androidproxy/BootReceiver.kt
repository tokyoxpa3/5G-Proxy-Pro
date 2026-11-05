package com.tokyoxpa3.androidproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 開機（或 App 更新）後還原代理。
 *
 * 三個條件同時成立才會啟動，缺一不可：
 *  1. 使用者在設定裡打開「重新開機後自動啟動」（[Socks5ProxyService.PREF_AUTO_START]，**預設關**）
 *  2. 關機前代理是開著的（[Socks5ProxyService.PREF_WAS_RUNNING]，按過停止就是 false）
 *  3. 服務現在沒在跑
 *
 * 條件 2 不能省：只有條件 1 的話，使用者按了停止、重開機，代理還是會自己起來 ——
 * 那正是「使用者不想被做的事」，而且代理預設無認證，等於在區域網路上開一個開放式 SOCKS5。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return

        val prefs = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Socks5ProxyService.PREF_AUTO_START, false)) return
        if (!prefs.getBoolean(Socks5ProxyService.PREF_WAS_RUNNING, false)) return
        if (Socks5ProxyService.isServiceRunning) return

        // 埠號即時持久化在 proxy_port（字串），使用者改過就沿用，不要退回 1080
        val port = prefs.getString("proxy_port", "1080")?.toIntOrNull() ?: 1080
        Log.i(Socks5ProxyService.TAG, "開機自動啟動代理（${intent.action}, port=$port）")
        try {
            context.startForegroundService(
                Intent(context, Socks5ProxyService::class.java).apply {
                    action = Socks5ProxyService.ACTION_START_PROXY
                    putExtra(Socks5ProxyService.EXTRA_PORT, port)
                    // 開機初期蜂巢 / Wi-Fi 常常還沒就緒，失敗是常態。
                    // 帶這個 extra 讓服務改成延遲重試，而不是直接 FAILED 收掉。
                    putExtra(Socks5ProxyService.EXTRA_AUTO_START, true)
                }
            )
        } catch (e: Exception) {
            // BOOT_COMPLETED 在 Android 12+ 背景啟動前景服務的豁免清單內，但廠商 ROM
            // 可能額外限制。失敗只記 log，不要讓使用者的開機流程出現崩潰提示。
            Log.e(Socks5ProxyService.TAG, "開機自動啟動失敗: ${e.message}")
        }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }
}
