package com.freeturn.app.domain.ping

import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.SshConfig
import com.freeturn.app.data.server.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PingUtilTest {

    @Test
    fun `parses time from android ping output`() {
        val lines = listOf(
            "PING 94.249.184.241 (94.249.184.241) 56(84) bytes of data.",
            "64 bytes from 94.249.184.241: icmp_seq=1 ttl=48 time=42.1 ms",
            "64 bytes from 94.249.184.241: icmp_seq=2 ttl=48 time=41.9 ms",
            "",
            "--- 94.249.184.241 ping statistics ---",
            "3 packets transmitted, 3 received, 0% packet loss, time 2001ms"
        )
        assertEquals(42.1, PingUtil.parseTimeMs(lines[1])!!, 0.001)
        assertEquals(41.9, PingUtil.parseTimeMs(lines[2])!!, 0.001)
        assertNull(PingUtil.parseTimeMs(lines[0]))
        assertNull(PingUtil.parseTimeMs(lines[3]))
        assertNull(PingUtil.parseTimeMs(lines[5]))
    }

    @Test
    fun `parses time from busybox ping output`() {
        val line = "64 bytes from 94.249.184.241: seq=0 ttl=48 time=12.34 ms"
        assertEquals(12.34, PingUtil.parseTimeMs(line)!!, 0.001)
    }

    @Test
    fun `fails on unreachable or parsing noise`() {
        assertNull(PingUtil.parseTimeMs("Request timeout for icmp_seq 1"))
        assertNull(PingUtil.parseTimeMs(""))
    }

    @Test
    fun `hostOnly strips single port`() {
        assertEquals("94.249.184.241", PingUtil.hostOnly("94.249.184.241:56256"))
        assertEquals("94.249.184.241", PingUtil.hostOnly(" 94.249.184.241:56256 "))
    }

    @Test
    fun `hostOnly keeps bare ip and hostname`() {
        assertEquals("94.249.184.241", PingUtil.hostOnly("94.249.184.241"))
        assertEquals("example.com", PingUtil.hostOnly("example.com"))
    }

    @Test
    fun `hostOnly handles bracketed ipv6`() {
        assertEquals("::1", PingUtil.hostOnly("[::1]:80"))
        assertEquals("2001:db8::1", PingUtil.hostOnly("[2001:db8::1]:8080"))
        assertEquals("::1", PingUtil.hostOnly("::1"))
    }

    @Test
    fun `pingTarget prefers endpoint and falls back to ssh ip`() {
        val s = Server(
            name = "s",
            ssh = SshConfig(ip = "10.0.0.2"),
            client = ClientConfig(serverAddress = "94.249.184.241:56256")
        )
        assertEquals("94.249.184.241", PingUtil.pingTarget(s))

        val sshOnly = Server(
            name = "s",
            ssh = SshConfig(ip = "10.0.0.2"),
            client = ClientConfig(serverAddress = "")
        )
        assertEquals("10.0.0.2", PingUtil.pingTarget(sshOnly))

        val nothing = Server(name = "s")
        assertNull(PingUtil.pingTarget(nothing))
    }
}