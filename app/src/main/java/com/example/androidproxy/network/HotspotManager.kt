package com.example.androidproxy.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface as JavaNetworkInterface

object HotspotManager {

    private const val TAG = "HotspotManager"

    // 常見的 Android 熱點預設網段
    private val hotspotSubnets = listOf(
        "192.168.43.", // Android 預設熱點
        "192.168.44.",
        "192.168.45.",
        "192.168.46.",
        "192.168.47.",
        "192.168.48.",
        "10.0.0.",     // 部分三星/客製化 ROM
        "172.20.10."   // iOS 風格網段
    )

    /**
     * 獲取熱點分享時的 IP (通常是 192.168.43.1)
     * @return IP 地址字串，熱點未開啟或獲取失敗返回 null
     */
    fun getHotspotIP(context: Context): String? {
        // 方法一: 透過反射取得 ConnectivityManager.getTetheredIfaces()
        // (低版本 Android 可用，高版本會遭隱藏 API 限制而失敗，自動 fallback)
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val method = ConnectivityManager::class.java.getMethod("getTetheredIfaces")
                @Suppress("UNCHECKED_CAST")
                val tetheredIfaces = method.invoke(cm) as? Array<String>
                if (tetheredIfaces != null) {
                    for (ifaceName in tetheredIfaces) {
                        val ip = getInterfaceIPv4(ifaceName)
                        if (ip != null) return ip
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Reflection getTetheredIfaces failed, fallback to enumeration", e)
        }

        // 方法二: 枚舉所有網路介面，用熱點網段判斷
        val currentWifiIP = getCurrentWifiIP(context)
        return try {
            val interfaces = JavaNetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                if (isCellularInterface(iface.name)) continue

                val addresses = iface.inetAddresses ?: continue
                for (addr in addresses) {
                    if (addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip == currentWifiIP) continue
                        if (hotspotSubnets.any { ip.startsWith(it) }) {
                            return ip
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to enumerate network interfaces", e)
            null
        }
    }

    private fun getInterfaceIPv4(ifaceName: String): String? {
        return try {
            val iface = JavaNetworkInterface.getByName(ifaceName) ?: return null
            if (!iface.isUp || iface.isLoopback) return null
            val addresses = iface.inetAddresses ?: return null
            for (addr in addresses) {
                if (addr is Inet4Address) {
                    val ip = addr.hostAddress ?: continue
                    if (!ip.startsWith("127.")) return ip
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get IPv4 for interface $ifaceName", e)
            null
        }
    }

    private fun getCurrentWifiIP(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip != 0) {
                String.format(
                    "%d.%d.%d.%d",
                    (ip and 0xff),
                    (ip shr 8 and 0xff),
                    (ip shr 16 and 0xff),
                    (ip shr 24 and 0xff)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isCellularInterface(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("rmnet") ||
            lower.contains("pdp") ||
            lower.contains("ccmni") ||
            lower.contains("ppp") ||
            lower.contains("wwan") ||
            lower.contains("radio") ||
            lower.contains("rndis")
    }
}
