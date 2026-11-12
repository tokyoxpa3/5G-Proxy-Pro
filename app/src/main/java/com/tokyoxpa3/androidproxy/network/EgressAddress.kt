package com.tokyoxpa3.androidproxy.network

/**
 * 判斷「介面上的位址本身能不能當出口」（純函式，可用 JVM 單元測試）。
 *
 * 只有 IPv6 需要判斷：行動網路的 IPv4 幾乎都在 CGNAT 後面（實測 `100.96.34.49`），
 * 介面上看到的是共享位址，真正的公網出口只有對端看得到。既然本機的 IPv4 位址
 * 一律不可信，這裡就沒有「這個 IPv4 算不算公網」的問題要回答 —— IPv4 出口
 * 全部交給 STUN／echo 回報。IPv6 通常沒有 NAT，介面上的全域位址**就是**出口位址，
 * 這種情況完全不需要任何外部探測。
 *
 * 這裡只做位址分類，不碰任何 Android API。
 */
object EgressAddress {

    /**
     * IPv6 是否為「可直接當出口」的全域位址。
     *
     * 排除 loopback、link-local、ULA、multicast 與文件用前綴。
     * IPv4-mapped（`::ffff:192.168.1.1`）也要排除：它是 IPv4 的表示法，
     * 拿它當 IPv6 出口會把兩種位址混在一起比較。
     */
    fun isGlobalIpv6(host: String): Boolean {
        if (!host.contains(':')) return false
        // 介面位址可能帶 zone id（fe80::1%wlan0），比對前先切掉
        val h = host.substringBefore('%').trim().lowercase()
        if (h.isEmpty()) return false
        if (h == "::" || h == "::1") return false
        if (h.startsWith("::ffff:")) return false                        // IPv4-mapped
        if (h.startsWith("fe8") || h.startsWith("fe9") ||
            h.startsWith("fea") || h.startsWith("feb")
        ) return false                                                   // fe80::/10 link-local
        if (h.startsWith("fc") || h.startsWith("fd")) return false       // fc00::/7 ULA
        if (h.startsWith("ff")) return false                             // ff00::/8 multicast
        if (h.startsWith("2001:db8")) return false                       // 文件用
        return true
    }

    /** 從候選位址中挑第一個全域 IPv6；沒有就回 null。 */
    fun pickGlobalIpv6(addresses: List<String>): String? =
        addresses.asSequence()
            .map { it.trim() }
            .firstOrNull { isGlobalIpv6(it) }
}
