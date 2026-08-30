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
}
