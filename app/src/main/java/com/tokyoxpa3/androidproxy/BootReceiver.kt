package com.tokyoxpa3.androidproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 開機（或 App 更新後）自動還原代理。
 *
 * 只在「開關開啟」且「使用者上次沒有按停止」時才啟動，見 Socks5ProxyService.PREF_WAS_RUNNING。
 * BOOT_COMPLETED 要等使用者第一次解鎖才會送達；在那之前代理不會起來（刻意不支援 Direct Boot，
 * 否則 SOCKS5 帳密得搬到未加密的 device-protected storage）。
 * BOOT_COMPLETED / MY_PACKAGE_REPLACED 都在「可從背景啟動前景服務」的豁免清單內。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val prefs = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Socks5ProxyService.PREF_AUTO_START, true)) return
        if (!prefs.getBoolean(Socks5ProxyService.PREF_WAS_RUNNING, false)) return
        if (Socks5ProxyService.isServiceRunning) return

        val port = prefs.getString("proxy_port", "1080")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 1080
        Log.i(Socks5ProxyService.TAG, "自動啟動代理（${intent.action}），port=$port")
        try {
            context.startForegroundService(
                Intent(context, Socks5ProxyService::class.java).apply {
                    action = Socks5ProxyService.ACTION_START_PROXY
                    putExtra(Socks5ProxyService.EXTRA_PORT, port)
                    putExtra(Socks5ProxyService.EXTRA_AUTO_START, true)
                }
            )
        } catch (e: Exception) {
            Log.e(Socks5ProxyService.TAG, "自動啟動失敗: ${e.message}")
        }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // 部分廠商（HTC / 小米舊機）的快速開機只送這個
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }
}
