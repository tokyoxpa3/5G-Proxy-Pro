package com.tokyoxpa3.androidproxy

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Happy Eyeballs 的連線競賽決策（純邏輯，抽離 Android/網路依賴，可單元測試）。
 *
 * 多個位址並行連線，最先成功者勝出；其餘（含「主執行緒已放棄後才連上的遲到勝者」）
 * 必須自行回收，否則成為無人持有的洩漏 socket（電信商 IPv4 黑洞期間每次逾時都會
 * 製造一個，長時間運行下 FDSize 衝向 16384 的元兇之一）。
 *
 * @param connect 建立連線。成功回傳候選（交由本函式判定勝負）；失敗/放棄回傳 null。
 * @param close   回收候選（競輸、或遲到勝者收回時呼叫）。
 */
object HappyEyeballs {
    /** RFC 8305「Connection Attempt Delay」：首選家族（IPv6）先行，次要家族（IPv4）
     *  延後啟動，使「先試 IPv6」不會因 IPv6 長時間無回應而卡住 fallback。 */
    const val DEFAULT_CONNECTION_ATTEMPT_DELAY_MS = 250L

    fun <T : Any> attempt(
        winner: AtomicReference<T>,
        abandoned: AtomicBoolean,
        latch: CountDownLatch,
        connect: () -> T?,
        close: (T) -> Unit,
    ) {
        if (winner.get() != null || abandoned.get()) return
        val candidate = connect() ?: return
        if (winner.compareAndSet(null, candidate)) {
            if (abandoned.get()) {
                // 主執行緒已放棄：自行關閉並讓出勝者位（不 countDown，不留下孤兒）
                close(candidate)
                winner.compareAndSet(candidate, null)
                return
            }
            latch.countDown()
        } else {
            close(candidate) // 已有人勝出，本候選競輸
        }
    }

    /**
     * RFC 8305 位址排序：IPv6 優先、IPv4 其次（同族內保持原始相對順序）。
     * 連線競賽時首選家族先行、次要家族延後，正常情況下較快的首選家族勝出。
     */
    fun <T> orderIpv6First(addresses: List<T>, isIpv6: (T) -> Boolean): List<T> =
        addresses.sortedBy { if (isIpv6(it)) 0 else 1 }

    /**
     * 錯開（stagger）連線競賽：`first` 家族立即全數提交，`deferred` 家族經
     * [scheduler] 延後 [staggerMs] 再提交。首選家族若很快成功，延後家族仍會依
     * [attempt] 的前置檢查自動 no-op；首選家族若黑洞/無回應，延後家族在延後
     * 時間點後接手，避免延遲從「單次逾時」疊加成「雙家族總和」。
     *
     * @param dispatch  把一次連線嘗試提交到有界執行緒池（實作層注入；測試可同步執行）。
     * @param scheduler 抽象化延後啟動（實作層用 scheduled executor；測試可擷取不執行）。
     */
    fun <A, C : Any> race(
        first: List<A>,
        deferred: List<A>,
        staggerMs: Long,
        winner: AtomicReference<C>,
        abandoned: AtomicBoolean,
        latch: CountDownLatch,
        dispatch: (() -> Unit) -> Unit,
        scheduler: (Long, () -> Unit) -> Unit,
        connect: (A) -> C?,
        close: (C) -> Unit,
    ) {
        fun launch(a: A) {
            dispatch {
                attempt(winner, abandoned, latch,
                    connect = { connect(a) },
                    close = close)
            }
        }
        first.forEach { launch(it) }
        if (deferred.isNotEmpty()) {
            scheduler(staggerMs) { deferred.forEach { launch(it) } }
        }
    }
}
