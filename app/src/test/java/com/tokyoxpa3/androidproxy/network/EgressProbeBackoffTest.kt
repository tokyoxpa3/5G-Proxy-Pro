package com.tokyoxpa3.androidproxy.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [EgressProbeBackoff] 的迴歸測試。
 *
 * 這裡錯的代價不對稱：
 *  - 退避太慢 → 死路上照樣每 30 秒打兩個 HTTPS 請求，一天數千次白工。
 *  - 退避太快（第 1 次就退）→ 換 IP 當下正是最需要密集確認的時候，卻被拉長間隔。
 *  - 忘記封頂 → 失敗次數一多，間隔會被位移到好幾小時，出口變更幾乎不會被發現。
 */
class EgressProbeBackoffTest {

    private val active = 15_000L   // 有活躍連線時的基底間隔
    private val idle = 30_000L     // 閒置時的基底間隔

    @Test
    fun noFailureKeepsBaseInterval() {
        assertEquals(active, EgressProbeBackoff.interval(active, 0))
        assertEquals(idle, EgressProbeBackoff.interval(idle, 0))
    }

    @Test
    fun firstFailureDoesNotBackOff() {
        // 換 IP 當下 echo 幾乎必然失敗一次，那時要維持密集重試
        assertEquals(active, EgressProbeBackoff.interval(active, 1))
    }

    @Test
    fun backoffDoublesFromSecondFailure() {
        assertEquals(30_000L, EgressProbeBackoff.interval(active, 2))
        assertEquals(60_000L, EgressProbeBackoff.interval(active, 3))
        assertEquals(120_000L, EgressProbeBackoff.interval(active, 4))
        assertEquals(240_000L, EgressProbeBackoff.interval(active, 5))
    }

    @Test
    fun backoffIsCappedAtMax() {
        assertEquals(EgressProbeBackoff.DEFAULT_MAX_MS, EgressProbeBackoff.interval(active, 6))
        // 位移量必須有上限，否則失敗次數一多會溢成荒謬的間隔
        assertEquals(EgressProbeBackoff.DEFAULT_MAX_MS, EgressProbeBackoff.interval(active, 1_000))
        assertEquals(EgressProbeBackoff.DEFAULT_MAX_MS, EgressProbeBackoff.interval(idle, 1_000))
    }

    @Test
    fun idleBaseBacksOffToTheSameCap() {
        assertEquals(60_000L, EgressProbeBackoff.interval(idle, 2))
        assertEquals(120_000L, EgressProbeBackoff.interval(idle, 3))
        assertEquals(EgressProbeBackoff.DEFAULT_MAX_MS, EgressProbeBackoff.interval(idle, 5))
    }

    @Test
    fun customMaxIsHonoured() {
        assertEquals(45_000L, EgressProbeBackoff.interval(active, 10, maxMs = 45_000L))
    }
}
