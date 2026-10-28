package com.tokyoxpa3.androidproxy.relay

/**
 * 控制 API 的極簡 HTTP/1.1 請求剖析。
 * 只看 request line 與 header（不讀 body），足夠 `curl -X POST .../rotate` 使用。
 *
 * 抽成純 JVM 物件（零 Android 依賴），供單元測試鎖住邊界行為。
 */
object ControlHttp {

    data class Request(val method: String, val path: String, val headers: Map<String, String>)

    /** lines：request line 起、空行前的所有行（不含 CRLF）。格式錯誤回 null。 */
    fun parseHead(lines: List<String>): Request? {
        val first = lines.firstOrNull() ?: return null
        val parts = first.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        // query string 不參與路由
        val path = parts[1].substringBefore('?')
        return Request(parts[0].uppercase(), path, headers)
    }

    /** api.ipify.org 回應本文 → IP 字串；不像 IP 的內容（錯誤頁、captive portal）回 null。 */
    fun parseIpBody(body: String): String? {
        val ip = body.trim()
        val v4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        val v6 = Regex("""^[0-9a-fA-F:]+$""")
        return if (v4.matches(ip) || (ip.contains(':') && v6.matches(ip))) ip else null
    }
}
