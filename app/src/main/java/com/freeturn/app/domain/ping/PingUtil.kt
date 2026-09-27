package com.freeturn.app.domain.ping

import com.freeturn.app.data.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

/** Итог замера задержки до хоста. */
sealed interface PingResult {
    /** Успешный замер - средняя задержка в миллисекундах. */
    data class Ok(val ms: Int) : PingResult
    /** Хост не ответил или замер упал. */
    data object Fail : PingResult
}

/** Состояние пинга сервера для UI. */
sealed interface PingState {
    /** Замер идёт. */
    data object Checking : PingState
    /** [ms] - средняя задержка. */
    data class Ok(val ms: Int) : PingState
    /** Нет ответа. */
    data object Fail : PingState
}

fun PingResult.toPingState(): PingState = when (this) {
    is PingResult.Ok -> PingState.Ok(ms)
    PingResult.Fail -> PingState.Fail
}

/** Замер пинга системным `ping` - он есть на любом Android и не требует root. */
object PingUtil {

    /** Жёсткий предел замера: `ping` при -W 2 и трёх пакетах обычно укладывается в ~6с. */
    private const val TIMEOUT_MS = 10_000L

    suspend fun ping(host: String, attemptCount: Int = 3, perAttemptTimeoutSec: Int = 2): PingResult =
        withContext(Dispatchers.IO) {
            val times = runCatching {
                withTimeout(TIMEOUT_MS) {
                    val process = ProcessBuilder(
                        "ping",
                        "-c", attemptCount.toString(),
                        "-W", perAttemptTimeoutSec.toString(),
                        host
                    )
                        .redirectErrorStream(true)
                        .start()
                    val lines = process.inputStream.bufferedReader().use { it.readLines() }
                    process.waitFor()
                    lines.mapNotNull { line -> parseTimeMs(line) }
                }
            }.getOrNull()
            if (times.isNullOrEmpty()) PingResult.Fail else PingResult.Ok(times.average().roundToInt())
        }

    private val timeRegex = Regex("""time[=<]([0-9]+(?:\.[0-9]+)?)\s*ms""")

    /** Достаёт `time=42.1 ms` из строки вывода `ping`. Отдельная функция ради тестов. */
    internal fun parseTimeMs(line: String): Double? =
        timeRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()

    /** Хост для замера: адрес endpoint клиента, иначе SSH-IP сервера. */
    fun pingTarget(server: Server): String? {
        val raw = server.client.serverAddress.takeIf { it.isNotBlank() }
            ?: server.ssh.ip.takeIf { it.isNotBlank() }
            ?: return null
        return hostOnly(raw)
    }

    /** Отрезает порт: "1.2.3.4:56000" -> "1.2.3.4", "[::1]:80" -> "::1". */
    internal fun hostOnly(address: String): String {
        val a = address.trim()
        if (a.startsWith("[")) return a.substringAfter('[').substringBefore(']')
        if (a.count { it == ':' } == 1) return a.substringBeforeLast(':')
        return a
    }
}