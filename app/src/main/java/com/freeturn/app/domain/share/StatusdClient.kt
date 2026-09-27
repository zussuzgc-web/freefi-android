package com.freeturn.app.domain.share

import android.content.Context
import com.freeturn.app.data.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * HTTP-статус подписки гостя: statusd - небольшой Go-демон на сервере
 * (порт туннеля + 1, см. server-control 56-statusd.sh). Гость без SSH не
 * умеет share-list, поэтому тянет JSON напрямую по ?cid=. Любая ошибка
 * тихо -> null: UI живёт снимком из ссылки, пока statusd недоступен.
 */
class StatusdClient(context: Context) {

    private val appContext = context.applicationContext

    /** null - транспорт/парсинг не сошёлся или клиент не найден. */
    suspend fun fetch(server: Server): MySubscription? = withContext(Dispatchers.IO) {
        val c = server.client
        if (c.clientId.isBlank()) return@withContext null
        val statusPort = StatusdClient.effectiveStatusPort(c.statusPort, c.serverAddress)
        if (statusPort <= 0) return@withContext null
        val host = StatusdClient.hostOf(c.serverAddress)
        if (host.isBlank()) return@withContext null
        val query = URLEncoder.encode(c.clientId.trim(), Charsets.UTF_8.name())
        val conn = (URL("http://$host:$statusPort/status?cid=$query").openConnection() as HttpURLConnection)
            .apply {
                connectTimeout = 4_000
                readTimeout = 4_000
                requestMethod = "GET"
            }
        try {
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            runCatching {
                val o = JSONObject(body)
                if (!o.optBoolean("found", false)) return@runCatching null
                MySubscription(
                    found = true,
                    expiresEpoch = o.optLong("expires", 0L).takeIf { it > 0L },
                    expired = o.optBoolean("expired", false),
                    online = o.optBoolean("online", false)
                )
            }.getOrNull()
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /**
         * Host/port из serverAddress: "1.2.3.4", "1.2.3.4:56000", "[::1]:56000".
         * Порт туннеля не нужен - статус живёт на отдельном порту сервера.
         */
        internal fun hostOf(address: String): String {
            var a = address.trim()
            if (a.startsWith("[") && a.contains(']')) return a.substringAfter('[').substringBefore(']')
            val colon = a.lastIndexOf(':')
            if (colon > 0 && a.substring(colon + 1).toIntOrNull() != null) {
                a = a.substring(0, colon)
            }
            return a
        }

        /**
         * Effective statusd port: explicit from the link (sp), else server default
         * «core port + 1» from the peer address (server-control 56-statusd.sh).
         * Covers records imported before sp existed in links - no re-import needed.
         * An unreachable statusd silently returns null, UI stays on the link snapshot.
         */
        internal fun effectiveStatusPort(statusPort: Int, address: String): Int {
            if (statusPort > 0) return statusPort.coerceIn(0, 65535)
            val a = address.trim()
            // "[::1]:56000" -> tail ":56000"; "1.2.3.4:56256" -> whole address.
            val tail = if (a.contains(']')) a.substringAfterLast(']') else a
            val peerPort = tail.substringAfterLast(':').toIntOrNull() ?: return 0
            return (peerPort + 1).coerceIn(0, 65535)
        }
    }
}