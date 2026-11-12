package com.tokyoxpa3.androidproxy

/**
 * 健康檢查的回應判讀與端點排序（純函式，可用 JVM 單元測試）。
 *
 * ## 只問一個問題：有沒有收到回應
 *
 * 這個檢查唯一的用途是決定**要不要重建代理**。所以判據只有一條：
 *
 * > 有沒有收到任何 HTTP 回應？
 *
 * 有 → 資料平面是活的，不需要動它。沒有 → 一個位元組都沒回來，重建才有意義。
 *
 * 刻意**不去分類**回應的內容。302 導登入頁、電信商流量用完的 200 + HTML、
 * 204 卻帶 body —— 這些都證明請求出去了、回應回來了，而重建代理對它們
 * **一個都修不了**。把它們判成失敗只會白重建兩次，再發一則與實情相反的通知。
 * 這也跟 watchdog 的主判據一致：`trafficFlowing`（有位元組在進來）就算健康。
 *
 * 真正該判失敗的是「一個位元組都沒回來」：連線逾時、TLS 卡住、被黑洞。
 * 那正是 issue #8 的形態（TCP 握手成功、payload 不回來）。
 *
 * ## 為什麼要有排序
 *
 * 端點清單本身就是「有些地區連不上」的備援設計（gstatic 在部分地區被封鎖）。
 * 但固定順序意味著：只要第一個端點不通，**每一輪**都要先吃滿一次 timeout。
 * 記住上次成功的那一個並排到最前面，穩態下就只發 1 個請求。
 */
object HealthProbe {

    /**
     * 一次端點嘗試的結果。
     *
     * [code] 為 null 代表連線層就失敗（[error] 有值）；兩者必有一個。
     * 刻意不保留 body —— 我們不需要看內容，讀它只是白白消耗流量。
     */
    data class Reply(
        val url: String,
        val code: Int? = null,
        val error: String? = null,
        val elapsedMs: Long = 0L
    )

    /**
     * 能不能證明資料平面是活的：**收到任何 HTTP 回應就算**。
     *
     * `code != null` 代表 `responseCode` 有值，也就是請求確實送達某個會回應的端點。
     * 連線逾時、TLS 卡住、被黑洞都會拋例外，落進 [Reply.error]。
     */
    fun isReachable(reply: Reply): Boolean = reply.error == null && reply.code != null

    /**
     * 上次成功的端點排最前面，其餘保持原本順序。
     *
     * [lastSuccess] 不在清單裡（例如改版刪掉端點）時原樣回傳，不要製造不存在的位置。
     */
    fun order(urls: List<String>, lastSuccess: String?): List<String> {
        if (lastSuccess == null || lastSuccess !in urls) return urls
        return listOf(lastSuccess) + urls.filter { it != lastSuccess }
    }

    /**
     * 產生固定順序的摘要字串，供 `HealthProbe:` 診斷行使用。
     *
     * 只陳述事實（有沒有回應、狀態碼、耗時），不做「符不符合預期」的推論 ——
     * 狀態碼本身已經足夠讓看的人自己判斷。刻意依 [order] 而非完成順序輸出，
     * 否則同一種故障每次的字串都不同，跨回報比對就失去意義。
     */
    fun summarize(replies: List<Reply>, order: List<String>): String {
        val byUrl = replies.associateBy { it.url }
        return order.mapNotNull { url ->
            val reply = byUrl[url] ?: return@mapNotNull null
            val host = url.substringAfter("//").substringBefore("/")
            when {
                reply.error != null -> "$host=${reply.error}"
                reply.code == null -> "$host=no-response"
                else -> "$host=ok ${reply.elapsedMs}ms (${reply.code})"
            }
        }.joinToString("; ")
    }
}
