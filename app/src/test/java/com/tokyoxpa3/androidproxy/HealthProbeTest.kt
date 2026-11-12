package com.tokyoxpa3.androidproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthProbeTest {

    private val gstatic = "http://connectivitycheck.gstatic.com/generate_204"
    private val apple = "http://captive.apple.com/hotspot-detect.html"
    private val miui = "http://connect.rom.miui.com/generate_204"

    // --- 健康判定：只問「有沒有收到 HTTP 回應」 ---
    //
    // 這一組是刻意的設計決策，不是漏驗：健康檢查唯一的用途是決定要不要重建代理，
    // 而「有回應」就證明資料平面活著。導頁、302、5xx 都代表位元組雙向流動，
    // 重建對它們毫無幫助。真正該判失敗的是「一個位元組都沒回來」。

    @Test
    fun empty204IsReachable() {
        assertTrue(HealthProbe.isReachable(HealthProbe.Reply(gstatic, code = 204)))
    }

    /** 電信商流量用完的導頁：200 + HTML。一樣算通。 */
    @Test
    fun carrierPortalIsReachable() {
        assertTrue(HealthProbe.isReachable(HealthProbe.Reply(gstatic, code = 200)))
    }

    /** 不跟隨 3xx 之後看到的是 302 本身。導頁也是通。 */
    @Test
    fun redirectIsReachable() {
        assertTrue(HealthProbe.isReachable(HealthProbe.Reply(gstatic, code = 302)))
    }

    /** 端點自己壞了（5xx），但網路是通的 —— 一樣不該重建代理。 */
    @Test
    fun serverErrorIsStillReachable() {
        assertTrue(HealthProbe.isReachable(HealthProbe.Reply(gstatic, code = 503)))
    }

    /** 這才是 issue #8 的形態：連線起來了，但一個位元組都沒回來。 */
    @Test
    fun transportErrorIsNotReachable() {
        assertFalse(
            HealthProbe.isReachable(HealthProbe.Reply(gstatic, error = "SocketTimeoutException"))
        )
    }

    @Test
    fun missingResponseCodeIsNotReachable() {
        assertFalse(HealthProbe.isReachable(HealthProbe.Reply(gstatic)))
    }

    // --- 排序：上次成功的端點排最前面 ---

    @Test
    fun orderPutsLastSuccessFirst() {
        val urls = listOf(gstatic, apple, miui)
        assertEquals(listOf(apple, gstatic, miui), HealthProbe.order(urls, apple))
    }

    @Test
    fun orderKeepsListWhenNoHistory() {
        val urls = listOf(gstatic, apple, miui)
        assertEquals(urls, HealthProbe.order(urls, null))
    }

    /** 端點清單改版後，舊記錄不該製造一個不存在的位置。 */
    @Test
    fun orderIgnoresUnknownLastSuccess() {
        val urls = listOf(gstatic, apple, miui)
        assertEquals(urls, HealthProbe.order(urls, "http://gone.example/generate_204"))
    }

    @Test
    fun orderNeverDropsOrDuplicates() {
        val urls = listOf(gstatic, apple, miui)
        val ordered = HealthProbe.order(urls, miui)
        assertEquals(urls.size, ordered.size)
        assertEquals(urls.toSet(), ordered.toSet())
        assertEquals(miui, ordered.first())
    }

    // --- 摘要：只陳述事實，固定順序 ---

    @Test
    fun summaryFollowsEndpointOrderNotCompletionOrder() {
        val order = listOf(gstatic, apple, miui)
        // 故意用「完成順序」顛倒的輸入
        val replies = listOf(
            HealthProbe.Reply(miui, code = 204, elapsedMs = 447),
            HealthProbe.Reply(apple, error = "SocketTimeoutException", elapsedMs = 3000),
            HealthProbe.Reply(gstatic, code = 200, elapsedMs = 80)
        )
        assertEquals(
            "connectivitycheck.gstatic.com=ok 80ms (200); " +
                "captive.apple.com=SocketTimeoutException; " +
                "connect.rom.miui.com=ok 447ms (204)",
            HealthProbe.summarize(replies, order)
        )
    }

    @Test
    fun summarySkipsEndpointsNeverAttempted() {
        val order = listOf(gstatic, apple, miui)
        val replies = listOf(HealthProbe.Reply(gstatic, code = 204, elapsedMs = 78))
        assertEquals(
            "connectivitycheck.gstatic.com=ok 78ms (204)",
            HealthProbe.summarize(replies, order)
        )
    }
}
