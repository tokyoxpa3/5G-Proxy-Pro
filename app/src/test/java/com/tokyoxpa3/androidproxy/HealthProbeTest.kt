package com.tokyoxpa3.androidproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthProbeTest {

    private val gstatic = "http://connectivitycheck.gstatic.com/generate_204"
    private val apple = "http://captive.apple.com/hotspot-detect.html"
    private val miui = "http://connect.rom.miui.com/generate_204"

    // --- 語意驗證：這一組是為了「電信商流量用完的導頁」而寫的 ---

    @Test
    fun empty204IsHealthy() {
        assertTrue(HealthProbe.isHealthy(HealthProbe.Reply(gstatic, code = 204, body = "")))
    }

    /** 導頁常見形態：200 + HTML。舊版讀到一個 byte 就判定健康，這裡必須擋掉。 */
    @Test
    fun html200OnNoContentEndpointIsUnhealthy() {
        assertFalse(
            HealthProbe.isHealthy(
                HealthProbe.Reply(gstatic, code = 200, body = "<html>data cap reached</html>")
            )
        )
    }

    /** 204 卻帶 body：協定上不該發生，當成不可信。 */
    @Test
    fun noisy204IsUnhealthy() {
        assertFalse(HealthProbe.isHealthy(HealthProbe.Reply(gstatic, code = 204, body = "x")))
    }

    @Test
    fun appleSuccessPageIsHealthy() {
        assertTrue(
            HealthProbe.isHealthy(
                HealthProbe.Reply(apple, code = 200, body = "<HTML><HEAD><TITLE>Success</TITLE>")
            )
        )
    }

    @Test
    fun appleLoginPortalIsUnhealthy() {
        assertFalse(
            HealthProbe.isHealthy(
                HealthProbe.Reply(apple, code = 200, body = "<html>Sign in to continue</html>")
            )
        )
    }

    /** 不跟隨 3xx 之後會看到 302 本身，不能當成健康。 */
    @Test
    fun redirectIsUnhealthy() {
        assertFalse(HealthProbe.isHealthy(HealthProbe.Reply(gstatic, code = 302)))
    }

    @Test
    fun transportErrorIsUnhealthy() {
        assertFalse(
            HealthProbe.isHealthy(HealthProbe.Reply(gstatic, error = "SocketTimeoutException"))
        )
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
            "connectivitycheck.gstatic.com=HTTP200; " +
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
