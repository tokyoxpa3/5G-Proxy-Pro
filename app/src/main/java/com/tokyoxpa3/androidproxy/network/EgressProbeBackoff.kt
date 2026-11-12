package com.tokyoxpa3.androidproxy.network

/**
 * 出口探測的退避策略（純函式，可用 JVM 單元測試）。
 *
 * 為什麼要退避：探測失敗代表「看不到出口位址」。這個狀態下更密集地重試不會讓它
 * 恢復，只是白白消耗行動數據與電量 —— issue #8 的機器長期 `Cellular IP: Failed`，
 * 每 30 秒仍要打兩個 HTTPS 請求，一天數千次全是白工。
 *
 * 為什麼第一次失敗不退避：換 IP 的當下 echo 幾乎必然失敗一次（舊連線還在用舊出口），
 * 那正是最需要密集重試確認的時候。所以第 1 次維持原間隔，第 2 次才開始拉長。
 *
 * 恢復不需要「解除退避」的路徑：成功一次就把失敗計數歸零，間隔自然回到原值。
 */
object EgressProbeBackoff {

    /**
     * 退避上限：5 分鐘。
     *
     * 與既有的 echo 校正週期一致 —— 反正恢復時第一件事就是校正一次，
     * 再長下去只會讓「出口變了」被發現得更晚，省不到多少流量。
     */
    const val DEFAULT_MAX_MS = 5 * 60 * 1000L

    /**
     * 位移上限：失敗再多次也只乘到 32 倍，避免位移量失控。
     *
     * 取 5 而不是更小，是為了讓 [DEFAULT_MAX_MS] 真的會被觸發到：以 15 秒為基底，
     * 乘到 32 倍是 480 秒，封頂後才是 300 秒。若上限取 4，最大只到 240 秒，
     * 封頂那段就永遠走不到，等於白寫。
     */
    private const val MAX_SHIFT = 5

    /**
     * 依連續失敗次數回傳這次該等多久。
     *
     * 以 15 秒為基底：15 → 30 → 60 → 120 → 240 → 300（封頂）。
     *
     * @param baseMs 正常節奏下的間隔（閒置 30 秒 / 有連線 15 秒）
     * @param consecutiveFailures 連續失敗次數（0 或 1 代表不退避）
     */
    fun interval(
        baseMs: Long,
        consecutiveFailures: Int,
        maxMs: Long = DEFAULT_MAX_MS
    ): Long {
        if (consecutiveFailures <= 1) return baseMs
        val shift = (consecutiveFailures - 1).coerceAtMost(MAX_SHIFT)
        return (baseMs shl shift).coerceAtMost(maxMs)
    }
}
