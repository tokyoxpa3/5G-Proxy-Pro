package com.tokyoxpa3.androidproxy

/**
 * 健康檢查的回應判讀與端點排序（純函式，可用 JVM 單元測試）。
 *
 * ## 為什麼要驗語意
 *
 * 舊版只看 `inputStream.read()` 有沒有讀到一個 byte。問題是電信商流量用完之後的
 * **導頁（cap redirect）** 會回 200 + HTML，讀得到 byte，於是判定「健康」—— 但其實
 * 完全不能用。而「流量被計費限流／用完」正是本 App 使用者的典型處境，所以這不是
 * 理論問題，是我們目標客群最可能遇到的那一種。
 *
 * 同理，`HttpURLConnection` 預設會自動跟隨 3xx，於是「302 導到登入頁 → 200」也會
 * 被當成健康。所以呼叫端要關掉 `instanceFollowRedirects`，這裡再驗 status code：
 *
 * - `generate_204` 這類端點：必須 **204 且沒有 body**。
 * - `captive.apple.com/hotspot-detect.html`：必須 **200 且 body 含 "Success"**。
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
     * 避免被導頁的整頁 HTML 拖慢。
     */
    data class Reply(
        val url: String,
        val code: Int? = null,
        val body: String? = null,
        val error: String? = null,
        val elapsedMs: Long = 0L
    )

    /** 這個 URL 是否應該回「204 + 空 body」。未知端點一律用最嚴格的標準。 */
    fun expectsNoContent(url: String): Boolean = !url.contains("captive.apple.com")

    /** 這個回應能不能證明「資料平面是活的」。 */
    fun isHealthy(reply: Reply): Boolean {
        if (reply.error != null) return false
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
                isHealthy(reply) -> "$host=ok ${reply.elapsedMs}ms"
                reply.error != null -> "$host=${reply.error}"
                else -> "$host=HTTP${reply.code}"
            }
        }.joinToString("; ")
    }
}
