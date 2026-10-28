package com.tokyoxpa3.androidproxy.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 開機（或 App 更新後）若 relay 關機前是開著的（relay_enabled），就自動重新啟動。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        if (!context.getSharedPreferences("relay", Context.MODE_PRIVATE).getBoolean("relay_enabled", false)) return
        if (RelayService.isRunning) return
        Log.i("TailnetRelay", "自動啟動 relay（${intent.action}）")
        try {
            // 不帶 EXTRA_PORT：服務會從設定讀埠號
            context.startForegroundService(Intent(context, RelayService::class.java).setAction(RelayService.ACTION_START))
        } catch (e: Exception) {
            Log.e("TailnetRelay", "自動啟動失敗: ${e.message}")
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
