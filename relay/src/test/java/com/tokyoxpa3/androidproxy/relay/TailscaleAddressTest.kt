package com.tokyoxpa3.androidproxy.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class TailscaleAddressTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun cgnatRangeBoundaries() {
        assertTrue(TailscaleAddress.isTailscaleAddress(ip("100.64.0.0")))
        assertTrue(TailscaleAddress.isTailscaleAddress(ip("100.101.102.103")))
        assertTrue(TailscaleAddress.isTailscaleAddress(ip("100.127.255.255")))
        assertFalse(TailscaleAddress.isTailscaleAddress(ip("100.63.255.255")))
        assertFalse(TailscaleAddress.isTailscaleAddress(ip("100.128.0.0")))
        assertFalse(TailscaleAddress.isTailscaleAddress(ip("10.0.0.1")))
    }

    @Test
    fun tailscaleIpv6Prefix() {
        assertTrue(TailscaleAddress.isTailscaleAddress(ip("fd7a:115c:a1e0::1")))
        assertTrue(TailscaleAddress.isTailscaleAddress(ip("fd7a:115c:a1e0:ab12:4843:cd96:6263:6f2b")))
        assertFalse(TailscaleAddress.isTailscaleAddress(ip("fd7a:115c:a1e1::1")))
        assertFalse(TailscaleAddress.isTailscaleAddress(ip("2001:db8::1")))
    }

    @Test
    fun carrierCgnatOnCellularIsNotBound() {
        // 電信商常把 100.64.0.0/10 配給行動網路介面：絕不能因此綁到 rmnet
        assertFalse(TailscaleAddress.shouldBind("rmnet_data0", ip("100.72.1.2")))
        assertFalse(TailscaleAddress.shouldBind("ccmni0", ip("100.72.1.2")))
    }

    @Test
    fun otherVpnTunnelIsNotBound() {
        assertFalse(TailscaleAddress.shouldBind("tun0", ip("10.8.0.2")))
    }

    @Test
    fun tailscaleTunIsBound() {
        assertTrue(TailscaleAddress.shouldBind("tun0", ip("100.101.102.103")))
        assertTrue(TailscaleAddress.shouldBind("tun1", ip("fd7a:115c:a1e0::5")))
    }
}
