package com.tokyoxpa3.androidproxy.network

/**
 * 判斷「介面上的位址本身能不能當出口」（純函式，可用 JVM 單元測試）。
 *
 * 為什麼需要它：行動網路的 IPv4 幾乎都在 CGNAT 後面（實測 `100.96.34.49`），
 * 介面上看到的是共享位址，真正的公網出口只有對端看得到 —— 這就是為什麼
 * 出口 IP 必須靠 echo 服務回報。但 IPv6 通常沒有 NAT，介面上的全域位址
 * **就是**出口位址，這種情況完全不需要任何外部探測。
 *
 * 這裡只做位址分類，不碰任何 Android API。
 */
object EgressAddress {

    /**
     * IPv4 是否為公網可路由。
     *
     * 排除清單刻意寫得比 RFC1918 寬：CGNAT（`100.64.0.0/10`）是行動網路最常見的
     * 情況，漏掉它會讓 `100.96.x.x` 被誤認成公網位址 —— 於是探測永遠回傳同一個
     * 假值，真正的出口變更反而完全偵測不到。
     */
    fun isPublicIpv4(host: String): Boolean {
        val o = octets(host) ?: return false
        val a = o[0]
        val b = o[1]
        val c = o[2]
        return when {
            a == 0 -> false                                  // 0.0.0.0/8
            a == 10 -> false                                 // 10.0.0.0/8
            a == 127 -> false                                // 127.0.0.0/8 loopback
            a == 169 && b == 254 -> false                    // 169.254.0.0/16 link-local
            a == 172 && b in 16..31 -> false                 // 172.16.0.0/12
            a == 192 && b == 168 -> false                    // 192.168.0.0/16
            a == 100 && b in 64..127 -> false                // 100.64.0.0/10 CGNAT
            a == 192 && b == 0 && c == 0 -> false            // 192.0.0.0/24
            a == 192 && b == 0 && c == 2 -> false            // 192.0.2.0/24 TEST-NET-1
            a == 192 && b == 88 && c == 99 -> false          // 192.88.99.0/24
            a == 198 && (b == 18 || b == 19) -> false        // 198.18.0.0/15 benchmark
            a == 198 && b == 51 && c == 100 -> false         // 198.51.100.0/24 TEST-NET-2
            a == 203 && b == 0 && c == 113 -> false          // 203.0.113.0/24 TEST-NET-3
            a >= 224 -> false                                // multicast + 保留
            else -> true
        }
    }

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

    /** 從候選位址中挑第一個公網可路由的 IPv4；沒有就回 null。 */
    fun pickPublicIpv4(addresses: List<String>): String? =
        addresses.asSequence()
            .map { it.trim() }
            .firstOrNull { isPublicIpv4(it) }

    private fun octets(host: String): IntArray? {
        val parts = host.trim().split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            // 不用 toIntOrNull 的寬鬆解析：它會接受 "+1" 這種非位址寫法
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v
        }
        return out
    }
}
