package com.tokyoxpa3.androidproxy.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlHttpTest {
    private fun req(vararg lines: String) = ControlHttp.parseHead(lines.toList())

    @Test
    fun parsesRequestLineAndHeaders() {
        val r = req("post /rotate?x=1 HTTP/1.1", "Host: a", "User-Agent:  curl/8 ")!!
        assertEquals("POST", r.method)
        assertEquals("/rotate", r.path)
        assertEquals("curl/8", r.headers["user-agent"])
    }

    @Test
    fun rejectsMalformedRequestLine() {
        assertNull(req())
        assertNull(req("GET /status"))
        assertNull(req("GET /status FTP/1.0"))
    }

    @Test
    fun parsesIpBody() {
        assertEquals("175.223.14.234", ControlHttp.parseIpBody("175.223.14.234\n"))
        assertEquals("2001:db8::1", ControlHttp.parseIpBody("2001:db8::1"))
        assertNull(ControlHttp.parseIpBody("<html>captive</html>"))
        assertNull(ControlHttp.parseIpBody(""))
    }
}
