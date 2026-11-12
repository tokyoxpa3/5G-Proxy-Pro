package com.tokyoxpa3.androidproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthProbeTest {

    private val gstatic = "http://connectivitycheck.gstatic.com/generate_204"
    private val apple = "http://captive.apple.com/hotspot-detect.html"
    private val miui = "http://connect.rom.miui.com/generate_204"

    // --- 健康判定：只要收到 HTTP 回應就算通 ---
    //
    // 這一組是刻意的設計決策，不是漏驗：健康檢查唯一的用途是決定要不要重建代理，
    // 而「有回應」就證明資料平面活著。導頁、302、204 帶 body 都代表位元組雙向流動，
    // 重建對它們毫無幫助。真正該判失敗的是「一個位元組都沒回來」。

    @Test
    fun empty204IsReachable() {
        val reply = HealthProbe.Reply(gstatic, code = 204, body = "")
        assertTrue(HealthProbe.isReachable(reply))
        assertTrue(HealthProbe.matchesExpected(reply))
    }

    /** 電信商流量用完的導頁：200 + HTML。通，但語意不符。 */
    @Test
    fun carrierPortalStillCountsAsReachable() {
        val reply = HealthProbe.Reply(gstatic, code = 200, body = "<html>data cap reached</html>")
        assertTrue(HealthProbe.isReachable(reply))
        assertFalse(HealthProbe.matchesExpected(reply))
    }

    /** 204 卻帶 body：協議異常，但位元組確實流動了。 */
    @Test
    fun noisy204IsReachableButUnexpected() {
        val reply = HealthProbe.Reply(gstatic, code = 204, body = "x")
        assertTrue(HealthProbe.isReachable(reply))
        assertFalse(HealthProbe.matchesExpected(reply))
    }

    /** 不跟隨 3xx 之後看到的是 302 本身。導頁也是通。 */
    @Test
    fun redirectIsReachableButUnexpected() {
        val reply = HealthProbe.Reply(gstatic, code = 302)
        assertTrue(HealthProbe.isReachable(reply))
        assertFalse(HealthProbe.matchesExpected(reply))
    }

    @Test
    fun appleSuccessPageMatches() {
        val reply = HealthProbe.Reply(
            apple, code = 200, body = "<HTML><HEAD><TITLE>Success</TITLE>"
        )
        assertTrue(HealthProbe.isReachable(reply))
        assertTrue(HealthProbe.matchesExpected(reply))
    }

    @Test
    fun appleLoginPortalIsReachableButUnexpected() {
        val reply = HealthProbe.Reply(apple, code = 200, body = "<html>Sign in to continue</html>")
        assertTrue(HealthProbe.isReachable(reply))
        assertFalse(HealthProbe.matchesExpected(reply))
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

    @Test
    fun expectsNoContentOnlyForApple() {
        assertFalse(HealthProbe.expectsNoContent(apple))
        assertTrue(HealthProbe.expectsNoContent(gstatic))
        assertTrue(HealthProbe.expectsNoContent(miui))
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

    // --- 摘要：固定順序，跨回報可比對 ---

    @Test
    fun summaryFollowsEndpointOrderNotCompletionOrder() {
        val order = listOf(gstatic, apple, miui)
        // 故意用「完成順序」顛倒的輸入
        val replies = listOf(
            HealthProbe.Reply(miui, code = 204, body = "", elapsedMs = 447),
            HealthProbe.Reply(apple, error = "SocketTimeoutException", elapsedMs = 3000),
            HealthProbe.Reply(gstatic, code = 200, body = "<html>", elapsedMs = 80)
        )
        assertEquals(
            "connectivitycheck.gstatic.com=HTTP200(unexpected); " +
                "captive.apple.com=SocketTimeoutException; " +
                "connect.rom.miui.com=ok 447ms",
            HealthProbe.summarize(replies, order)
        )
    }

    @Test
    fun summarySkipsEndpointsNeverAttempted() {
        val order = listOf(gstatic, apple, miui)
        val replies = listOf(HealthProbe.Reply(gstatic, code = 204, body = "", elapsedMs = 78))
        assertEquals("connectivitycheck.gstatic.com=ok 78ms", HealthProbe.summarize(replies, order))
    }
}
