package com.freeturn.app.viewmodel.admin

import android.app.Application
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.ClientId
import com.freeturn.app.data.config.SshConfig
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServersSnapshot
import com.freeturn.app.data.share.ShareInfo
import com.freeturn.app.data.share.ShareLinkBuilder
import com.freeturn.app.domain.admin.FleetOutage
import com.freeturn.app.domain.server.ServerCommandException
import com.freeturn.app.domain.server.ServerErrorCode
import com.freeturn.app.domain.share.ShareRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit

@Serializable
data class AdminClient(
    val id: String,
    val name: String,
    val created_at: String,
    val expires_at: String,
    val blocked: Boolean,
    val active: Boolean,
    val wg_pubkey: String? = null,
    val wg_ip: String? = null,
    val vk_link: String = "",
    /** Сервер (id в настройках), которому принадлежит клиент. Пусто = активный. */
    val serverId: String = "",
    /** Отображаемое имя сервера клиента. */
    val serverName: String = "",
    /** Публичный адрес сервера (host:port), которому выдана подписка. */
    val serverAddress: String = ""
)

/** Ссылка на настроенный сервер для фильтра панели: id + отображаемое имя/адрес. */
@Serializable
data class AdminServerRef(
    val id: String,
    val name: String,
    val address: String = ""
)

@Serializable
data class AdminState(
    val isLoggedIn: Boolean = false,
    val clients: List<AdminClient> = emptyList(),
    /** Все настроенные серверы (id/имя/адрес) — источник опций фильтра по серверу. */
    val servers: List<AdminServerRef> = emptyList(),
    /** id серверов, скрытых из чипов-фильтра. Их клиенты остаются в списке; скрытые возвращаются. */
    val hiddenServerIds: Set<String> = emptySet(),
    val error: String? = null,
    /** Успешная заметка (не ошибка): показывается как «Готово»-диалог. */
    val notice: String? = null,
    val loading: Boolean = false,
    val creating: Boolean = false,
    val generatedLinkId: String? = null,
    val generatedLink: String? = null,
    val registeredIds: Set<String> = emptySet(),
    /** cid -> epoch последнего handshake (WG-бэкенд): онлайн-индикатор и длительность сессии. */
    val peerHandshakes: Map<String, Long> = emptyMap(),
    /** cid -> epoch НАЧАЛА текущей сессии (первое наблюдение онлайн). */
    val peerSessionStarts: Map<String, Long> = emptyMap(),
    /** cid -> публичный endpoint пира (адрес:порт) из последнего handshake. */
    val peerEndpoints: Map<String, String> = emptyMap(),
    /** true - все известные клиенты офлайн дольше [AdminViewModel.FLEET_OUTAGE_SECONDS]: похоже на обрыв флотилии/сервера. */
    val fleetOutage: Boolean = false,
    /** Сколько секунд длится текущий обрыв флотилии (0 - нет обрыва). */
    val fleetOutageSeconds: Long = 0,
    /** Кол-во известных клиентов (контекст для баннера). */
    val knownClientCount: Int = 0,
    /** Кол-во онлайн-клиентов прямо сейчас. */
    val onlineClientCount: Int = 0
)

