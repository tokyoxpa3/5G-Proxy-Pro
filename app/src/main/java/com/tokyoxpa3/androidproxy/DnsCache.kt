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

    /**
     * 一次查詢的結果。
     *
     * [queried] 表示**這一次呼叫有沒有真的送出 DNS 查詢**。這是診斷唯一需要的資訊：
     * 快取命中（正或負）與 follower 等待都算「沒查」，只有 leader 真的查了。
     *
     * 為什麼不能只看位址清單：空清單可能是「負快取命中」（根本沒發查詢），也可能是
     * 「查了但失敗」，兩者在 issue #8 那種「DNS 到底通不通」的判斷裡意義完全相反。
     */
    data class Lookup(val addresses: List<InetAddress>, val queried: Boolean)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, CountDownLatch>()

    /**
     * 查詢 [key]：快取命中（含負快取）直接回傳；未命中則由 leader 呼叫 [resolve]，
     * 併發的同 key 查詢作為 follower 等待 leader 完成後讀取快取。
     *
     * @param nowMs 目前時間（毫秒）。由呼叫端注入，便於測試控制 TTL。
     * @param resolve 僅 leader 呼叫的實際查詢；回傳解析結果（空清單＝負快取）。
     */
    fun lookup(key: String, nowMs: Long, resolve: () -> List<InetAddress>): List<InetAddress> =
        lookupDetailed(key, nowMs, resolve).addresses

    /**
     * 與 [lookup] 相同，但額外回報「這次有沒有真的送出查詢」。
     *
     * 為什麼需要：issue #8 那台機器「可 bind、可握手、卻收不到位元組」，此時
     * 「DNS 到底有沒有真的查成功」是判斷資料平面是否活著的關鍵證據 —— 但快取
     * 命中會讓呼叫端誤以為剛才有一次成功的查詢。
     */
    fun lookupDetailed(
        key: String,
        nowMs: Long,
        resolve: () -> List<InetAddress>
    ): Lookup {
        val hit = cache[key]
        if (hit != null && hit.expiresAt > nowMs) {
            return Lookup(hit.addresses, queried = false)
        }

        val leaderLatch = CountDownLatch(1)
        val existing = inFlight.putIfAbsent(key, leaderLatch)
        if (existing != null) {
            // follower：等待 leader 完成（有界，避免 leader 卡死時連帶卡住）。
            // 等待後必須重新檢查 TTL——leader 可能已完成但條目在等待期間過期，
            // 直接把過期位址回給呼叫端會讓新連線指向已失效的 IP。
            try { existing.await(followerWaitMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            val followerHit = cache[key]
            return if (followerHit != null && followerHit.expiresAt > nowMs) {
                Lookup(followerHit.addresses, queried = false)
            } else {
                Lookup(emptyList(), queried = false)
            }
        }
        try {
            if (cache.size >= maxEntries) cache.clear()
            val addresses = resolve()
            put(key, nowMs, addresses)
            return Lookup(addresses, queried = true)
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
