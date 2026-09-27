package com.freeturn.app.domain.server

import com.freeturn.app.data.config.HostPort
import com.freeturn.app.data.config.KcpProfile
import com.freeturn.app.data.config.ProxyMode
import com.freeturn.app.domain.ServerState

sealed class ServerCommand {
    data object Probe : ServerCommand()
    data object Install : ServerCommand()

    data class WgSetup(
        val port: Int,
        val endpoint: String
    ) : ServerCommand()
    data class Start(val opts: ServerStartOptions) : ServerCommand()
    data object Stop : ServerCommand()
    data class FetchLogs(val lines: Int = 80) : ServerCommand()

    data class Uninstall(
        val withWgPkg: Boolean = false,
        val dryRun: Boolean = false
    ) : ServerCommand()

    data object ShareInfo : ServerCommand()

    data class PeerAdd(
        val nameB64: String,
        val endpoint: String,
        val clientId: String,
        val expireAtUnix: Long = 0L
    ) : ServerCommand()

    data object ShareList : ServerCommand()

    data class PeerConf(
        val pubkey: String,
        val clientId: String,
        val nameB64: String,
        val expireAtUnix: Long = 0L
    ) : ServerCommand()
    data class PeerRemove(val pubkey: String) : ServerCommand()

    data class ClientAdd(val nameB64: String, val clientId: String, val expireAtUnix: Long = 0L) : ServerCommand()
    data class ClientRemove(val clientId: String) : ServerCommand()
    data class ClientBounce(val nameB64: String, val clientId: String, val expireAtUnix: Long = 0L) : ServerCommand()

    data class SetExpiry(val clientId: String, val expireAtUnix: Long) : ServerCommand()
    data object Sweep : ServerCommand()
    data object SweepInstall : ServerCommand()

    /** Декодит statusd-бинарь из $PREFIX/statusd.deploy.b64 и запускает его. */
    data object StatusdDeploy : ServerCommand()

    /** Копия списка клиентов панели админа на сервер (base64-пейлоад). */
    data class AdminSave(val b64: String) : ServerCommand()
    data object AdminLoad : ServerCommand()

    fun toArgv(): List<String> = when (this) {
        is Probe -> listOf("probe")
        is Install -> listOf("install")
        is WgSetup -> buildList {
            add("wg-setup")
            add("--port=$port")
            add("--endpoint=$endpoint")
        }
        is Start -> buildList {
            add("start")
            add("--listen=${opts.listen}")
            add("--connect=${opts.connect}")
            if (opts.proxyMode == ProxyMode.TCP) {
                add("--mode=${ProxyMode.TCP}")
                addAll(kcpArgs(opts.kcp))
            }
            if (opts.obfProfile != "none" && opts.obfKey.isNotBlank()) {
                add("--obf-profile=${opts.obfProfile}")
                add("--obf-key=${opts.obfKey}")
                // Пейсинг без профиля ядро отвергает - только внутри этой ветки.
                if (opts.obfTimingMs > 0) add("--obf-timing=${opts.obfTimingMs}ms")
            }
            if (opts.clientId.isNotBlank()) add("--client-id=${opts.clientId}")
        }
        is Stop -> listOf("stop")
        is FetchLogs -> listOf("logs", "--tail=$lines")
        is ShareInfo -> listOf("share-info")
        is PeerAdd -> buildList {
            add("peer-add")
            add("--name-b64=$nameB64")
            add("--endpoint=$endpoint")
            if (clientId.isNotBlank()) add("--client-id=$clientId")
            if (expireAtUnix > 0L) add("--expire=$expireAtUnix")
        }
        is ShareList -> listOf("share-list")
        is PeerConf -> buildList {
            add("peer-conf")
            add("--pubkey=$pubkey")
            if (clientId.isNotBlank()) add("--client-id=$clientId")
            if (nameB64.isNotBlank()) add("--name-b64=$nameB64")
            if (expireAtUnix > 0L) add("--expire=$expireAtUnix")
        }
        is PeerRemove -> listOf("peer-remove", "--pubkey=$pubkey")
        is ClientAdd -> buildList {
            add("client-add")
            add("--name-b64=$nameB64")
            add("--client-id=$clientId")
            if (expireAtUnix > 0L) add("--expire=$expireAtUnix")
        }
        is ClientRemove -> listOf("client-remove", "--client-id=$clientId")
        is ClientBounce -> buildList {
            add("client-bounce")
            add("--client-id=$clientId")
            add("--name-b64=$nameB64")
            if (expireAtUnix > 0L) add("--expire=$expireAtUnix")
        }
        is SetExpiry -> buildList {
            add("set-expiry")
            add("--client-id=$clientId")
            add("--expire=$expireAtUnix")
        }
        is Sweep -> listOf("sweep")
        is SweepInstall -> listOf("sweep-install")
        is StatusdDeploy -> listOf("statusd-deploy")
        is AdminSave -> listOf("admin-save", "--b64=$b64")
        is AdminLoad -> listOf("admin-load")
        is Uninstall -> buildList {
            add("uninstall")
            if (withWgPkg) add("--with-wg-pkg")
            if (dryRun) add("--dry-run")
        }
    }
}