class AdminViewModel(
    application: Application,
    private val prefs: AppPreferences,
    private val shareRepo: ShareRepository
) : AndroidViewModel(application) {

    companion object {
        /** Сентинел бессрочной подписки: минус-значение в длительности. */
        const val UNLIMITED_MINUTES = -1
        /** Интервал опроса сервера для онлайн-статуса и регистрации, мс. */
        const val POLL_INTERVAL_MS = 30_000L
        /** Окно онлайн-статуса: handshake младше этого возраста = клиент сейчас в VPN. */
        const val ONLINE_WINDOW_SECONDS = 180L
        val UNLIMITED_EXPIRY: Instant = Instant.parse("9999-12-31T23:59:59Z")

        fun isUnlimited(expiresAt: String): Boolean = try {
            !Instant.parse(expiresAt).isBefore(UNLIMITED_EXPIRY)
        } catch (e: Exception) {
            false
        }
    }

    /** Unix-секунды срока подписки клиента; 0 = бессрочно (серверный контракт). */
    private fun expireAtUnix(client: AdminClient): Long =
        if (isUnlimited(client.expires_at)) 0L
        else try {
            val epoch = Instant.parse(client.expires_at).epochSecond
            if (epoch <= 0L) 0L else epoch
        } catch (e: Exception) {
            0L
        }

    /** Серверы с настроенным SSH — панель опрашивает и агрегирует по ним всех. */
    private fun serversWithSsh(snapshot: ServersSnapshot): List<Server> =
        snapshot.list.filter { it.ssh.ip.isNotBlank() }

    /** Уникальный ключ клиента панели: сервер+cid (один и тот же cid бывает на разных серверах, как owner). */
    private fun serverKey(serverId: String, clientId: String): String = "$serverId/$clientId"

    private fun serverKeyOf(client: AdminClient): String = serverKey(client.serverId, client.id)

    /** Сервер операции: тот, где числится клиент; без метки — активный (legacy-поведение). */
    private suspend fun serverForClient(client: AdminClient): Server? {
        val snapshot = prefs.serversSnapshot.first()
        return snapshot.list.firstOrNull { it.id == client.serverId }
            ?: snapshot.active ?: snapshot.list.firstOrNull()
    }

    private val adminPrefs = application.getSharedPreferences("admin_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** id серверов, скрытых из чипов-фильтра панели; хранятся локально и переживают выход. */
    private fun loadHiddenIds(): Set<String> {
        val data = adminPrefs.getString("hidden_servers", "[]") ?: "[]"
        return try {
            json.decodeFromString<List<String>>(data).toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun saveHiddenIds(hidden: Set<String>) {
        adminPrefs.edit().putString("hidden_servers", json.encodeToString(hidden.toList())).apply()
    }

    /** Скрыть сервер из чипов-фильтра (клиенты этого сервера остаются в списке). */
    fun hideServer(serverId: String) {
        val hidden = _state.value.hiddenServerIds + serverId
        _state.value = _state.value.copy(hiddenServerIds = hidden)
        saveHiddenIds(hidden)
    }

    /** Вернуть скрытый сервер в чипы-фильтра. */
    fun unhideServer(serverId: String) {
        val hidden = _state.value.hiddenServerIds - serverId
        _state.value = _state.value.copy(hiddenServerIds = hidden)
        saveHiddenIds(hidden)
    }

    private val _state = MutableStateFlow(AdminState())
    val state: StateFlow<AdminState> = _state.asStateFlow()

    /** Epoch первого наблюдения «все офлайн»; null - флот не в обрыве. */
    private var fleetDownSinceEpoch: Long? = null

    init {
        _state.value = _state.value.copy(hiddenServerIds = loadHiddenIds())
        val loggedIn = adminPrefs.getBoolean("logged_in", false)
        if (loggedIn) {
            _state.value = _state.value.copy(isLoggedIn = true)
            loadClients()
        }
        // Реактивно: серверы появляются в DataStore заново только ПОСЛЕ восстановления
        // из бэкапа/ручного добавления (переустановка их стирает). Каждое изменение
        // снапшота пробуем восстановить панель, пока локальный список пуст.
        viewModelScope.launch {
            prefs.serversSnapshot.collect { snapshot ->
                _state.value = _state.value.copy(
                    servers = snapshot.list.map {
                        AdminServerRef(it.id, it.name, it.client.serverAddress)
                    }
                )
                maybeRecoverClients()
            }
        }
    }

    fun login(password: String): Boolean {
        if (password != "q") {
            _state.value = _state.value.copy(error = "Неверный пароль")
            return false
        }
        adminPrefs.edit().putBoolean("logged_in", true).apply()
        _state.value = _state.value.copy(isLoggedIn = true, error = null, hiddenServerIds = loadHiddenIds())
        loadClients()
        return true
    }

    fun logout() {
        adminPrefs.edit().putBoolean("logged_in", false).apply()
        _state.value = AdminState()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun generateClientId(): String = ClientId.generate()

    fun loadClients() {
        val data = adminPrefs.getString("admin_clients", "[]") ?: "[]"
        val clients = try {
            json.decodeFromString<List<AdminClient>>(data)
        } catch (e: Exception) {
            emptyList()
        }
        _state.value = _state.value.copy(clients = clients)
        if (clients.isEmpty()) maybeRecoverClients()
        refreshRegistrationStatus()
    }

    private fun saveClients(clients: List<AdminClient>) {
        adminPrefs.edit().putString("admin_clients", json.encodeToString(clients)).apply()
        pushClientsToServer(clients)
    }

    /** Флаг, чтобы параллельные триггеры (логин + реакция на серверы) не спорили. */
    private var recovering = false

    /**
     * Восстановление панели после переустановки/очистки данных. Источники в порядке
     * надёжности: 1) allowlist в WG-пирах и clients.json на сервере (share-list) —
     * переживает даже пропажу зеркала; 2) зеркало admin_clients.json (добавляет
     * vk_link/created_at/blocked). Ждёт появления активного сервера с SSH до 20 с:
     * переустановка стирает и список серверов, пользователь добавляет его заново.
     * Silent: сбой транспорта/старого control.sh просто оставляет пустой список.
     */
    private fun maybeRecoverClients() {
        if (recovering) return
        if (!adminPrefs.getBoolean("logged_in", false)) return
        val local = adminPrefs.getString("admin_clients", "[]") ?: "[]"
        if (local != "[]") return
        recoverClients()
    }

    private fun recoverClients() {
        if (recovering) return
        recovering = true
        viewModelScope.launch {
            try {
                val servers = serversWithSsh(prefs.serversSnapshot.first())
                if (servers.isEmpty()) return@launch
                val recovered = buildRecoveredClients(servers) ?: return@launch
                adminPrefs.edit().putString("admin_clients", json.encodeToString(recovered)).apply()
                _state.value = _state.value.copy(clients = recovered, error = _state.value.error)
                refreshRegistrationStatus()
            } finally {
                recovering = false
            }
        }
    }

    /**
     * Собирает список панели из серверной правды по ВСЕМ серверам с SSH: пиры с cid →
     * AdminClient, allowlist без пира → AdminClient, поверх них из зеркала доклеиваем
     * vk_link/created_at/blocked. null = ни один сервер не отдал данных (транспорт недоступен).
     */
    private suspend fun buildRecoveredClients(servers: List<Server>): List<AdminClient>? {
        val byKey = linkedMapOf<String, AdminClient>()
        var anyData = false
        for (server in servers) {
            val allowlist = shareRepo.listShared(server.ssh).getOrNull()
            val mirror = try {
                shareRepo.adminLoad(server.ssh).getOrNull()
                    ?.let { json.decodeFromString<List<AdminClient>>(it) }
            } catch (e: Exception) {
                emptyList()
            } ?: emptyList()
            if (allowlist != null) anyData = true
            if (allowlist != null) {
                val (peers, clients) = allowlist
                for (p in peers) {
                    val cid = p.cid?.takeIf { it.isNotBlank() } ?: continue
                    val key = serverKey(server.id, cid)
                    byKey[key] = AdminClient(
                        id = cid,
                        name = p.name,
                        created_at = createdString(p.createdEpoch),
                        expires_at = expiryString(p.expiresEpoch),
                        blocked = false,
                        active = true,
                        wg_pubkey = p.pubkey,
                        wg_ip = p.ip.takeIf { it.isNotBlank() },
                        vk_link = "",
                        serverId = server.id,
                        serverName = server.name,
                        serverAddress = server.client.serverAddress
                    )
                }
                for (c in clients) {
                    val key = serverKey(server.id, c.clientId)
                    val existing = byKey[key]
                    byKey[key] = AdminClient(
                        id = c.clientId,
                        name = c.name.ifBlank { existing?.name.orEmpty() },
                        created_at = existing?.created_at ?: "",
                        expires_at = expiryString(c.expiresEpoch),
                        blocked = existing?.blocked ?: false,
                        active = existing?.active ?: true,
                        wg_pubkey = existing?.wg_pubkey,
                        wg_ip = existing?.wg_ip,
                        vk_link = "",
                        serverId = server.id,
                        serverName = server.name,
                        serverAddress = server.client.serverAddress
                    )
                }
            }
            for (m in mirror) {
                val key = serverKey(server.id, m.id)
                val cur = byKey[key]
                byKey[key] = if (cur == null) {
                    m.copy(serverId = server.id, serverName = server.name, serverAddress = server.client.serverAddress)
                } else cur.copy(
                    created_at = m.created_at.ifEmpty { cur.created_at },
                    blocked = m.blocked,
                    vk_link = m.vk_link
                )
            }
        }
        return if (byKey.values.isEmpty()) (if (anyData) emptyList() else null)
        else byKey.values.toList()
    }

    private fun expiryString(epoch: Long?): String =
        if (epoch == null || epoch <= 0) UNLIMITED_EXPIRY.toString()
        else Instant.ofEpochSecond(epoch).toString()

    /** created_at из серверного mtime верификации/conf; нет данных -> пусто (UI рисует «—»). */
    private fun createdString(epoch: Long?): String =
        if (epoch == null || epoch <= 0) "" else Instant.ofEpochSecond(epoch).toString()

    /** Зеркалит список клиентов панели на каждый сервер с SSH (best-effort: не мешает панели при сбое SSH). */
    private fun pushClientsToServer(clients: List<AdminClient>) {
        viewModelScope.launch {
            for (server in serversWithSsh(prefs.serversSnapshot.first())) {
                shareRepo.adminSave(server.ssh, json.encodeToString(clients))
            }
        }
    }

    fun createClient(name: String, durationMinutes: Int, clientId: String, vkLink: String = "", wgPubkey: String? = null, wgIp: String? = null) {
        if (_state.value.creating) return
        if (_state.value.clients.any { it.id == clientId }) {
            _state.value = _state.value.copy(error = "Клиент уже существует")
            return
        }
        _state.value = _state.value.copy(creating = true, error = null)
        viewModelScope.launch {
            val server = prefs.serversSnapshot.first().active

            var registrationError: String? = null
            var wgPub = wgPubkey
            var wgIpAddress = wgIp

            val now = Instant.now()
            val expiresAt = if (durationMinutes < 0) UNLIMITED_EXPIRY
            else now.plus(durationMinutes.toLong(), ChronoUnit.MINUTES)
            val expireUnix = if (isUnlimited(expiresAt.toString())) 0L else expiresAt.epochSecond

            val ssh = server?.ssh?.takeIf { it.ip.isNotBlank() }
            if (ssh != null) {
                // Без тихого фолбэка: ошибка shareInfo в WG-бэкенде превращается в
                // «прокси-ссылку без туннеля» (реальный IP у гостя). Лучше честный отказ.
                val info = shareRepo.shareInfo(ssh)
                info.onSuccess { share ->
                    if (share.wgBackend) {
                        shareRepo.addPeer(ssh, name, ClientConfig.DEFAULT_LOCAL_PORT, clientId, expireUnix)
                            .onSuccess { peer ->
                                wgPub = peer.pubkey
                                wgIpAddress = peer.ip
                            }
                            .onFailure { e ->
                                val code = (e as? ServerCommandException)?.code
                                if (code == ServerErrorCode.LINK_ALREADY_USED) {
                                    registrationError = "Клиент уже зарегистрирован, но его WG-конфиг недоступен. Создайте клиента заново."
                                } else {
                                    val detail = (e as? ServerCommandException)?.message ?: e.message
                                    registrationError = detail?.takeIf { it.isNotBlank() } ?: "SSH ошибка"
                                }
                            }
                    } else {
                        shareRepo.addClient(ssh, name, clientId, expireUnix)
                            .onFailure { e ->
                                val code = (e as? ServerCommandException)?.code
                                if (code != ServerErrorCode.LINK_ALREADY_USED) {
                                    val detail = (e as? ServerCommandException)?.message ?: e.message
                                    registrationError = detail?.takeIf { it.isNotBlank() } ?: "SSH ошибка"
                                }
                            }
                    }
                }
                info.onFailure { e ->
                    val detail = (e as? ServerCommandException)?.message ?: e.message
                    registrationError =
                        detail?.takeIf { it.isNotBlank() } ?: "не удалось получить параметры сервера (share-info)"
                }
            }

            val client = AdminClient(
                id = clientId,
                name = name,
                created_at = now.toString(),
                expires_at = expiresAt.toString(),
                blocked = false,
                active = true,
                wg_pubkey = wgPub,
                wg_ip = wgIpAddress,
                vk_link = vkLink.trim(),
                serverId = server?.id ?: "",
                serverName = server?.name ?: "",
                serverAddress = server?.client?.serverAddress ?: ""
            )

            val clients = _state.value.clients.toMutableList()
            clients.add(client)
            saveClients(clients)
            val stateError = when {
                server == null -> "Нет активного сервера. Клиент создан, но не зарегистрирован в allowlist."
                server.ssh.ip.isBlank() ->
                    "SSH не настроен для активного сервера. Клиент создан, но не зарегистрирован в allowlist — гость не сможет подключиться. Настройте SSH (Настройки → Серверы → сервер) и повторите «Поделиться ссылкой»."
                registrationError != null ->
                    "Клиент не зарегистрирован в allowlist (SSH ошибка: $registrationError). Клиент создан, но гость не сможет подключиться, пока регистрация не пройдёт."
                else -> null
            }
            _state.value = _state.value.copy(
                creating = false,
                clients = clients,
                error = stateError
            )
            if (stateError == null) generateShareLink(client)
            refreshRegistrationStatus()
        }
    }

    fun reregisterClient(clientId: String) {
        val client = _state.value.clients.find { it.id == clientId } ?: return
        _state.value = _state.value.copy(error = null)
        viewModelScope.launch {
            val server = serverForClient(client)
            when {
                server == null -> {
                    _state.value = _state.value.copy(error = "Нет активного сервера — регистрация невозможна.")
                }
                server.ssh.ip.isBlank() -> {
                    _state.value = _state.value.copy(
                        error = "SSH не настроен для активного сервера. Настройте SSH (Настройки → Серверы → сервер) и повторите."
                    )
                }
                else -> {
                    shareRepo.shareInfo(server.ssh)
                        .onSuccess { info ->
                            if (info.wgBackend) {
                                ensureWgPeer(server.ssh, client)
                                    .onSuccess {
                                        _state.value = _state.value.copy(error = "Клиент зарегистрирован в WG на сервере.")
                                        generateShareLink(client)
                                    }
                                    .onFailure { e ->
                                        val detail = (e as? ServerCommandException)?.message ?: e.message
                                        _state.value = _state.value.copy(
                                            error = "Регистрация не удалась (SSH: ${detail?.takeIf { it.isNotBlank() } ?: "ошибка"})."
                                        )
                                    }
                            } else {
                                shareRepo.addClient(server.ssh, client.name, clientId, expireAtUnix(client))
                                    .onSuccess {
                                        _state.value = _state.value.copy(error = "Клиент зарегистрирован в allowlist сервера.")
                                        generateShareLink(client)
                                    }
                                    .onFailure { e ->
                                        val code = (e as? ServerCommandException)?.code
                                        if (code == ServerErrorCode.LINK_ALREADY_USED) {
                                            _state.value = _state.value.copy(error = "Клиент уже зарегистрирован в allowlist сервера.")
                                            generateShareLink(client)
                                        } else {
                                            val detail = (e as? ServerCommandException)?.message ?: e.message
                                            _state.value = _state.value.copy(
                                                error = "Регистрация не удалась (SSH: ${detail?.takeIf { it.isNotBlank() } ?: "ошибка"})."
                                            )
                                        }
                                    }
                            }
                        }
.onFailure { e ->
                            val detail = (e as? ServerCommandException)?.message ?: e.message
                            _state.value = _state.value.copy(
                                error = "Регистрация не удалась — не получены параметры сервера (SSH: ${detail?.takeIf { it.isNotBlank() } ?: "ошибка"})."
                            )
                        }
                    refreshRegistrationStatus()
                }
        }
    }
    }

    /**
     * Сброс сессии клиента на сервере: ядро рвёт активное устройство-держателя
     * (привязка «клиент -> устройство» в памяти очищается), следующий коннект
     * любого устройства принимается заново. Лечит «уже активно другое устройство»
     * после переустановки приложения / неаккуратного переключения устройств.
     */
    fun bounceClient(clientId: String) {
        val client = _state.value.clients.find { it.id == clientId } ?: return
        _state.value = _state.value.copy(error = null)
        viewModelScope.launch {
            val server = serverForClient(client)
            when {
                server == null -> {
                    _state.value = _state.value.copy(error = "Нет активного сервера — сброс сессии невозможен.")
                }
                server.ssh.ip.isBlank() -> {
                    _state.value = _state.value.copy(
                        error = "SSH не настроен для активного сервера. Настройте SSH (Настройки → Серверы → сервер) и повторите."
                    )
                }
                else -> {
                    shareRepo.bounceClient(server.ssh, client.name, clientId, expireAtUnix(client))
                        .onSuccess {
                            val whom = client.name.ifBlank { clientId }
                            // Мгновенно гасим онлайн-статус и сессию карточки: разрыв только что
                            // выполнен на сервере, а share-list ещё ~минуты будет отдавать прежний
                            // handshake (окно 180 с), из-за чего без этого клиент выглядел бы
                            // «в сети» и время сессии росло после нажатия.
                            _state.value = _state.value.copy(
                                notice = "Сессия «$whom» сброшена на сервере: клиент отключён. При следующем подключении начнётся новая сессия.",
                                error = null,
                                peerHandshakes = _state.value.peerHandshakes - clientId,
                                peerSessionStarts = _state.value.peerSessionStarts - clientId
                            )
                        }
                        .onFailure { e ->
                            val detail = (e as? ServerCommandException)?.message ?: e.message
                            _state.value = _state.value.copy(
                                error = "Сброс сессии не удался (SSH: ${detail?.takeIf { it.isNotBlank() } ?: "ошибка"})."
                            )
                        }
                }
            }
        }
    }

    /**
     * Полное обновление панели с сервера (кнопка «Обновить»): перечитывает
     * allowlist/пиров ВСЕХ серверов, доклеивает зеркало (даты/sроки/vk),
     * добавляет клиентов, которых нет локально. Список сходится к серверной правде:
     * призраки (запись есть, а cid на здоровом сервере отсутствует) удаляются,
     * локальные «ещё не зарегистрированные» (serverId пуст, пира нет) остаются.
     */
    fun refreshAll() {
        _state.value = _state.value.copy(error = null, loading = true)
        viewModelScope.launch {
            val snapshot = prefs.serversSnapshot.first()
            val servers = serversWithSsh(snapshot)
            if (servers.isEmpty()) {
                _state.value = _state.value.copy(
                    error = "Обновить не удалось: нет серверов с SSH. Настройте сервер и повторите.",
                    loading = false
                )
                refreshRegistrationStatus()
                return@launch
            }
            val discovered = linkedMapOf<String, AdminClient>()
            val failedServerIds = mutableSetOf<String>()
            var anyData = false
            for (server in servers) {
                val listResult = shareRepo.listShared(server.ssh)
                if (listResult.isFailure) {
                    failedServerIds += server.id
                    continue
                }
                anyData = true
                val (peers, shared) = listResult.getOrThrow()
                for (p in peers) {
                    val cid = p.cid?.takeIf { it.isNotBlank() } ?: continue
                    val key = serverKey(server.id, cid)
                    discovered[key] = AdminClient(
                        id = cid,
                        name = p.name,
                        created_at = createdString(p.createdEpoch),
                        expires_at = expiryString(p.expiresEpoch),
                        blocked = false,
                        active = true,
                        wg_pubkey = p.pubkey,
                        wg_ip = p.ip.takeIf { it.isNotBlank() },
                        vk_link = "",
                        serverId = server.id,
                        serverName = server.name,
                        serverAddress = server.client.serverAddress
                    )
                }
                for (c in shared) {
                    val key = serverKey(server.id, c.clientId)
                    val peer = discovered[key]
                    discovered[key] = AdminClient(
                        id = c.clientId,
                        name = c.name.ifBlank { peer?.name.orEmpty() },
                        created_at = peer?.created_at.orEmpty(),
                        expires_at = expiryString(c.expiresEpoch),
                        blocked = false,
                        active = true,
                        wg_pubkey = peer?.wg_pubkey,
                        wg_ip = peer?.wg_ip,
                        vk_link = "",
                        serverId = server.id,
                        serverName = server.name,
                        serverAddress = server.client.serverAddress
                    )
                }
                // Зеркало только обогащает найденную серверную правду (vk/даты/блок),
                // призраков из зеркала не возрождаем.
                val mirror = try {
                    shareRepo.adminLoad(server.ssh).getOrNull()
                        ?.let { json.decodeFromString<List<AdminClient>>(it) }
                } catch (e: Exception) {
                    emptyList()
                } ?: emptyList()
                for (m in mirror) {
                    val key = serverKey(server.id, m.id)
                    val cur = discovered[key] ?: continue
                    discovered[key] = cur.copy(
                        created_at = m.created_at.ifEmpty { cur.created_at },
                        blocked = m.blocked,
                        vk_link = m.vk_link
                    )
                }
            }

            if (!anyData) {
                val detail = servers.joinToString("; ") { s ->
                    "${s.name}: недоступен"
                }
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Обновить не удалось: серверы не отвечают (SSH). $detail"
                )
                refreshRegistrationStatus()
                return@launch
            }

            val remaining = discovered.toMutableMap()
            val merged = mutableListOf<AdminClient>()
            for (c in _state.value.clients) {
                var fromServer = remaining.remove(serverKeyOf(c))
                if (fromServer == null && c.serverId.isBlank()) {
                    // Legacy-запись без метки сервера: ищем по cid на любом сервере.
                    val key = remaining.keys.firstOrNull { it.endsWith("/" + c.id) }
                    if (key != null) fromServer = remaining.remove(key)
                }
                if (fromServer != null) {
                    merged += fromServer
                    continue
                }
                val keep = when {
                    c.serverId.isBlank() && c.wg_pubkey.isNullOrBlank() -> true
                    c.serverId in failedServerIds -> true
                    else -> false
                }
                if (keep) merged += c
            }
            merged += remaining.values
            val changed = merged != _state.value.clients
            if (changed) {
                saveClients(merged)
            }
            val partial = failedServerIds.takeIf { it.isNotEmpty() }
                ?.let { ids -> servers.filter { it.id in ids }.joinToString("; ") { it.name } }
            _state.value = _state.value.copy(
                clients = merged,
                loading = false,
                error = partial?.let { "Обновлено не полностью: серверы недоступны — $it. Их клиенты не проверены." }
            )
            refreshRegistrationStatus()
        }
    }

    fun blockClient(clientId: String) {
        val client = _state.value.clients.find { it.id == clientId } ?: return
        viewModelScope.launch {
            val server = serverForClient(client)
            val error = when {
                server == null -> "Нет активного сервера — блокировка применена только локально."
                server.ssh.ip.isBlank() -> "SSH не настроен для активного сервера — доступ на сервере не снят."
                else -> null
            }
            if (error != null) {
                updateClient(clientId) { it.copy(blocked = true) }
                _state.value = _state.value.copy(error = error)
                return@launch
            }
            val ssh = server!!.ssh
            val revokeResult = if (client.wg_pubkey.isNullOrBlank()) {
                shareRepo.removeClient(ssh, clientId)
            } else {
                shareRepo.removePeer(ssh, client.wg_pubkey)
            }
            revokeResult
                .onFailure { e ->
                    val detail = (e as? ServerCommandException)?.message ?: e.message
                    _state.value = _state.value.copy(
                        error = "Не удалось снять доступ на сервере (SSH: ${detail?.takeIf { it.isNotBlank() } ?: "ошибка"}). Блокировка применена только локально."
                    )
                }
            updateClient(clientId) { it.copy(blocked = true) }
            refreshRegistrationStatus()
        }
    }

    fun unblockClient(clientId: String) {
        val client = _state.value.clients.find { it.id == clientId } ?: return
        val isExpired = try {
            Instant.parse(client.expires_at).isBefore(Instant.now())
        } catch (e: Exception) { false }

        if (isExpired) {
            _state.value = _state.value.copy(error = "Подписка истекла. Сначала продлите.")
            return
        }
        viewModelScope.launch {
            val server = serverForClient(client)
            if (server == null || server.ssh.ip.isBlank()) {
                _state.value = _state.value.copy(
                    error = "SSH не настроен для активного сервера — доступ на сервере не выдан. Гость не сможет подключиться."
                )
                updateClient(clientId) { it.copy(blocked = false) }
                return@launch
            }
            restoreServerAccess(server.ssh, client)
            updateClient(clientId) { it.copy(blocked = false) }
            refreshRegistrationStatus()
        }
    }

    /** Массовая блокировка: доступ на сервере снимается, клиенты остаются в списке. */
    fun blockClients(clientIds: List<String>) {
        clientIds.forEach { blockClient(it) }
    }

    /** Массовый отзыв: клиенты удаляются из панели и с сервера. */
    fun revokeClients(clientIds: List<String>) {
        clientIds.forEach { revokeClient(it) }
    }

    /** Массовое изменение срока без даунтайма: каждый клиент обрабатывается отдельно. */
    fun extendClients(clientIds: List<String>, durationMinutes: Int, subtract: Boolean = false) {
        clientIds.forEach { extendClient(it, durationMinutes, subtract) }
    }

    fun revokeClient(clientId: String) {
        val client = _state.value.clients.find { it.id == clientId }
        viewModelScope.launch {
            val server = client?.let { serverForClient(it) }
            val stash = _state.value
            if (server != null && server.ssh.ip.isNotBlank()) {
                val ssh = server.ssh
                val revoke = if (client.wg_pubkey.isNullOrBlank()) {
                    shareRepo.removeClient(ssh, clientId)
                } else {
                    shareRepo.removePeer(ssh, client.wg_pubkey)
                }
                revoke.onFailure { e ->
                    val detail = (e as? ServerCommandException)?.message ?: e.message
                    _state.value = stash.copy(
                        error = "Не удалось снять доступ на сервере (SSH: ${detail?.takeIf { it.isNotBlank() } ?: "ошибка"}). Клиент удалён локально."
                    )
                }
            }
            val clients = stash.clients.filter { it.id != clientId }
            saveClients(clients)
            _state.value = stash.copy(clients = clients, error = _state.value.error)
        }
    }

    /** Выдаёт доступ на сервере в режиме бэкенда: peer-add (WG) или client-add (allowlist). */
    private suspend fun restoreServerAccess(ssh: SshConfig, client: AdminClient) {
        val infoResult = shareRepo.shareInfo(ssh)
        val info = infoResult.getOrNull()
        if (info == null) {
            val detail = (infoResult.exceptionOrNull() as? ServerCommandException)?.message
                ?: infoResult.exceptionOrNull()?.message
            _state.value = _state.value.copy(
                error = "Не удалось выдать доступ на сервере — не получены параметры сервера (SSH: ${detail?.takeIf { it.isNotBlank() } ?: "ошибка"})."
            )
            return
        }
        val wg = info.wgBackend
        val result = if (wg) {
            shareRepo.addPeer(ssh, client.name, ClientConfig.DEFAULT_LOCAL_PORT, client.id, expireAtUnix(client))
                .map { peer ->
                    updateClient(client.id) {
                        it.copy(wg_pubkey = peer.pubkey, wg_ip = peer.ip)
                    }
                }
        } else {
            shareRepo.addClient(ssh, client.name, client.id, expireAtUnix(client))
                .recover { e ->
                    if (e is ServerCommandException && e.code == ServerErrorCode.LINK_ALREADY_USED) Unit
                    else throw e
                }
        }
        result.onFailure { e ->
            val detail = (e as? ServerCommandException)?.message ?: e.message
            _state.value = _state.value.copy(
                error = "Не удалось выдать доступ на сервере (SSH: ${detail?.takeIf { it.isNotBlank() } ?: "ошибка"})."
            )
        }
    }

    /**
     * Меняет срок подписки: продлевает от текущей даты либо срока, а при [subtract] — убавляет.
     * Отрицательный [durationMinutes] по-прежнему означает «бессрочно» (только без [subtract]).
     */
    fun extendClient(clientId: String, durationMinutes: Int, subtract: Boolean = false) {
        val client = _state.value.clients.find { it.id == clientId } ?: return
        val now = Instant.now()
        val currentExpiry = try {
            Instant.parse(client.expires_at)
        } catch (e: Exception) { now }
        val wasExpired = !isUnlimited(client.expires_at) && currentExpiry.isBefore(now)
        val action = if (subtract) "Уменьшение срока" else "Продление"

        if (subtract) {
            if (isUnlimited(client.expires_at)) {
                _state.value = _state.value.copy(
                    notice = "У бессрочной подписки срока нет — убавлять нечего."
                )
                return
            }
            if (wasExpired) {
                _state.value = _state.value.copy(
                    notice = "Срок подписки уже истёк — убавлять нечего."
                )
                return
            }
        }

        val newExpiry = if (subtract) {
            // Отсчёт от оставшегося срока; если убавили больше, чем осталось — подписка
            // истекает сразу (не раньше текущего момента).
            val candidate = currentExpiry.minus(durationMinutes.toLong().coerceAtLeast(0), ChronoUnit.MINUTES)
            if (candidate.isBefore(now)) now else candidate
        } else if (durationMinutes < 0) {
            UNLIMITED_EXPIRY
        } else if (currentExpiry.isBefore(now)) {
            now.plus(durationMinutes.toLong(), ChronoUnit.MINUTES)
        } else {
            currentExpiry.plus(durationMinutes.toLong(), ChronoUnit.MINUTES)
        }
        // Убавление, приведшее к немедленному истечению, применяем на сервере сразу.
        val needsSweepNow = subtract && !newExpiry.isAfter(now)

        updateClient(clientId) {
            it.copy(
                expires_at = newExpiry.toString(),
                blocked = if (subtract) it.blocked else false
            )
        }
        val updated = _state.value.clients.find { it.id == clientId } ?: client
        val expireUnix = if (isUnlimited(updated.expires_at)) 0L else try {
            Instant.parse(updated.expires_at).epochSecond
        } catch (e: Exception) {
            0L
        }
        viewModelScope.launch {
            val server = serverForClient(client)
            if (server != null && server.ssh.ip.isNotBlank()) {
                // Гарантия автоотзыва по сроку: sweep-install идемпотентен и восстанавливает
                // таймер на старых серверах, где он не был доставлен при установке.
                shareRepo.sweepInstall(server.ssh)
                shareRepo.setExpiry(server.ssh, clientId, expireUnix)
                    .onFailure { e ->
                        val detail = (e as? ServerCommandException)?.message ?: e.message
                        _state.value = _state.value.copy(
                            error = "$action не синхронизировано с сервером (SSH: ${detail?.takeIf { it.isNotBlank() } ?: "ошибка"}). Автоотзыв на сервере будет по старому сроку."
                        )
                    }
                    .onSuccess {
                        if (subtract) {
                            _state.value = _state.value.copy(
                                notice = "Срок подписки уменьшён: ${updated.name.ifBlank { clientId }}."
                            )
                        }
                    }
                if (needsSweepNow) {
                    // Срок уже прошёл: sweeper должен отозвать доступ немедленно, а не по таймеру.
                    shareRepo.sweep(server.ssh)
                }
                if (wasExpired) {
                    // Истёкшего клиента sweeper уже снял с allowlist, а set-expiry ставит
                    // только маркер. Возрождаем доступ: peer-conf / client-add с будущим
                    // сроком возвращают запись в allowlist.
                    shareRepo.shareInfo(server.ssh)
                        .onSuccess { info ->
                            val result = if (info.wgBackend) {
                                ensureWgPeer(server.ssh, updated).map { }
                            } else {
                                shareRepo.addClient(server.ssh, updated.name, clientId, expireUnix)
                                    .recover { e ->
                                        if (e is ServerCommandException && e.code == ServerErrorCode.LINK_ALREADY_USED) Unit
                                        else throw e
                                    }
                            }
                            result.onFailure { e ->
                                val detail = (e as? ServerCommandException)?.message ?: e.message
                                _state.value = _state.value.copy(
                                    error = "$action не выдано на сервере (SSH: ${detail?.takeIf { it.isNotBlank() } ?: "ошибка"}). Гость пока не сможет подключиться."
                                )
                            }
                            refreshRegistrationStatus()
                        }
                }
            }
        }
    }

    fun generateShareLink(client: AdminClient) {
        viewModelScope.launch {
            val server = serverForClient(client)
            if (server == null) {
                _state.value = _state.value.copy(error = "Нет активного сервера")
                return@launch
            }
            if (server.client.serverAddress.isBlank()) {
                _state.value = _state.value.copy(error = "Адрес сервера не настроен")
                return@launch
            }
            val ssh = server.ssh.takeIf { it.ip.isNotBlank() }
            val info: ShareInfo
            if (ssh != null) {
                val infoResult = shareRepo.shareInfo(ssh)
                val loaded = infoResult.getOrNull()
                if (loaded == null) {
                    val detail = (infoResult.exceptionOrNull() as? ServerCommandException)?.message
                        ?: infoResult.exceptionOrNull()?.message
                    // Ошибка shareInfo на WG-сервере дала бы «прокси-ссылку без туннеля»
                    // (реальный IP у гостя) — отказываемся собирать такую ссылку.
                    _state.value = _state.value.copy(
                        error = "Не удалось получить параметры сервера (SSH: ${detail?.takeIf { it.isNotBlank() } ?: "ошибка"}). Ссылка не собрана."
                    )
                    return@launch
                }
                info = loaded
            } else {
                // Без SSH серверную правду не вытащить: только локальная proxy-сборка.
                info = ShareInfo()
            }
            val wgConf: String?
            if (ssh != null && info.wgBackend) {
                val confResult = ensureWgPeer(ssh, client)
                if (confResult.isFailure) {
                    val detail = (confResult.exceptionOrNull() as? ServerCommandException)?.message
                        ?: confResult.exceptionOrNull()?.message
                    _state.value = _state.value.copy(
                        error = "Не удалось получить WG-доступ клиента (SSH: ${detail?.takeIf { it.isNotBlank() } ?: "ошибка"})."
                    )
                    return@launch
                }
                wgConf = confResult.getOrThrow()
            } else {
                if (ssh != null) {
                    // Прокси-бэкенд: регистрируем в allowlist идемпотентно.
                    shareRepo.addClient(ssh, client.name, client.id, expireAtUnix(client))
                        .onFailure { e ->
                            val code = (e as? ServerCommandException)?.code
                            if (code != ServerErrorCode.LINK_ALREADY_USED) {
                                val detail = (e as? ServerCommandException)?.message ?: e.message
                                _state.value = _state.value.copy(
                                    error = "Не удалось зарегистрировать клиента в allowlist (SSH: ${detail?.takeIf { it.isNotBlank() } ?: "ошибка"}). Гость не сможет подключиться."
                                )
                                return@launch
                            }
                        }
                }
                wgConf = null
            }
            val link = ShareLinkBuilder.build(
                server = server,
                info = info,
                userName = client.name,
                wgConf = wgConf,
                clientId = client.id,
                vkLink = client.vk_link,
                expiresUnix = expireAtUnix(client),
                statusPort = info.statusPort
            )
            _state.value = _state.value.copy(
                generatedLinkId = client.id,
                generatedLink = link
            )
            refreshRegistrationStatus()
        }
    }

    /**
     * Гарантирует наличие WG-пира для клиента и возвращает его client-conf для ссылки.
     * Legacy-клиент, числящийся только в allowlist (старый формат без пира), мигрируется
     * в пир: снимаем allowlist-запись и заводим peer-add заново.
     */
    private suspend fun ensureWgPeer(ssh: SshConfig, client: AdminClient): Result<String> {
        val pub = client.wg_pubkey?.takeIf { it.isNotBlank() }
        if (pub != null) {
            return shareRepo.peerConf(ssh, pub, client.id, client.name, expireAtUnix(client)).map { it.clientConf }
        }
        var pending = shareRepo.addPeer(ssh, client.name, ClientConfig.DEFAULT_LOCAL_PORT, client.id, expireAtUnix(client))
        if (pending.isFailure &&
            (pending.exceptionOrNull() as? ServerCommandException)?.code == ServerErrorCode.LINK_ALREADY_USED) {
            shareRepo.removeClient(ssh, client.id).getOrElse { }
            pending = shareRepo.addPeer(ssh, client.name, ClientConfig.DEFAULT_LOCAL_PORT, client.id, expireAtUnix(client))
        }
        return pending.map { peer ->
            updateClient(client.id) { it.copy(wg_pubkey = peer.pubkey, wg_ip = peer.ip) }
            peer.clientConf
        }
    }

    /**
     * Проверка состояния allowlist на серверах: отмечает клиентов, которые
     * реально зарегистрированы. WG-бэкенд: регистрация живёт в пирах с cid
     * (clients.json пустой), поэтому ids собираем из ОБОИХ источников по ВСЕМ
     * серверам. Заодно тянем handshake-эпохи пиров для онлайн-индикатора.
     * Если серверы не отвечают — не молчим: показываем ошибку, чтобы «не
     * зарегистрирован» по всем клиентам не выглядело серверной правдой.
     */
    fun refreshRegistrationStatus() {
        viewModelScope.launch {
            val snapshot = prefs.serversSnapshot.first()
            val servers = serversWithSsh(snapshot)
            if (servers.isEmpty()) {
                fleetDownSinceEpoch = null
                _state.value = _state.value.copy(
                    registeredIds = emptySet(),
                    peerHandshakes = emptyMap(),
                    peerSessionStarts = emptyMap(),
                    peerEndpoints = emptyMap(),
                    fleetOutage = false,
                    fleetOutageSeconds = 0,
                    knownClientCount = 0,
                    onlineClientCount = 0
                )
                return@launch
            }
            val ids = mutableSetOf<String>()
            val handshakes = mutableMapOf<String, Long>()
            val serverStarts = mutableMapOf<String, Long>()
            val createdByCid = mutableMapOf<String, Long>()
            val endpoints = mutableMapOf<String, String>()
            var failures = 0
            var firstError: String? = null
            for (server in servers) {
                shareRepo.listShared(server.ssh)
                    .onSuccess { (peers, shared) ->
                        peers.forEach { p ->
                            val cid = p.cid?.takeIf { c -> c.isNotBlank() } ?: return@forEach
                            ids.add(cid)
                            p.lastHandshakeEpoch?.let { handshakes[cid] = it }
                            p.sessionStartEpoch?.let { serverStarts[cid] = it }
                            p.createdEpoch?.let { createdByCid[cid] = it }
                            p.endpoint?.let { endpoints[cid] = it }
                        }
                        shared.forEach { ids.add(it.clientId) }
                    }
                    .onFailure {
                        failures++
                        if (firstError == null) {
                            firstError = (it as? ServerCommandException)?.message?.takeIf { m -> m.isNotBlank() }
                                ?: it.message
                        }
                    }
            }
            val usable = failures < servers.size
            if (!usable) {
                fleetDownSinceEpoch = null
                _state.value = _state.value.copy(
                    registeredIds = emptySet(),
                    peerHandshakes = emptyMap(),
                    peerSessionStarts = emptyMap(),
                    peerEndpoints = emptyMap(),
                    fleetOutage = false,
                    fleetOutageSeconds = 0,
                    knownClientCount = 0,
                    onlineClientCount = 0,
                    error = "Не удалось проверить регистрацию: серверы не отвечают (SSH: ${firstError ?: "ошибка"}). Проверьте настройки серверов и нажмите «Обновить»."
                )
                return@launch
            }
            val nowEpoch = Instant.now().epochSecond
            val onlineCids = handshakes.filterValues { nowEpoch - it < ONLINE_WINDOW_SECONDS }.keys
            val fleet = FleetOutage.check(
                knownClients = ids.size,
                onlineClients = onlineCids.size,
                nowSec = nowEpoch,
                downSinceEpoch = fleetDownSinceEpoch
            )
            fleetDownSinceEpoch = fleet.downSinceEpoch
            val sessions = _state.value.peerSessionStarts.toMutableMap()
            sessions.keys.retainAll(onlineCids)
            for (cid in onlineCids) {
                val hs = handshakes.getValue(cid)
                sessions[cid] = serverStarts[cid] ?: (sessions[cid] ?: hs)
            }
            var datesFilled = false
            val fixedClients = _state.value.clients.map { c ->
                val cr = createdByCid[c.id]
                if (cr != null && c.created_at.isBlank()) {
                    datesFilled = true
                    c.copy(created_at = createdString(cr))
                } else c
            }
            _state.value = _state.value.copy(
                registeredIds = ids,
                peerHandshakes = handshakes,
                peerSessionStarts = sessions,
                peerEndpoints = endpoints,
                fleetOutage = fleet.outage,
                fleetOutageSeconds = fleet.outSinceSec,
                knownClientCount = ids.size,
                onlineClientCount = onlineCids.size,
                clients = if (datesFilled) fixedClients else _state.value.clients
            )
            if (datesFilled) {
                adminPrefs.edit()
                    .putString("admin_clients", json.encodeToString(fixedClients))
                    .apply()
            }
        }
    }

    /** Периодический опрос сервера, пока экран панели открыт. */
    private var pollingJob: Job? = null

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (true) {
                refreshRegistrationStatus()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun copyLinkToClipboard() {
        val link = _state.value.generatedLink ?: return
        val ctx = getApplication<Application>()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("freeturn link", link))
    }

    fun dismissGeneratedLink() {
        _state.value = _state.value.copy(generatedLinkId = null, generatedLink = null)
    }

    private fun updateClient(clientId: String, transform: (AdminClient) -> AdminClient) {
        val clients = _state.value.clients.map {
            if (it.id == clientId) transform(it) else it
        }
        saveClients(clients)
        _state.value = _state.value.copy(clients = clients, error = null)
    }
}