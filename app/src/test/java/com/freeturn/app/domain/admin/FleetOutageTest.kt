package com.freeturn.app.domain.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetOutageTest {

    @Test
    fun `single client offline is not a fleet outage`() {
        val d = FleetOutage.check(knownClients = 1, onlineClients = 0, nowSec = 1_000L, downSinceEpoch = null)
        assertFalse(d.outage)
        assertNull(d.downSinceEpoch)
    }

    @Test
    fun `no known clients never alerts`() {
        val d = FleetOutage.check(knownClients = 0, onlineClients = 0, nowSec = 1_000L, downSinceEpoch = null)
        assertFalse(d.outage)
        assertNull(d.downSinceEpoch)
    }

    @Test
    fun `anyone online kills the outage timer`() {
        val d = FleetOutage.check(knownClients = 5, onlineClients = 1, nowSec = 50_000L, downSinceEpoch = 5_000L)
        assertFalse(d.outage)
        assertNull(d.downSinceEpoch)
        assertEquals(0L, d.outSinceSec)
    }

    @Test
    fun `short all-offline does not alert yet`() {
        val d = FleetOutage.check(knownClients = 3, onlineClients = 0, nowSec = 1_000L, downSinceEpoch = null)
        assertFalse(d.outage)
        assertEquals(1_000L, d.downSinceEpoch)
        assertEquals(0L, d.outSinceSec)
    }

    @Test
    fun `all-offline past the threshold alerts`() {
        val start = 1_000L
        val d = FleetOutage.check(knownClients = 3, onlineClients = 0, nowSec = start + FleetOutage.OUTAGE_SECONDS, downSinceEpoch = start)
        assertTrue(d.outage)
        assertEquals(FleetOutage.OUTAGE_SECONDS, d.outSinceSec)
        assertEquals(start, d.downSinceEpoch)
    }

    @Test
    fun `timer continues while outage persists`() {
        val start = 1_000L
        val d = FleetOutage.check(knownClients = 3, onlineClients = 0, nowSec = start + 600L, downSinceEpoch = start)
        assertTrue(d.outage)
        assertEquals(600L, d.outSinceSec)
    }

    @Test
    fun `recovery resets everything`() {
        val d = FleetOutage.check(knownClients = 3, onlineClients = 3, nowSec = 100_000L, downSinceEpoch = 1_000L)
        assertFalse(d.outage)
        assertNull(d.downSinceEpoch)
        assertEquals(0L, d.outSinceSec)
    }
}