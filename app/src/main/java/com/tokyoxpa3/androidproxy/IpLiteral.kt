package com.tokyoxpa3.androidproxy

import java.net.InetAddress

/**
 * IP 字面值判斷（不觸發 DNS）。redirector / proxifier 型客戶端（NetRedirector 等）
 * 攔截的是應用程式已建立的 TCP 連線目的地，CONNECT 只會帶 IP 位址（ATYP 0x01/0x04）
 * 而沒有網域；DnsResolver.query() 對字面值的行為不同，會把它當網域查詢而必然失敗。
 * 此物件僅在確定是字面值時做本地剖析，hostname 一律回 null 交給正常 DNS 路徑。
 *
 * 抽成純 JVM 物件（零 Android 依賴），供單元測試鎖住邊界行為。
 */
object IpLiteral {
    private val IPV4_RE = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun parse(host: String): InetAddress? {
        val looksLikeIpv4 = IPV4_RE.matches(host)
        val looksLikeIpv6 = host.contains(':') // IPv6 字面值必含冒號；hostname 不可能
        if (!looksLikeIpv4 && !looksLikeIpv6) return null
        return try {
            InetAddress.getByName(host) // 字面值僅本地剖析，不觸發 DNS
        } catch (e: Exception) {
            null
        }
    }
}
