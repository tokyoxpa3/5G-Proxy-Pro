package com.example.androidproxy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.app.AlertDialog

object PowerPermissionHelper {

    /**
     * 檢查 App 是否已經在電池最佳化白名單中
     */
    fun isWhitelisted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    /**
     * 嘗試直接彈出系統對話框請求忽略電池最佳化
     * 如果失敗或被攔截，則退回到手動設定頁面
     */
    fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // 如果系統攔截了直接請求，則退回到原本的手動設定頁面
                jumpToSettings(context)
            }
        } else {
            jumpToSettings(context)
        }
    }

    /**
     * 根據廠牌顯示對應的提示對話框
     */
    fun showOptimizationDialog(context: Context) {
        if (isWhitelisted(context)) {
            // 雖然已加入白名單，但小米的 5G 省電是獨立開關，仍需提醒
            val isXiaomi = Build.MANUFACTURER.lowercase().let { 
                it.contains("xiaomi") || it.contains("poco") || it.contains("redmi") 
            }
            
            val message = if (isXiaomi) {
                context.getString(R.string.dialog_whitelist_completed_msg_xiaomi)
            } else {
                context.getString(R.string.dialog_whitelist_completed_msg_default)
            }

            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.dialog_whitelist_completed_title))
                .setMessage(message)
                .setPositiveButton(context.getString(R.string.btn_check_settings)) { _, _ -> jumpToSettings(context) }
                .setNegativeButton(context.getString(R.string.btn_close), null)
                .show()
        } else {
            // 尚未設定白名單時的引導
            val brand = Build.MANUFACTURER.lowercase()
            val (title, message) = getBrandSpecificText(context, brand)

            AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(context.getString(R.string.btn_go_settings)) { _, _ -> requestIgnoreBatteryOptimization(context) }
                .setNegativeButton(context.getString(R.string.btn_cancel), null)
                .show()
        }
    }

    private fun getBrandSpecificText(context: Context, brand: String): Pair<String, String> {
        return when {
            // 針對小米/POCO 更新精確路徑
            brand.contains("xiaomi") || brand.contains("poco") || brand.contains("redmi") -> {
                context.getString(R.string.dialog_xiaomi_title) to 
                context.getString(R.string.dialog_xiaomi_msg)
            }
            brand.contains("samsung") -> {
                context.getString(R.string.dialog_samsung_title) to 
                context.getString(R.string.dialog_samsung_msg)
            }
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> {
                context.getString(R.string.dialog_oppo_title) to 
                context.getString(R.string.dialog_oppo_msg)
            }
            brand.contains("vivo") -> {
                context.getString(R.string.dialog_vivo_title) to 
                context.getString(R.string.dialog_vivo_msg)
            }
            else -> {
                context.getString(R.string.dialog_default_title) to 
                context.getString(R.string.dialog_default_msg)
            }
        }
    }

    private fun jumpToSettings(context: Context) {
        val brand = Build.MANUFACTURER.lowercase()
        val intent = Intent()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            when {
                // 小米/POCO：跳轉到「省電與電池」主頁面
                // 用戶需依指示點擊「更多電池功能」
                brand.contains("xiaomi") || brand.contains("poco") -> {
                    intent.component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.powercenter.PowerSettingsInternalActivity"
                    )
                }
                
                // 其他廠牌維持原樣...
                brand.contains("samsung") -> {
                    intent.action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                }
                brand.contains("oppo") || brand.contains("realme") -> {
                    intent.component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                }
                brand.contains("vivo") -> {
                    intent.component = ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                }
                else -> {
                    intent.action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                }
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // 如果專用 Intent 失敗，嘗試通用電池設定
            try {
                val fallbackIntent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                val lastResort = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                lastResort.data = Uri.fromParts("package", context.packageName, null)
                lastResort.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(lastResort)
            }
        }
    }
}