data class ServerStartOptions(
    val listen: String,
    val connect: String,
    val proxyMode: String = ProxyMode.UDP,
    val kcp: KcpProfile = KcpProfile.DEFAULT,
    val obfProfile: String = "none",
    val obfKey: String = "",
    val obfTimingMs: Int = 0,
    val clientId: String = ""
)

/** Только отличия от дефолта: остальное сервер возьмёт своё, как и клиент. */
private fun kcpArgs(p: KcpProfile): List<String> = buildList {
    val d = KcpProfile.DEFAULT
    if (p.noDelay != d.noDelay) add("--kcp-nodelay=${p.noDelay}")
    if (p.interval != d.interval) add("--kcp-interval=${p.interval}")
    if (p.resend != d.resend) add("--kcp-resend=${p.resend}")
    if (p.nc != d.nc) add("--kcp-nc=${p.nc}")
    if (p.sndWnd != d.sndWnd) add("--kcp-sndwnd=${p.sndWnd}")
    if (p.rcvWnd != d.rcvWnd) add("--kcp-rcvwnd=${p.rcvWnd}")
    if (p.mtu != d.mtu) add("--kcp-mtu=${p.mtu}")
    if (p.ackNoDelay != d.ackNoDelay) add("--kcp-acknodelay=${p.ackNoDelay}")
}

/** Дефолтные заглушки, которые приложение подставляет импортированным серверам без полей. */
const val DefaultProxyListen = "0.0.0.0:56000"
const val DefaultProxyConnect = "127.0.0.1:40537"

/**
 * listen/connect для ручного «Старт». Уважаем сохранённый конфиг, но если он не
 * конфигурировался (дефолтная заглушка импортированного сервера) или битый -
 * берём живые значения сервера из probe (run.args), чтобы случайный рестарт
 * через приложение не перезаписал рабочий порт дефолтным.
 */
fun resolveStartEndpoints(
    savedListen: String,
    savedConnect: String,
    live: ServerState.Known?
): Pair<String, String> {
    val liveListen = live?.listen?.takeIf { HostPort.isValid(it) }
    val liveConnect = live?.connect?.takeIf { HostPort.isValid(it) }
    fun pick(savedValue: String, liveValue: String?, fallbackDefault: String): String =
        if (savedValue != fallbackDefault && HostPort.isValid(savedValue)) savedValue
        else liveValue ?: savedValue
    return pick(savedListen, liveListen, DefaultProxyListen) to
        pick(savedConnect, liveConnect, DefaultProxyConnect)
}

/**
 * listen/connect для фонового рестарта (смена сервера, debounced sync). Не трогаем
 * то, что реально запущено: prefer (после явного apply) > живые с сервера > сохранённые.
 */
fun resolveRestartEndpoints(
    preferListen: String?,
    preferConnect: String?,
    live: ServerState.Known?,
    savedListen: String,
    savedConnect: String
): Pair<String, String> {
    fun firstValid(vararg values: String?): String? =
        values.firstOrNull { it != null && HostPort.isValid(it) }
    val l = firstValid(preferListen, live?.listen, savedListen) ?: savedListen
    val c = firstValid(preferConnect, live?.connect, savedConnect) ?: savedConnect
    return l to c
}
