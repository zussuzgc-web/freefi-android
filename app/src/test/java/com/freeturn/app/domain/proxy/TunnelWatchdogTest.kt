package com.freeturn.app.domain.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelWatchdogTest {

    @Test
    fun `fresh handshake is healthy and resets the kick`() {
        val d = TunnelWatchdog.decide(up = true, handshakeAgeSec = 60, kickPending = true)
        assertEquals(TunnelWatchdog.Action.Healthy, d.action)
        assertEquals(false, d.kickPending)
    }

    @Test
    fun `no tunnel or no handshake yet just waits`() {
        assertEquals(TunnelWatchdog.Action.Waiting, TunnelWatchdog.decide(false, -1, false).action)
        assertEquals(TunnelWatchdog.Action.Waiting, TunnelWatchdog.decide(true, -1, false).action)
        assertEquals(TunnelWatchdog.Action.Waiting, TunnelWatchdog.decide(false, 900, false).action)
        // && первичный пинок при «ещё не было handshake» не взводится.
        assertEquals(false, TunnelWatchdog.decide(true, -1, false).kickPending)
    }

    @Test
    fun `stale between soft and hard kicks exactly once`() {
        val first = TunnelWatchdog.decide(up = true, handshakeAgeSec = 300, kickPending = false)
        assertEquals(TunnelWatchdog.Action.Kick, first.action)
        assertEquals(true, first.kickPending)

        // Пинок уже был - продолжаем ждать, а не долбим ядро каждые 15 c.
        val again = TunnelWatchdog.decide(up = true, handshakeAgeSec = 300, kickPending = true)
        assertEquals(TunnelWatchdog.Action.Healthy, again.action)
        assertEquals(true, again.kickPending)
    }

    @Test
    fun `hard stale forces full restart`() {
        val d = TunnelWatchdog.decide(up = true, handshakeAgeSec = 540, kickPending = true)
        assertEquals(TunnelWatchdog.Action.Restart, d.action)
        assertEquals(false, d.kickPending)
    }

    @Test
    fun `soft threshold boundary`() {
        assertEquals(TunnelWatchdog.Action.Healthy, TunnelWatchdog.decide(true, 239, false).action)
        assertEquals(TunnelWatchdog.Action.Kick, TunnelWatchdog.decide(true, 240, false).action)
    }

    @Test
    fun `hard threshold boundary`() {
        assertEquals(TunnelWatchdog.Action.Healthy, TunnelWatchdog.decide(true, 479, true).action)
        assertEquals(TunnelWatchdog.Action.Restart, TunnelWatchdog.decide(true, 480, true).action)
    }
}