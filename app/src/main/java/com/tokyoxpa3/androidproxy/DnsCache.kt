package com.tokyoxpa3.androidproxy

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DNS 快取 + single-flight 的純協調邏輯（抽離 Android 依賴，可單元測試）。
 *
 * 職責：正/負結果的 TTL 快取、併發查詢同一 host 只查一次（single-flight）、
 * 快取過大時清空。實際的網路查詢由服務層以 [lookup] 的 resolve 回呼注入。
 */
class DnsCache(
    private val maxEntries: Int = 256,
    private val positiveTtlMs: Long = 5 * 60 * 1000L,
    private val negativeTtlMs: Long = 3 * 1000L,
    private val followerWaitMs: Long = 3000L,
) {
    private class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, CountDownLatch>()

    /**
     * 查詢 [key]：快取命中（含負快取）直接回傳；未命中則由 leader 呼叫 [resolve]，
     * 併發的同 key 查詢作為 follower 等待 leader 完成後讀取快取。
     *
     * @param nowMs 目前時間（毫秒）。由呼叫端注入，便於測試控制 TTL。
     * @param resolve 僅 leader 呼叫的實際查詢；回傳解析結果（空清單＝負快取）。
     */
    fun lookup(key: String, nowMs: Long, resolve: () -> List<InetAddress>): List<InetAddress> {
        val hit = cache[key]
        if (hit != null && hit.expiresAt > nowMs) return hit.addresses

        val leaderLatch = CountDownLatch(1)
        val existing = inFlight.putIfAbsent(key, leaderLatch)
        if (existing != null) {
            // follower：等待 leader 完成（有界，避免 leader 卡死時連帶卡住）。
            // 等待後必須重新檢查 TTL——leader 可能已完成但條目在等待期間過期，
            // 直接把過期位址回給呼叫端會讓新連線指向已失效的 IP。
            try { existing.await(followerWaitMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            val followerHit = cache[key]
            return if (followerHit != null && followerHit.expiresAt > nowMs) followerHit.addresses else emptyList()
        }
        try {
            if (cache.size >= maxEntries) cache.clear()
            val addresses = resolve()
            return put(key, nowMs, addresses)
        } finally {
            inFlight.remove(key, leaderLatch)
            leaderLatch.countDown()
        }
    }

    /** 清空快取（服務停止/重建時呼叫）；進行中的查詢不受影響，由 leader 的 finally 自然收尾。 */
    fun clear() {
        cache.clear()
        // 一併清 in-flight 標記：否則重建後若舊 leader 尚未收尾，新查詢會被誤當
        // follower 而等待一個已無意義的 leader。
        inFlight.clear()
    }

    private fun put(key: String, nowMs: Long, addresses: List<InetAddress>): List<InetAddress> {
        cache[key] = if (addresses.isNotEmpty()) {
            Entry(addresses, nowMs + positiveTtlMs)
        } else {
            Entry(emptyList(), nowMs + negativeTtlMs)
        }
        return addresses
    }
}
