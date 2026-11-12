package com.tokyoxpa3.androidproxy.network

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object VpnDetector {

    /**
     * 檢查系統目前是否有任何運作中的 VPN 網路（TRANSPORT_VPN）。
     *
     * ⚠️ 這個函式回答的是「**系統裡有沒有 VPN**」，**不是**「**本 App 的路由有沒有被接管**」。
     * per-app VPN（split tunneling）未包含本 App 時，這裡照樣回 true，但本 App 的蜂巢式
     * 出口完全可用。因此**不可拿回傳值當「能不能出去」的判據**，也不可單憑它就宣稱
     * 連線被 VPN 阻擋 —— 那會把真實斷線誤判成 VPN 阻擋（症狀：永不自動重建）。
     *
     * 它唯一的用途是「措辭」：在已由 `Network.bindSocket` 探測確認無法綁定蜂巢式出口
     * 之後，決定把原因歸給 VPN 還是歸給網路本身。判定邏輯見 [NetworkHealthClassifier]。
     */
    fun isVpnActive(cm: ConnectivityManager?): Boolean {
        if (cm == null) return false
        return try {
            // 優先檢查 activeNetwork
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val activeCaps = cm.getNetworkCapabilities(activeNetwork)
                if (activeCaps != null && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true
                }
            }
            // 遍歷所有已註冊網路（涵蓋非預設路由或 split-tunneling 的 VPN）
            val networks = cm.allNetworks
            networks.any { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        } catch (e: Exception) {
            false
        }
    }
}
