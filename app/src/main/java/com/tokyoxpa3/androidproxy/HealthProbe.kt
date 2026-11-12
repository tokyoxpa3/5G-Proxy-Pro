package com.tokyoxpa3.androidproxy

/**
 * 健康檢查的回應判讀與端點排序（純函式，可用 JVM 單元測試）。
 *
 * ## 健康判定：只要「收到 HTTP 回應」就算通
 *
 * 這個檢查唯一的用途是決定**要不要重建代理**。而「收到回應」本身就證明請求出去了、
 * 回應回來了 —— 資料平面是活的。至於那個回應是不是 204，對「要不要重建」沒有影響：
 *
 * - **302 導登入頁**、**電信商流量用完的 200 + HTML**、**204 卻帶 body**：都是雙向
 *   都有位元組。重建代理對導頁毫無幫助，判成不健康只會白白重建兩次、再發一則
 *   「沒有應用層資料回流」的通知 —— 而那則通知的描述是錯的。
 * - 這也跟 watchdog 的主判據一致：`trafficFlowing`（有位元組在進來）就算健康，
 *   備援路徑不該用更嚴格的標準。
 *
 * 真正該判成不健康的是**一個位元組都沒回來**：連線逾時、TLS 卡住、被黑洞。
 * 那正是 issue #8 的形態（TCP 握手成功、payload 不回來）。
 *
 * ## 語意判斷只為了「看得懂」
 *
 * [matchesExpected] 不參與任何決策，只用來讓 `HealthProbe:` 那行寫出
 * `HTTP200(unexpected)` 這種標記 —— 回報裡看到它就知道是電信商導頁或中間設備，
 * 不用再猜。這比「判定為失敗」有用得多，因為它保留資訊而不改變行為。
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
     * [code] 為 null 代表連線層就失敗（[error] 有值）；[body] 只保留前 512 bytes，
     * 只用來產生診斷標記，不參與判定。
     */
    data class Reply(
        val url: String,
        val code: Int? = null,
        val body: String? = null,
        val error: String? = null,
        val elapsedMs: Long = 0L
    )

    /** 這個 URL 預期回「204 + 空 body」。未知端點一律用最嚴格的標準。 */
    fun expectsNoContent(url: String): Boolean = !url.contains("captive.apple.com")

    /**
     * 能不能證明資料平面是活的：**收到任何 HTTP 回應就算**。
     *
     * 刻意不驗 status code —— 見檔頭說明。`code != null` 代表 `responseCode` 有值，
     * 也就是請求確實送達某個會回應的端點；連線逾時／TLS 卡住會拋例外，落進 [Reply.error]。
     */
    fun isReachable(reply: Reply): Boolean = reply.error == null && reply.code != null

    /**
     * 回應語意是否符合端點預期。**只用於診斷標記，不參與健康判定。**
     *
     * 不符預期通常意味著中間有東西：電信商導頁、captive portal、透明代理。
     */
    fun matchesExpected(reply: Reply): Boolean {
        val code = reply.code ?: return false
        val body = reply.body ?: ""
        return if (expectsNoContent(reply.url)) {
            code == 204 && body.isBlank()
        } else {
            code == 200 && body.contains("Success")
        }
    }

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
     * 刻意依 [order] 而非完成順序輸出：否則同一種故障每次的字串都不同，
     * 跨回報比對就失去意義。
     */
    fun summarize(replies: List<Reply>, order: List<String>): String {
        val byUrl = replies.associateBy { it.url }
        return order.mapNotNull { url ->
            val reply = byUrl[url] ?: return@mapNotNull null
            val host = url.substringAfter("//").substringBefore("/")
            when {
                reply.error != null -> "$host=${reply.error}"
                !isReachable(reply) -> "$host=no-response"
                !matchesExpected(reply) -> "$host=HTTP${reply.code}(unexpected)"
                else -> "$host=ok ${reply.elapsedMs}ms"
            }
        }.joinToString("; ")
    }
}
