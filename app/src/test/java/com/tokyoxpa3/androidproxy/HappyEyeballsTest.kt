package com.tokyoxpa3.androidproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HappyEyeballsTest {
    private fun <T> ref(v: T?): AtomicReference<T> = AtomicReference(v)
    private fun flag(b: Boolean) = AtomicBoolean(b)
    private fun latch() = CountDownLatch(1)

    @Test
    fun winnerCountsDownAndIsNotClosed() {
        val winner = ref<String>(null)
        val l = latch()
        val closed = ArrayList<String>()
        HappyEyeballs.attempt(winner, flag(false), l,
            connect = { "cand-1" },
            close = { closed.add(it) })
        assertEquals("cand-1", winner.get())
        assertEquals(0, l.count) // latch 已 countDown
        assertTrue(closed.isEmpty()) // 勝者不關閉
    }

    @Test
    fun preCheckSkipsConnectWhenAlreadyWonOrAbandoned() {
        val winner = ref<String>("already-won")
        var connectCalled = false
        HappyEyeballs.attempt(winner, flag(false), latch(),
            connect = { connectCalled = true; "x" },
            close = {})
        assertFalse(connectCalled) // 已有勝者 → 根本不連

        connectCalled = false
        HappyEyeballs.attempt(ref(null), flag(true), latch(),
            connect = { connectCalled = true; "x" },
            close = {})
        assertFalse(connectCalled) // 已放棄 → 根本不連
    }

    @Test
    fun lateWinnerSelfClosesAndDoesNotCountDown() {
        // 核心競態：主執行緒放棄（abandoned=true）後，先前已開始的連線才連上並
        // 贏得 CAS —— 它必須自行關閉、讓出勝者位、不 countDown，否則成為洩漏 socket。
        val winner = ref<String>(null)
        val abandoned = flag(false)
        val l = latch()
        val closed = ArrayList<String>()

        val connectStarted = CountDownLatch(1)
        val releaseConnect = CountDownLatch(1)
        val completed = CountDownLatch(1)

        Thread {
            HappyEyeballs.attempt(winner, abandoned, l,
                connect = {
                    connectStarted.countDown()
                    releaseConnect.await(5, TimeUnit.SECONDS)
                    "late-cand"
                },
                close = { closed.add(it) })
            completed.countDown()
        }.start()

        assertTrue(connectStarted.await(3, TimeUnit.SECONDS))
        abandoned.set(true) // 模擬主執行緒在 connect 進行中放棄
        releaseConnect.countDown()
        assertTrue(completed.await(3, TimeUnit.SECONDS))

        assertNull(winner.get()) // 讓出勝者位
        assertEquals(listOf("late-cand"), closed) // 自行關閉
        assertEquals(1, l.count) // 未 countDown
    }

    @Test
    fun connectFailureReturnsNullLeavesNoWinner() {
        val winner = ref<String>(null)
        val l = latch()
        HappyEyeballs.attempt(winner, flag(false), l,
            connect = { null },
            close = {})
        assertNull(winner.get())
        assertEquals(1, l.count) // 未 countDown
    }

    @Test
    fun orderIpv6FirstPutsIpv6BeforeIpv4Stably() {
        val input = listOf("v4-a", "v6-a", "v4-b", "v6-b")
        val out = HappyEyeballs.orderIpv6First(input) { it.startsWith("v6") }
        assertEquals(listOf("v6-a", "v6-b", "v4-a", "v4-b"), out)
    }

    @Test
    fun raceStaggersDeferredAndSkipsWhenAlreadyWon() {
        // 首選家族（IPv6）立即連線並勝出；延後家族（IPv4）排入 scheduler（延遲=staggerMs），
        // 到點觸發時因已有勝者，attempt 前置檢查直接 no-op，不再對 IPv4 連線。
        val winner = ref<String>(null)
        val l = latch()
        val connected = ArrayList<String>()
        val delayed = ArrayList<() -> Unit>()
        val delayMs = java.util.concurrent.atomic.AtomicReference<Long?>()

        HappyEyeballs.race(
            first = listOf("v6-a"),
            deferred = listOf("v4-a"),
            staggerMs = 250L,
            winner = winner, abandoned = flag(false), latch = l,
            dispatch = { it() }, // 同步執行，模擬立即提交
            scheduler = { ms, run -> delayMs.set(ms); delayed.add(run) },
            connect = { addr -> connected.add(addr); if (addr == "v6-a") "win" else null },
            close = {}
        )

        assertEquals(listOf("v6-a"), connected) // 首選家族立即連線並勝出
        assertEquals(250L, delayMs.get())        // 延後家族以 250ms 排入
        assertEquals("win", winner.get())
        assertEquals(0, l.count)                 // 勝者已 countDown

        delayed[0].invoke()                       // 延後時間到，觸發 IPv4
        assertEquals(listOf("v6-a"), connected)   // 已有人勝出 → v4-a 未連線
    }

    @Test
    fun raceConnectsDeferredWhenFirstFamilyHasNoWinner() {
        // 首選家族全部嘗試但無人勝出；延後家族到點後接手並勝出。
        val winner = ref<String>(null)
        val l = latch()
        val connected = ArrayList<String>()
        val delayed = ArrayList<() -> Unit>()

        HappyEyeballs.race(
            first = listOf("v6-a"),
            deferred = listOf("v4-a"),
            staggerMs = 250L,
            winner = winner, abandoned = flag(false), latch = l,
            dispatch = { it() },
            scheduler = { _, run -> delayed.add(run) },
            connect = { addr -> connected.add(addr); if (addr == "v4-a") "win" else null },
            close = {}
        )

        assertEquals(listOf("v6-a"), connected) // v6-a 嘗試但未勝出
        assertNull(winner.get())

        delayed[0].invoke()                       // IPv4 接手
        assertEquals(listOf("v6-a", "v4-a"), connected)
        assertEquals("win", winner.get())
        assertEquals(0, l.count)
    }
}
