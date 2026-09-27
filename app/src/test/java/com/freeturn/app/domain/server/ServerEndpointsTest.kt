package com.freeturn.app.domain.server

import com.freeturn.app.domain.ServerState
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerEndpointsTest {

    private fun live(listen: String?, connect: String?) = ServerState.Known(
        installed = true, running = true, listen = listen, connect = connect
    )

    @Test
    fun `start берет живые эндпоинты вместо дефолтных заглушек`() {
        val (l, c) = resolveStartEndpoints(
            DefaultProxyListen, DefaultProxyConnect,
            live("0.0.0.0:56256", "127.0.0.1:51545")
        )
        assertEquals("0.0.0.0:56256", l)
        assertEquals("127.0.0.1:51545", c)
    }

    @Test
    fun `start без живых данных оставляет сохранённые (даже дефолтные)`() {
        val (l, c) = resolveStartEndpoints(DefaultProxyListen, DefaultProxyConnect, null)
        assertEquals(DefaultProxyListen, l)
        assertEquals(DefaultProxyConnect, c)
    }

    @Test
    fun `start уважает сохранённый конфиг, если он не дефолтный`() {
        val (l, c) = resolveStartEndpoints(
            "0.0.0.0:45321", "127.0.0.1:443",
            live("0.0.0.0:56256", "127.0.0.1:51545")
        )
        assertEquals("0.0.0.0:45321", l)
        assertEquals("127.0.0.1:443", c)
    }

    @Test
    fun `start игнорирует битые живые значения`() {
        val keeper = live("0.0.0.0:0", null)
        val (l, c) = resolveStartEndpoints(DefaultProxyListen, DefaultProxyConnect, keeper)
        assertEquals(DefaultProxyListen, l)
        assertEquals(DefaultProxyConnect, c)
    }

    @Test
    fun `restart предпочитает prefer-параметры после apply`() {
        val (l, c) = resolveRestartEndpoints(
            "0.0.0.0:56000", "127.0.0.1:443",
            live("0.0.0.0:56256", "127.0.0.1:51545"),
            DefaultProxyListen, DefaultProxyConnect
        )
        assertEquals("0.0.0.0:56000", l)
        assertEquals("127.0.0.1:443", c)
    }

    @Test
    fun `restart без prefer не трогает живые значения`() {
        val (l, c) = resolveRestartEndpoints(
            null, null,
            live("0.0.0.0:56256", "127.0.0.1:51545"),
            DefaultProxyListen, DefaultProxyConnect
        )
        assertEquals("0.0.0.0:56256", l)
        assertEquals("127.0.0.1:51545", c)
    }

    @Test
    fun `restart без prefer и живых значений идет по сохранённым`() {
        val (l, c) = resolveRestartEndpoints(null, null, null, "0.0.0.0:56000", "127.0.0.1:443")
        assertEquals("0.0.0.0:56000", l)
        assertEquals("127.0.0.1:443", c)
    }
}