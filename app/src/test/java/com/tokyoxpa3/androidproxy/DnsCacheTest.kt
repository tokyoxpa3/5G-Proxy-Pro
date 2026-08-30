package com.tokyoxpa3.androidproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DnsCacheTest {
    private fun addr(ip: String): InetAddress = InetAddress.getByName(ip)

    @Test
    fun positiveCacheHitWithinTtl() {
        val cache = DnsCache()
        var calls = 0
        val now = 1_000_000L
        cache.lookup("a.com", now) { calls++; listOf(addr("1.2.3.4")) }
        val second = cache.lookup("a.com", now + 1000) { calls++; listOf(addr("5.6.7.8")) }
        assertEquals(1, calls) // 快取命中，不重新查詢
        assertEquals(listOf(addr("1.2.3.4")), second)
    }

    @Test
    fun positiveEntryExpiresAfterTtl() {
        val cache = DnsCache()
        var calls = 0
        val now = 1_000_000L
        cache.lookup("a.com", now) { calls++; listOf(addr("1.2.3.4")) }
        val after = cache.lookup("a.com", now + 5 * 60 * 1000L + 1) { calls++; listOf(addr("5.6.7.8")) }
        assertEquals(2, calls) // 5 分鐘後過期，重新查詢
        assertEquals(listOf(addr("5.6.7.8")), after)
    }

    @Test
    fun negativeCacheShortTtl() {
        val cache = DnsCache()
        var calls = 0
        val now = 1_000_000L
        assertTrue(cache.lookup("nx.com", now) { calls++; emptyList() }.isEmpty())
        // 3 秒內負快取，不再查詢
        assertTrue(cache.lookup("nx.com", now + 2000) { calls++; listOf(addr("1.2.3.4")) }.isEmpty())
        assertEquals(1, calls)
        // 超過 3 秒，重新查詢
        val third = cache.lookup("nx.com", now + 3001) { calls++; listOf(addr("1.2.3.4")) }
        assertEquals(2, calls)
        assertEquals(listOf(addr("1.2.3.4")), third)
    }

    @Test
    fun singleFlightDedupsConcurrentLookups() {
        val cache = DnsCache()
        var calls = 0
        val now = 1_000_000L
        val resolveStarted = CountDownLatch(1)
        val releaseResolve = CountDownLatch(1)
        val bothEntered = CountDownLatch(2)
        val results = ConcurrentHashMap<String, List<InetAddress>>()

        val resolve: () -> List<InetAddress> = {
            calls++
            resolveStarted.countDown()
            releaseResolve.await(5, TimeUnit.SECONDS)
            listOf(addr("1.2.3.4"))
        }

        val threads = listOf("t1", "t2").map { name ->
            Thread {
                bothEntered.countDown()
                bothEntered.await()
                results[name] = cache.lookup("a.com", now, resolve)
            }.also { it.start() }
        }

        // 等 leader 真正進入 resolve（calls==1）後再放行
        assertTrue(resolveStarted.await(3, TimeUnit.SECONDS))
        releaseResolve.countDown()
        threads.forEach { it.join(5000) }

        assertEquals(1, calls) // 併發同 host 只查一次
        assertEquals(listOf(addr("1.2.3.4")), results["t1"])
        assertEquals(listOf(addr("1.2.3.4")), results["t2"])
    }

    @Test
    fun followerReturnsEmptyWhenLeaderStalls() {
        // 縮短 follower 等待時間，測試才不會拖 3 秒
        val cache = DnsCache(followerWaitMs = 200)
        val now = 1_000_000L
        val neverResolve = CountDownLatch(1)
        val leaderDone = CountDownLatch(1)
        val leader = Thread {
            cache.lookup("slow.com", now) { neverResolve.await(5, TimeUnit.SECONDS); emptyList() }
            leaderDone.countDown()
        }
        leader.start()
        Thread.sleep(50) // 確保 leader 已成為 in-flight
        val followerResult = cache.lookup("slow.com", now) { listOf(addr("1.2.3.4")) }
        assertTrue(followerResult.isEmpty()) // follower 等 200ms 超時回空
        neverResolve.countDown()
        leader.join(5000)
    }
}
