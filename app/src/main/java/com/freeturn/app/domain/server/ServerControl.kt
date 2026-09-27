package com.freeturn.app.domain.server

import android.content.Context
import com.freeturn.app.data.config.SshConfig
import com.freeturn.app.data.control.ControlResponse
import com.freeturn.app.data.control.ControlResponseParser
import com.freeturn.app.data.control.StatusdDeployData
import com.freeturn.app.domain.ssh.SSHManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/** Запускает [ServerCommand] на удалённом хосте, стримя скрипт через SSH stdin. */
class ServerControl(
    context: Context,
    private val ssh: SSHManager
) {
    private val appContext = context.applicationContext

    /**
     * Скрипт управления сервером из ассетов. null - сборка без server-control
     * (например, open-source): быстрой установки на VPS просто нет, ронять
     * приложение на этом нельзя - отдаём ошибку.
     */
    private val scriptOrNull: String? by lazy {
        runCatching {
            appContext.assets.open(SCRIPT_ASSET).bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull()
    }

    private fun missingScript(): ControlResponse =
        ControlResponse(proto = 2, result = "err", code = "internal", msg = SCRIPT_MISSING)

    /** Команда запуска скрипта с эскалацией по rootMode (скрипт идёт в stdin). */
    private fun remoteCmd(argv: List<String>, cfg: SshConfig): String {
        val base = "bash -s -- " + argv.joinToString(" ") { shellQuote(it) }
        return when (cfg.rootMode) {
            SshConfig.SUDO_NOPASS -> "sudo -n $base"
            // -k сбрасывает кэш timestamp - иначе sudo может не спросить пароль, и он утечёт в stderr как команда.
            SshConfig.SUDO_PASS   -> "sudo -k -S -p '' $base"
            else                  -> base
        }
    }

    // SUDO_PASS: пароль идёт ПЕРВОЙ строкой stdin (sudo -S съест её, остаток -
    // скрипт - достаётся bash). Пусто для key-auth -> sudo не пройдёт -> sudo_auth_failed.
    private fun effectiveSudoPassword(cfg: SshConfig): String =
        cfg.sudoPassword.ifBlank { if (cfg.authType == SshConfig.AUTH_PASSWORD) cfg.password else "" }

    suspend fun run(cfg: SshConfig, cmd: ServerCommand): ControlResponse = withContext(Dispatchers.IO) {
        if (cfg.ip.isBlank()) {
            return@withContext ControlResponse(proto = 2, result = "err", code = "transport", msg = "no SSH config")
        }
        val script = scriptOrNull ?: return@withContext missingScript()
        val stdin = if (cfg.rootMode == SshConfig.SUDO_PASS) {
            effectiveSudoPassword(cfg) + "\n" + script
        } else {
            script
        }
        val output = ssh.executeWithStdin(
            ip = cfg.ip,
            port = cfg.port,
            user = cfg.username,
            pass = cfg.password,
            command = remoteCmd(cmd.toArgv(), cfg),
            stdin = stdin,
            knownFingerprint = cfg.hostFingerprint.ifEmpty { null },
            sshKey = if (cfg.authType == SshConfig.AUTH_SSH_KEY) cfg.sshKey else ""
        )
        ControlResponseParser.parse(output)
    }

    /**
     * Preflight: определяет [SshConfig.rootMode]. Дешёвый exec без большого скрипта.
     * null - транспортная ошибка (соединение не удалось).
     */
    suspend fun detectRootMode(cfg: SshConfig): String? = withContext(Dispatchers.IO) {
        val out = ssh.executeSilentCommand(
            cfg.ip, cfg.port, cfg.username, cfg.password,
            "id -u; command -v sudo >/dev/null 2>&1 && { sudo -n true 2>/dev/null && echo FT_SUDO_NOPASS || echo FT_SUDO_PASS; }",
            knownFingerprint = cfg.hostFingerprint.ifEmpty { null },
            sshKey = if (cfg.authType == SshConfig.AUTH_SSH_KEY) cfg.sshKey else ""
        )
        if (out.startsWith("ERROR:")) null else classifyRootMode(out)
    }

    /**
     * Заворачивает значение в одинарные кавычки для bash. Любая `'` внутри
     * заменяется на `'\''` - стандартный приём.
     */
    private fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        // Если строка состоит только из безопасных символов - обходимся без кавычек.
        if (s.matches(Regex("^[A-Za-z0-9._:=\\-/]+$"))) return s
        return "'" + s.replace("'", "'\\''") + "'"
    }

    /** PREFIX, куда серверный скрипт кладёт своё состояние (см. 00-header.sh). */
    private val serverPrefix = "/opt/free-turn-proxy"

    /**
     * Деплой управления на сервер: пишет локальную копию control.sh. Нужна для
     * systemd timer автоотзыва (sweep-install). Идемпотентно; обновляет файл.
     */
    suspend fun deployControlScript(cfg: SshConfig): ControlResponse = withContext(Dispatchers.IO) {
        if (cfg.ip.isBlank()) {
            return@withContext ControlResponse(proto = 2, result = "err", code = "transport", msg = "no SSH config")
        }
        val target = "$serverPrefix/control.sh"
        val script = scriptOrNull ?: return@withContext missingScript()
        val cmd = when (cfg.rootMode) {
            SshConfig.SUDO_NOPASS -> "sudo -n bash -c 'cat > $target'"
            SshConfig.SUDO_PASS   -> "sudo -k -S -p '' bash -c 'cat > $target'"
            else                  -> "bash -c 'cat > $target'"
        }
        val stdin = if (cfg.rootMode == SshConfig.SUDO_PASS) {
            effectiveSudoPassword(cfg) + "\n" + script
        } else {
            script
        }
        val output = ssh.executeWithStdin(
            ip = cfg.ip,
            port = cfg.port,
            user = cfg.username,
            pass = cfg.password,
            command = cmd,
            stdin = stdin,
            knownFingerprint = cfg.hostFingerprint.ifEmpty { null },
            sshKey = if (cfg.authType == SshConfig.AUTH_SSH_KEY) cfg.sshKey else ""
        )
        // Файл пишем напрямую (stdout пустой) - ok, если транспорт жив.
        if (output.startsWith("ERROR:")) {
            ControlResponseParser.parse(output)
        } else {
            ControlResponse(proto = 2, result = "ok", data = JsonObject(emptyMap()))
        }
    }

    /**
     * Деплой statusd на сервер: SSH-команда пишет base64 бинаря в
     * $PREFIX/statusd.deploy.b64, затем control `statusd-deploy` декодит и
     * поднимает демон. Result == порт HTTP-статуса (0 = не установилось:
     * арка без биндла, нет ассета или ошибка). Ошибки не роняют поток -
     * вызывающий решает, молча уйти на снимок из ссылки.
     */
    suspend fun deployStatusd(cfg: SshConfig): Result<Int> = withContext(Dispatchers.IO) {
        if (cfg.ip.isBlank()) {
            return@withContext Result.failure(IllegalStateException("no SSH config"))
        }
        val uname = ssh.executeSilentCommand(
            cfg.ip, cfg.port, cfg.username, cfg.password, "uname -m",
            knownFingerprint = cfg.hostFingerprint.ifEmpty { null },
            sshKey = if (cfg.authType == SshConfig.AUTH_SSH_KEY) cfg.sshKey else ""
        )
        if (uname.startsWith("ERROR:") || uname.isBlank()) {
            return@withContext Result.failure(IllegalStateException("uname failed: $uname".take(80)))
        }
        val asset = statusdAssetFor(uname) ?: return@withContext Result.success(0)
        val bytes = runCatching {
            appContext.assets.open(asset).use { it.readBytes() }
        }.getOrElse { return@withContext Result.success(0) }

        val target = "$serverPrefix/statusd.deploy.b64"
        val cmd = when (cfg.rootMode) {
            SshConfig.SUDO_NOPASS -> "sudo -n bash -c 'cat > $target'"
            SshConfig.SUDO_PASS   -> "sudo -k -S -p '' bash -c 'cat > $target'"
            else                  -> "bash -c 'cat > $target'"
        }
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val stdin = if (cfg.rootMode == SshConfig.SUDO_PASS) {
            effectiveSudoPassword(cfg) + "\n" + b64 + "\n"
        } else {
            b64 + "\n"
        }
        val output = ssh.executeWithStdin(
            ip = cfg.ip,
            port = cfg.port,
            user = cfg.username,
            pass = cfg.password,
            command = cmd,
            stdin = stdin,
            knownFingerprint = cfg.hostFingerprint.ifEmpty { null },
            sshKey = if (cfg.authType == SshConfig.AUTH_SSH_KEY) cfg.sshKey else ""
        )
        if (output.startsWith("ERROR:")) {
            return@withContext Result.failure(IllegalStateException(output.take(80)))
        }
        run(cfg, ServerCommand.StatusdDeploy)
            .requireData<StatusdDeployData>()
            .map { it.statusPort.coerceIn(0, 65535) }
    }

    companion object {
        const val SCRIPT_ASSET = "free-turn-control.sh"
        private const val SCRIPT_MISSING =
            "Скрипт управления сервером не входит в эту сборку (соберите её с каталогом server-control)"

        /** statusd-ассет для uname -m; null = для арки биндла нет. */
        fun statusdAssetFor(unameM: String): String? = when (unameM.trim()) {
            "x86_64", "amd64" -> "statusd-linux-amd64"
            "aarch64", "arm64" -> "statusd-linux-arm64"
            else -> null
        }

        /** Классификация вывода preflight в [SshConfig].rootMode-константу. */
        fun classifyRootMode(output: String): String {
            val euid = output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.toIntOrNull() != null }
                ?.toIntOrNull()
            return when {
                euid == 0 -> SshConfig.ROOT
                output.contains("FT_SUDO_NOPASS") -> SshConfig.SUDO_NOPASS
                output.contains("FT_SUDO_PASS") -> SshConfig.SUDO_PASS
                // Нет sudo / неясно: bare bash, скрипт честно вернёт needs_root.
                else -> SshConfig.ROOT
            }
        }
    }
}
