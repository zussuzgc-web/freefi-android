package com.freeturn.app.domain.share

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusdClientTest {

    @Test
    fun `explicit status port wins`() {
        assertEquals(56001, StatusdClient.effectiveStatusPort(56001, "1.2.3.4:56000"))
        assertEquals(65535, StatusdClient.effectiveStatusPort(70000, "1.2.3.4:56000"))
    }

    @Test
    fun `status port derived from peer port plus one`() {
        assertEquals(56257, StatusdClient.effectiveStatusPort(0, "77.90.52.175:56256"))
        assertEquals(56001, StatusdClient.effectiveStatusPort(0, "1.2.3.4:56000"))
    }

    @Test
    fun `ipv6 derived from bracketed address`() {
        assertEquals(56001, StatusdClient.effectiveStatusPort(0, "[::1]:56000"))
    }

    @Test
    fun `no resolvable peer port yields zero`() {
        assertEquals(0, StatusdClient.effectiveStatusPort(0, "1.2.3.4"))
        assertEquals(0, StatusdClient.effectiveStatusPort(0, ""))
        assertEquals(0, StatusdClient.effectiveStatusPort(0, "example.com"))
    }

    @Test
    fun `host extracted from ipv4 and ipv6 addresses`() {
        assertEquals("1.2.3.4", StatusdClient.hostOf("1.2.3.4"))
        assertEquals("1.2.3.4", StatusdClient.hostOf("1.2.3.4:56256"))
        assertEquals("::1", StatusdClient.hostOf("[::1]:56000"))
        assertEquals("example.com", StatusdClient.hostOf("example.com"))
        assertEquals("", StatusdClient.hostOf(""))
    }
}