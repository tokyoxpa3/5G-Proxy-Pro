package com.tokyoxpa3.androidproxy.relay

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * /status 附帶的 OS 端資訊：各網路的「系統是否驗證可上網」與整機行動數據用量。
 *
 * 只讀系統既有的值，不發任何網路請求（不吃流量）。
 */
object DeviceStatus {

    /**
     * 系統對每個網路的判定。VALIDATED = Android 自己的連通性檢查通過（真的能上網）；
     * 有網路但沒 VALIDATED 通常是：數據用盡被擋 / 電信導向頁（captive portal）/ 訊號差。
     */
    fun networkJson(context: Context): JSONObject {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val list = JSONArray()
        var cellularValidated: Boolean? = null
        var anyValidated = false
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            // IMS（VoLTE）/ MMS 等不提供上網的網路不列入，否則會出現兩個 cellular 而誤判
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            list.put(JSONObject().apply {
                put("transport", transport)
                put("interface", cm.getLinkProperties(n)?.interfaceName ?: JSONObject.NULL)
                put("validated", validated)
                put("captive_portal", captive)
                put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                if (Build.VERSION.SDK_INT >= 28) {
                    put("suspended", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
                }
            })
            if (transport == "cellular") cellularValidated = (cellularValidated == true) || validated
            if (transport != "vpn" && validated) anyValidated = true
        }
        return JSONObject().apply {
            // 本 App 的預設網路是 Tailscale VPN，所以「能不能上網」以實體網路（非 VPN）判斷
            put("internet_ok", anyValidated)
            // null = 目前沒有行動網路（飛航模式、數據關閉、無訊號）
            put("cellular_internet_ok", cellularValidated ?: JSONObject.NULL)
            put("airplane_mode", AirplaneAssistant.isAirplaneOn(context))
            put("networks", list)
        }
    }

    /**
     * 整機行動數據用量（所有 App 合計，與「設定 > 數據使用量」同一來源）。
     * 需要「使用情形存取權」；Android 9 以下另需 READ_PHONE_STATE 取得 subscriberId。
     * 系統以約 2 小時為一桶統計，最近的用量可能延遲出現。
     */
    fun dataUsageJson(context: Context): JSONObject {
        if (!hasUsageAccess(context)) {
            return JSONObject().put("error", "usage access not granted")
        }
        val nsm = context.getSystemService(NetworkStatsManager::class.java)
        val subscriberId = subscriberId(context)
        if (Build.VERSION.SDK_INT < 29 && subscriberId == null) {
            // Android 9 以下傳 null 只會比對到 0 位元組，回傳 0 會被誤讀成「沒用流量」
            return JSONObject().put("error", "READ_PHONE_STATE not granted")
        }
        val now = System.currentTimeMillis()
        fun startOf(field: Int): Long = Calendar.getInstance().apply {
            if (field == Calendar.MONTH) set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        fun bytes(start: Long): JSONObject = try {
            val b = nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, subscriberId, start, now)
            JSONObject().apply {
                put("rx_bytes", b.rxBytes)
                put("tx_bytes", b.txBytes)
                put("total_bytes", b.rxBytes + b.txBytes)
                put("total_mb", Math.round((b.rxBytes + b.txBytes) / 1_000_000.0 * 10) / 10.0)
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        }
        return JSONObject().apply {
            put("today", bytes(startOf(Calendar.DAY_OF_MONTH)))
            // 月曆月（1 號起算）；系統「數據使用週期」的起始日屬於隱藏 API，讀不到
            put("this_month", bytes(startOf(Calendar.MONTH)))
            put("last_30_days", bytes(now - 30L * 24 * 60 * 60 * 1000))
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun subscriberId(context: Context): String? {
        // Android 10 起一般 App 拿不到 subscriberId，但傳 null 即代表「所有行動網路」
        if (Build.VERSION.SDK_INT >= 29) return null
        return try {
            context.getSystemService(TelephonyManager::class.java).subscriberId
        } catch (e: SecurityException) {
            null
        }
    }
}
