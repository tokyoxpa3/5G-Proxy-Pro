package com.tokyoxpa3.androidproxy.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkHealthClassifierTest {

    private fun classify(
        hasInternet: Boolean = true,
        validated: Boolean = true,
        bindOk: Boolean = true,
        vpnActive: Boolean = false,
        probeOk: Boolean = true
    ) = NetworkHealthClassifier.classify(
        hasInternetCapability = hasInternet,
        cellularValidated = validated,
        bindSucceeded = bindOk,
        vpnActive = vpnActive,
        probeSucceeded = probeOk
    )

    // --- 正常路徑 ---

    @Test
    fun healthyPathIsOk() {
        assertEquals(NetworkHealthStatus.OK, classify())
    }

    // --- 被 VPN 阻擋：只有「bind 失敗 + 有 VPN + 蜂巢式仍被系統驗證可用」才算 ---

    @Test
    fun bindDeniedWithActiveValidatedVpnIsBlocked() {
        assertEquals(
            NetworkHealthStatus.BLOCKED_BY_VPN,
            classify(bindOk = false, vpnActive = true, validated = true, probeOk = false)
        )
    }

    @Test
    fun bindDeniedWithoutVpnIsNetworkDead() {
        assertEquals(
            NetworkHealthStatus.NETWORK_DEAD,
            classify(bindOk = false, vpnActive = false, probeOk = false)
        )
    }

    // --- 回歸：這些以前會被誤判成 BLOCKED_BY_VPN，於是永不自動重建 ---

    /** 蜂巢式已失去 INTERNET capability：與 VPN 無關，就是斷線。 */
    @Test
    fun lostInternetCapabilityIsNetworkDeadEvenWithVpn() {
        assertEquals(
            NetworkHealthStatus.NETWORK_DEAD,
            classify(hasInternet = false, bindOk = false, vpnActive = true)
        )
    }

    /**
     * 蜂巢式連系統驗證都過不了（VALIDATED 為 false）：不管有沒有 VPN，
     * 都該走 NETWORK_DEAD 的自動重建路徑，不能因為「有 VPN」就放棄重建。
     */
    @Test
    fun bindDeniedWithUnvalidatedCellularIsNetworkDeadEvenWithVpn() {
        assertEquals(
            NetworkHealthStatus.NETWORK_DEAD,
            classify(bindOk = false, vpnActive = true, validated = false, probeOk = false)
        )
    }

    /**
     * bind 過得去、但探測收不到位元組 → CELLULAR_STALLED，不是 NETWORK_DEAD。
     *
     * 這一格以前回 NETWORK_DEAD，於是 watchdog 每 45 秒重建一次代理。issue #8
     * 的實機（Galaxy S23 / One UI 9）就是這種：bind 成功、TCP 握手成功、DNS 也
     * 解析得出來，但應用層資料完全回不來 —— 重建引擎修不了，只會砍掉在途連線。
     */
    @Test
    fun bindOkButProbesFailedIsCellularStalled() {
        assertEquals(
            NetworkHealthStatus.CELLULAR_STALLED,
            classify(bindOk = true, vpnActive = true, probeOk = false)
        )
    }

    @Test
    fun bindOkButProbesFailedIsCellularStalledWithoutVpnToo() {
        assertEquals(
            NetworkHealthStatus.CELLULAR_STALLED,
            classify(bindOk = true, vpnActive = false, probeOk = false)
        )
    }

    /** 真正的斷線（連 bind 都不行）仍然要落在 NETWORK_DEAD，不能被新狀態吃掉。 */
    @Test
    fun bindFailureStillIsNetworkDead() {
        assertEquals(
            NetworkHealthStatus.NETWORK_DEAD,
            classify(bindOk = false, vpnActive = false, probeOk = false)
        )
    }
}
