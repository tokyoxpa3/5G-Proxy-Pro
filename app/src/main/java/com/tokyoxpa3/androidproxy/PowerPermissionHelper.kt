package com.tokyoxpa3.androidproxy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.app.AlertDialog

object PowerPermissionHelper {

    fun isWhitelisted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                jumpToSettings(context)
            }
        } else {
            jumpToSettings(context)
        }
    }

    fun showOptimizationDialog(context: Context) {
        if (isWhitelisted(context)) {
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
                brand.contains("xiaomi") || brand.contains("poco") -> {
                    intent.component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.powercenter.PowerSettingsInternalActivity"
                    )
                }
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