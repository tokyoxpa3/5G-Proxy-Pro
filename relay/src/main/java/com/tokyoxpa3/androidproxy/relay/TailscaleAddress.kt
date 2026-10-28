package com.tokyoxpa3.androidproxy.relay

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 判斷某個介面位址是否為 Tailscale（tailnet）位址，讓 listener 也能綁在上面。
 *
 * Android 上 Tailscale 是一個 VpnService，介面名為 tun*，位址固定落在：
 *  - IPv4：100.64.0.0/10（CGNAT 段）
 *  - IPv6：fd7a:115c:a1e0::/48（Tailscale 專屬 ULA 前綴）
 * tailnet 只有經 Tailscale 認證的裝置才連得進來，與 LAN 同屬「自己的網路」。
 *
 * 兩個條件缺一不可：
 *  - 只看位址不看介面會誤綁電信 CGNAT —— 不少電信商給 rmnet/ccmni 配的正是
 *    100.64.0.0/10，那等於把代理暴露在行動網路上。
 *  - 只看 tun* 不看位址會把代理暴露給其他 VPN（商用 VPN 的隧道網段）。
 *
 * 抽成純 JVM 物件（零 Android 依賴），供單元測試鎖住邊界行為。
 */
object TailscaleAddress {
    private val TS_IPV6_PREFIX = byteArrayOf(
        0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte()
    )

    fun isTailscaleInterface(ifaceName: String): Boolean =
        ifaceName.lowercase().startsWith("tun")

    fun isTailscaleAddress(addr: InetAddress): Boolean {
        val b = addr.address
        return when (addr) {
            // 100.64.0.0/10：第一個位元組 100，第二個位元組高 2 位元為 01（64..127）
            is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 0x40
            is Inet6Address -> TS_IPV6_PREFIX.indices.all { b[it] == TS_IPV6_PREFIX[it] }
            else -> false
        }
    }

    fun shouldBind(ifaceName: String, addr: InetAddress): Boolean =
        isTailscaleInterface(ifaceName) && isTailscaleAddress(addr)
}
