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
}
