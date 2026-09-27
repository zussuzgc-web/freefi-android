@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.freeturn.app.R
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.viewmodel.admin.AdminClient
import com.freeturn.app.viewmodel.admin.AdminServerRef
import com.freeturn.app.viewmodel.admin.AdminViewModel
import android.content.Context
import android.content.Intent
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PRESET_DURATIONS_MIN = listOf(
    1 to "1 мин",
    1440 to "1 день",
    43200 to "30 дней",
    86400 to "60 дней",
    AdminViewModel.UNLIMITED_MINUTES to "Бесконечно"
)

/** Спец-идентификатор опции фильтра «клиенты без привязки к серверу» (legacy). */
private const val NO_SERVER_ID = "__no_server__"

/** Окно онлайн-статуса: handshake младше этого возраста = клиент сейчас в VPN. */
private const val ONLINE_WINDOW_SECONDS = 180L

private fun formatSession(seconds: Long): String =
    when {
        seconds < 60 -> "$seconds сек"
        seconds < 3600 -> "${seconds / 60} мин"
        else -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            if (m == 0L) "$h ч" else "$h ч $m мин"
        }
    }

/** Метка сервера карточки: имя (+ адрес, если задан). Всегда видна, даже для одного сервера. */
private fun buildServerLabel(client: AdminClient): String {
    val name = client.serverName.trim()
    val address = client.serverAddress.trim()
    return when {
        name.isNotEmpty() && address.isNotEmpty() -> "Сервер: $name ($address)"
        name.isNotEmpty() -> "Сервер: $name"
        address.isNotEmpty() -> "Сервер: $address"
        else -> ""
    }
}

/** Метка чипа фильтра: имя (+ адрес, если задан). */
private fun serverOptionLabel(name: String, address: String): String {
    val n = name.trim()
    val a = address.trim()
    return when {
        n.isNotEmpty() && a.isNotEmpty() -> "$n ($a)"
        n.isNotEmpty() -> n
        a.isNotEmpty() -> a
        else -> "Сервер"
    }
}

private enum class StatusFilter(val label: String) {
    ALL("Все"),
    ACTIVE("Активен"),
    BLOCKED("Заблокирован"),
    EXPIRED("Истёк")
}

private enum class SortMode(val label: String) {
    DEFAULT("Как есть"),
    EXPIRY("Истекающие скоро"),
    NAME("По имени")
}

/** Статистика сервера для полосы нагрузки: онлайн/всего + признак самого загруженного. */
private data class ServerStat(val id: String, val label: String, val total: Int, val online: Int)

/** Красная подсветка: осталось меньше этого числа дней до истечения. */
private const val EXPIRY_WARN_DAYS = 7L

private fun daysWord(n: Long): String = when {
    n % 10L == 1L && n % 100L != 11L -> "день"
    n % 10L in 2L..4L && n % 100L !in 12L..14L -> "дня"
    else -> "дней"
}

/** «осталось N дн.» (или пусто для бессрочного/истёкшего). */
private fun daysLeftText(expiresAt: String): Pair<String, Long> {
    if (AdminViewModel.isUnlimited(expiresAt)) return "" to Long.MAX_VALUE
    val days = try {
        (Duration.between(Instant.now(), Instant.parse(expiresAt)).toDays())
    } catch (e: Exception) {
        return "" to Long.MAX_VALUE
    }
    if (days < 0) return "" to Long.MAX_VALUE
    return "· осталось $days ${daysWord(days)}" to days
}

/** Хост из endpoint «addr:port» (у IPv6 снимаем скобки и порт). */
private fun hostFromEndpoint(endpoint: String): String? {
    val trimmed = endpoint.trim()
    if (trimmed.isEmpty()) return null
    val withoutPort = if (trimmed.startsWith("[")) {
        val end = trimmed.indexOf(']')
        if (end > 0) trimmed.substring(1, end) else trimmed
    } else {
        trimmed.substringBefore(':')
    }
    return withoutPort.takeIf { it.isNotBlank() }
}

/** Экспорт списка клиентов в CSV в кэш + share-интент (FileProvider). */
private fun exportClientsCsv(context: Context, clients: List<AdminClient>) {
    val rows = buildString {
        append("\uFEFF")
        append("id\tname\tcreated_at\texpires_at\tblocked\tstatus\tserver\tvk_link\tserver_id\r\n")
        val now = Instant.now()
        clients.forEach { c ->
            val unlimited = AdminViewModel.isUnlimited(c.expires_at)
            val expired = !unlimited && runCatching { Instant.parse(c.expires_at).isBefore(now) }.getOrDefault(false)
            val status = when {
                c.blocked -> "blocked"
                expired -> "expired"
                else -> "active"
            }
            append(c.id.replace('\t', ' '))
            append('\t')
            append(c.name.replace('\t', ' '))
            append('\t')
            append(c.created_at)
            append('\t')
            append(c.expires_at)
            append('\t')
            append(c.blocked)
            append('\t')
            append(status)
            append('\t')
            append(c.serverName.replace('\t', ' '))
            append('\t')
            append(c.vk_link.replace('\t', ' '))
            append('\t')
            append(c.serverId)
            append("\r\n")
        }
    }
    val dir = File(context.cacheDir, "export")
    dir.mkdirs()
    val target = File(dir, "clients.csv")
    target.writeText(rows, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Клиенты FreeTurn")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Экспорт клиентов"))
}

@Composable
fun AdminDashboardScreen(
    clients: List<AdminClient>,
    servers: List<AdminServerRef>,
    hiddenServerIds: Set<String>,
    loading: Boolean,
    error: String?,
    notice: String?,
    generatedLinkId: String?,
    generatedLink: String?,
    registeredIds: Set<String>,
    peerHandshakes: Map<String, Long>,
    peerSessionStarts: Map<String, Long>,
    peerEndpoints: Map<String, String>,
    fleetOutage: Boolean,
    fleetOutageSeconds: Long,
    knownClientCount: Int,
    onlineClientCount: Int,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onHideServer: (String) -> Unit,
    onUnhideServer: (String) -> Unit,
    onBlock: (String) -> Unit,
    onUnblock: (String) -> Unit,
    onRevoke: (String) -> Unit,
    onExtend: (String, Int) -> Unit,
    onBlockMany: (List<String>) -> Unit,
    onRevokeMany: (List<String>) -> Unit,
    onExtendMany: (List<String>, Int) -> Unit,
    onCreate: (String, Int, String, String) -> Unit,
    onGenerateId: () -> String,
    onGenerateLink: (AdminClient) -> Unit,
    onReregister: (String) -> Unit,
    onBounce: (String) -> Unit,
    onCopyLink: () -> Unit,
    onDismissGeneratedLink: () -> Unit,
    onClearError: () -> Unit,
    onClearNotice: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showExtendDialog by remember { mutableStateOf<String?>(null) }
    /** Массовое продление выбранных клиентов. */
    var showBulkExtendDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedServers by remember { mutableStateOf(setOf<String>()) }
    /** Выбранные для массовых действий клиенты. */
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var statusFilter by remember { mutableStateOf(StatusFilter.ALL) }
    var sortMode by remember { mutableStateOf(SortMode.DEFAULT) }
    /** Свёрнута ли панель «фильтр и сортировка» (чтобы не занимала место списка). */
    var showFilterBar by remember { mutableStateOf(true) }
    /** Развёрнут ли ряд скрытых серверов (по умолчанию свёрнут — не занимает места). */
    var showHiddenServers by remember { mutableStateOf(false) }
    /** Развёрнут ли список серверов (по умолчанию свёрнут — только кнопка). */
    var showServers by remember { mutableStateOf(false) }

    /**
     * Все опции фильтра: все настроенные серверы + серверы, встретившиеся у клиентов
     * (если пришли из зеркала/рекавери до того, как сервер попал в snapshot).
     * Новые серверы попадают сюда автоматически после добавления + «Обновить».
     */
    val allServerOptions = remember(clients, servers) {
        val byId = linkedMapOf<String, String>()
        servers.forEach { s -> byId[s.id] = serverOptionLabel(s.name, s.address) }
        clients.forEach { c ->
            if (c.serverId.isNotBlank()) {
                byId.putIfAbsent(c.serverId, serverOptionLabel(c.serverName, c.serverAddress))
            }
        }
        if (clients.any { it.serverId.isBlank() }) {
            byId[NO_SERVER_ID] = "Без сервера"
        }
        byId.toList()
    }

    /** Видимые чипы: скрытые серверы убраны, но их клиенты остаются в списке. */
    val visibleServerOptions =
        allServerOptions.filter { (serverId, _) -> serverId !in hiddenServerIds }

    /** Скрытые серверы — отдельный ряд, оттуда их можно вернуть. */
    val hiddenServerOptions =
        allServerOptions.filter { (serverId, _) -> serverId in hiddenServerIds && serverId != NO_SERVER_ID }

    // Выборка auto-почищается: исчезнувший/скрытый сервер не держит невидимый фильтр.
    LaunchedEffect(visibleServerOptions) {
        val available = visibleServerOptions.map { it.first }.toSet()
        if (selectedServers.any { it !in available }) {
            selectedServers = selectedServers intersect available
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Панель администратора") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.arrow_back_24px), "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { exportClientsCsv(context, clients) }) {
                        Icon(painterResource(R.drawable.cloud_download_24px), "Экспорт клиентов")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(painterResource(R.drawable.refresh_24px), "Обновить")
                    }
                    TextButton(onClick = onLogout) {
                        Text("Выйти")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(painterResource(R.drawable.add_24px), "Добавить клиента")
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (fleetOutage) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.error_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            "Полный обрыв флотилии: все $knownClientCount известных клиентов офлайн уже " +
                                "${fleetOutageSeconds / 60} мин. Проверьте серверы.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (clients.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Клиентов пока нет",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Button(onClick = { showCreateDialog = true }) {
                        Text("Создать первого клиента")
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.lg)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Поиск по имени клиента") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.sm))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val serverLabel = if (selectedServers.isEmpty()) "Серверы: все"
                        else "Серверы: ${selectedServers.size} из ${visibleServerOptions.size}"
                        FilledTonalButton(
                            onClick = { showServers = !showServers },
                            shape = MaterialTheme.shapes.large,
                            contentPadding = PaddingValues(
                                horizontal = Spacing.md,
                                vertical = Spacing.sm
                            ),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Text(serverLabel)
                            Spacer(Modifier.width(Spacing.sm))
                            Icon(
                                painterResource(
                                    if (showServers) R.drawable.expand_less_24px
                                    else R.drawable.expand_more_24px
                                ),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (selectedServers.isNotEmpty()) {
                            TextButton(onClick = { selectedServers = emptySet() }) {
                                Text("Сбросить")
                            }
                        }
                    }
                    if (showServers) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                        visibleServerOptions.forEach { (serverId, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val selected = serverId in selectedServers
                                FilledTonalButton(
                                    onClick = {
                                        selectedServers = if (selected) {
                                            selectedServers - serverId
                                        } else {
                                            selectedServers + serverId
                                        }
                                    },
                                    shape = MaterialTheme.shapes.large,
                                    contentPadding = PaddingValues(
                                        horizontal = Spacing.md,
                                        vertical = Spacing.sm
                                    ),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = if (selected) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        contentColor = if (selected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                ) {
                                    Text(label)
                                }
                                if (serverId != NO_SERVER_ID) {
                                    IconButton(
                                        onClick = { onHideServer(serverId) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.visibility_off_24px),
                                            contentDescription = "Скрыть сервер",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    }
                    if (hiddenServerOptions.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.xs))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showHiddenServers = !showHiddenServers }) {
                                Icon(
                                    painterResource(
                                        if (showHiddenServers) R.drawable.visibility_24px
                                        else R.drawable.visibility_off_24px
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (showHiddenServers) "Скрыть"
                                    else "Показать скрытые (${hiddenServerOptions.size})"
                                )
                            }
                        }
                        if (showHiddenServers) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                hiddenServerOptions.forEach { (serverId, label) ->
                                    FilterChip(
                                        selected = false,
                                        onClick = { onUnhideServer(serverId) },
                                        label = { Text(label) },
                                        trailingIcon = {
                                            Icon(
                                                painterResource(R.drawable.visibility_24px),
                                                contentDescription = "Вернуть сервер",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    // Статистика по серверам: онлайн/всего; самый загруженный подсвечен.
                    val serverStats = remember(clients, peerHandshakes) {
                        val nowEpoch = Instant.now().epochSecond
                        clients.groupBy { c -> c.serverId }
                            .map { (id, list) ->
                                val label = list.first().let { c ->
                                    c.serverName.trim().ifBlank { c.serverAddress.trim() }.ifBlank { id }.ifBlank { "Без сервера" }
                                }
                                ServerStat(
                                    id = id,
                                    label = label,
                                    total = list.size,
                                    online = list.count { c ->
                                        peerHandshakes[c.id]?.let { hs -> nowEpoch - hs < ONLINE_WINDOW_SECONDS } == true
                                    }
                                )
                            }
                            .sortedWith(compareByDescending<ServerStat> { it.online }.thenByDescending { it.total })
                    }
                    val busiest = serverStats.maxWithOrNull(
                        compareBy<ServerStat> { it.online }.thenBy { it.total }
                    )
                    if (serverStats.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            serverStats.forEach { stat ->
                                val isBusiest = stat.online > 0 && busiest?.id == stat.id && busiest.online == stat.online
                                Surface(
                                    shape = MaterialTheme.shapes.large,
                                    color = if (isBusiest) MaterialTheme.colorScheme.tertiaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = if (isBusiest) MaterialTheme.colorScheme.onTertiaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                ) {
                                    Text(
                                        "${stat.label}: ${stat.online}/${stat.total}" +
                                            (if (isBusiest) " · загружен" else ""),
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(
                                            horizontal = Spacing.md,
                                            vertical = Spacing.sm
                                        )
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(Spacing.sm))
                    }

                    val filtered = remember(clients, searchQuery, selectedServers, statusFilter, sortMode) {
                        val now = Instant.now()
                        val base = clients.filter { c ->
                            val nameOk = searchQuery.isBlank() ||
                                c.name.contains(searchQuery.trim(), ignoreCase = true)
                            val serverOk = selectedServers.isEmpty() ||
                                (if (c.serverId.isBlank()) NO_SERVER_ID in selectedServers
                                else c.serverId in selectedServers)
                            nameOk && serverOk && when (statusFilter) {
                                StatusFilter.ALL -> true
                                StatusFilter.BLOCKED -> c.blocked
                                StatusFilter.EXPIRED -> !AdminViewModel.isUnlimited(c.expires_at) &&
                                    runCatching { Instant.parse(c.expires_at).isBefore(now) }.getOrDefault(false)
                                StatusFilter.ACTIVE -> !c.blocked && !(
                                    !AdminViewModel.isUnlimited(c.expires_at) &&
                                        runCatching { Instant.parse(c.expires_at).isBefore(now) }.getOrDefault(false)
                                    )
                            }
                        }
                        when (sortMode) {
                            SortMode.DEFAULT -> base
                            SortMode.NAME -> base.sortedBy { it.name.lowercase() }
                            SortMode.EXPIRY -> base.sortedWith(
                                compareByDescending<AdminClient> { AdminViewModel.isUnlimited(it.expires_at) }
                                    .thenBy {
                                        runCatching { Instant.parse(it.expires_at).toEpochMilli() }
                                            .getOrDefault(Long.MAX_VALUE)
                                    }
                            )
                        }
                    }

                    // Фильтр и сортировка — сворачиваются, чтобы не съедать место списка.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showFilterBar = !showFilterBar }) {
                            Text(
                                if (showFilterBar) "Скрыть фильтр и сортировку"
                                else "Показать фильтр и сортировку"
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                painterResource(
                                    if (showFilterBar) R.drawable.expand_less_24px
                                    else R.drawable.expand_more_24px
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (showFilterBar) {
                        Spacer(Modifier.height(Spacing.xs))

                        // Статус-фильтр: все / активен / заблокирован / истёк.
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            StatusFilter.values().forEach { f ->
                                val selected = f == statusFilter
                                FilledTonalButton(
                                    onClick = { statusFilter = f },
                                    shape = MaterialTheme.shapes.large,
                                    contentPadding = PaddingValues(
                                        horizontal = Spacing.md,
                                        vertical = Spacing.sm
                                    ),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = if (selected) {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        contentColor = if (selected) {
                                            MaterialTheme.colorScheme.onTertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                ) {
                                    Text(f.label)
                                }
                            }
                        }
                        Spacer(Modifier.height(Spacing.sm))

                        // Сортировка + «выбрать все».
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            var showSortMenu by remember { mutableStateOf(false) }
                            TextButton(onClick = { showSortMenu = true }) {
                                Text("Сортировка: ${sortMode.label}")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                SortMode.values().forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m.label) },
                                        onClick = {
                                            sortMode = m
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    selectedIds = if (
                                        filtered.isNotEmpty() && selectedIds.size == filtered.size
                                    ) emptySet() else filtered.map { it.id }.toSet()
                                },
                                enabled = filtered.isNotEmpty()
                            ) {
                                Text(
                                    if (filtered.isNotEmpty() && selectedIds.size == filtered.size)
                                        "Снять выбор" else "Выбрать все"
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))

                    // Массовые действия.
                    if (selectedIds.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(Spacing.md)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Выбрано: ${selectedIds.size}",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    TextButton(onClick = { selectedIds = emptySet() }) {
                                        Text("Снять")
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                                ) {
                                    TextButton(onClick = { showBulkExtendDialog = true }) {
                                        Text("Продлить")
                                    }
                                    TextButton(
                                        onClick = {
                                            onBlockMany(selectedIds.toList())
                                            selectedIds = emptySet()
                                        }
                                    ) {
                                        Text("Заблокировать")
                                    }
                                    TextButton(
                                        onClick = {
                                            onRevokeMany(selectedIds.toList())
                                            selectedIds = emptySet()
                                        },
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Text("Отозвать")
                                    }
                                }
                            }
                        }
                    }

                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Ничего не найдено",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                top = Spacing.md,
                                bottom = 80.dp
                            )
                        ) {
                            items(filtered, key = { it.serverId + "/" + it.id }) { client ->
                                ClientCard(
                                    client = client,
                                    selected = client.id in selectedIds,
                                    onToggleSelect = {
                                        selectedIds = if (client.id in selectedIds) {
                                            selectedIds - client.id
                                        } else {
                                            selectedIds + client.id
                                        }
                                    },
                                    isGeneratingLink = generatedLinkId == client.id,
                                    isRegistered = client.id in registeredIds,
                                    handshakeEpoch = peerHandshakes[client.id],
                                    sessionStartEpoch = peerSessionStarts[client.id],
                                    endpoint = peerEndpoints[client.id],
                                    onBlock = { onBlock(client.id) },
                                    onUnblock = { onUnblock(client.id) },
                                    onRevoke = { onRevoke(client.id) },
                                    onExtend = { showExtendDialog = client.id },
                                    onGenerateLink = { onGenerateLink(client) },
                                    onReregister = { onReregister(client.id) },
                                    onBounce = { onBounce(client.id) }
                                )
                            }
                        }
                    }
                }
            }

            if (error != null) {
                AlertDialog(
                    onDismissRequest = onClearError,
                    title = { Text("Ошибка") },
                    text = { Text(error) },
                    confirmButton = {
                        TextButton(onClick = onClearError) {
                            Text("OK")
                        }
                    }
                )
            }

            if (notice != null) {
                AlertDialog(
                    onDismissRequest = onClearNotice,
                    title = { Text("Готово") },
                    text = { Text(notice) },
                    confirmButton = {
                        TextButton(onClick = onClearNotice) {
                            Text("OK")
                        }
                    }
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateClientDialog(
            onDismiss = { showCreateDialog = false },
            onGenerateId = onGenerateId,
            onCreate = { name, durationMin, clientId, vkLink ->
                onCreate(name, durationMin, clientId, vkLink)
                showCreateDialog = false
            }
        )
    }

    showExtendDialog?.let { clientId ->
        ExtendClientDialog(
            onDismiss = { showExtendDialog = null },
            onExtend = { durationMin ->
                onExtend(clientId, durationMin)
                showExtendDialog = null
            }
        )
    }

    if (showBulkExtendDialog) {
        ExtendClientDialog(
            onDismiss = { showBulkExtendDialog = false },
            onExtend = { durationMin ->
                onExtendMany(selectedIds.toList(), durationMin)
                selectedIds = emptySet()
                showBulkExtendDialog = false
            }
        )
    }

    if (generatedLink != null) {
        GeneratedLinkDialog(
            link = generatedLink,
            onDismiss = onDismissGeneratedLink,
            onCopy = {
                onCopyLink()
                onDismissGeneratedLink()
            },
            onShare = {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, generatedLink)
                }
                context.startActivity(Intent.createChooser(share, "Отправить ссылку"))
                onDismissGeneratedLink()
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClientCard(
    client: AdminClient,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    isGeneratingLink: Boolean,
    isRegistered: Boolean,
    handshakeEpoch: Long?,
    sessionStartEpoch: Long?,
    endpoint: String?,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onRevoke: () -> Unit,
    onExtend: () -> Unit,
    onGenerateLink: () -> Unit,
    onReregister: () -> Unit,
    onBounce: () -> Unit
) {
    val expiresAt = try {
        Instant.parse(client.expires_at).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    } catch (e: Exception) {
        client.expires_at
    }

    val createdAt = try {
        Instant.parse(client.created_at).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    } catch (e: Exception) {
        client.created_at
    }

    val isUnlimited = AdminViewModel.isUnlimited(client.expires_at)
    val isExpired = !isUnlimited && try {
        Instant.parse(client.expires_at).isBefore(Instant.now())
    } catch (e: Exception) {
        false
    }
    val (daysText, daysLeft) = daysLeftText(client.expires_at)
    val expiringSoon = !isExpired && daysLeft <= EXPIRY_WARN_DAYS

    val nowEpoch = Instant.now().epochSecond
    val handshakeAge = handshakeEpoch?.let { nowEpoch - it }
    val isOnline = handshakeAge != null && handshakeAge < ONLINE_WINDOW_SECONDS
    val sessionSeconds = if (isOnline && sessionStartEpoch != null) {
        (nowEpoch - sessionStartEpoch).coerceAtLeast(0)
    } else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                client.blocked -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                isExpired -> MaterialTheme.colorScheme.surfaceVariant
                expiringSoon -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.size(36.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        client.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            isUnlimited -> "Действует: бессрочно"
                            isExpired -> "Истёк: $expiresAt"
                            else -> "Действует до: $expiresAt $daysText"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            client.blocked -> MaterialTheme.colorScheme.error
                            isExpired -> MaterialTheme.colorScheme.error
                            expiringSoon -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        "Создан: ${createdAt.ifBlank { "—" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (client.serverName.isNotBlank() || client.serverAddress.isNotBlank()) {
                        Text(
                            buildServerLabel(client),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            "ID: ${client.id}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (client.vk_link.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "VK: ${client.vk_link}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (handshakeEpoch != null) {
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = if (isOnline) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = CircleShape
                                    )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    isOnline -> "Онлайн · сессия ${formatSession(sessionSeconds ?: 0)}"
                                    handshakeAge != null -> {
                                        val min = handshakeAge / 60
                                        if (min > 0) {
                                            val h = min / 60
                                            if (h > 0) "Был в сети $h ч ${min % 60} мин назад"
                                            else "Был в сети $min мин назад"
                                        } else {
                                            "Был в сети ${handshakeAge} сек назад"
                                        }
                                    }
                                    else -> "Офлайн"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isOnline) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (handshakeEpoch != null && endpoint != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "IP: ${
                                hostFromEndpoint(endpoint) ?: endpoint
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!isRegistered) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "На сервере НЕ зарегистрирован",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = when {
                        client.blocked -> MaterialTheme.colorScheme.error
                        client.active -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(
                                when {
                                    client.blocked -> R.drawable.error_24px
                                    client.active -> R.drawable.check_circle_24px
                                    else -> R.drawable.error_24px
                                }
                            ),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (!client.blocked && !isExpired) {
                    TextButton(onClick = onBlock) {
                        Text("Заблокировать")
                    }
                }
                if (client.blocked && !isExpired) {
                    TextButton(onClick = onUnblock) {
                        Text("Разблокировать")
                    }
                }
                TextButton(onClick = onExtend) {
                    Text("Продлить")
                }
                TextButton(onClick = onBounce) {
                    Text("Сброс сессии")
                }
                TextButton(
                    onClick = onRevoke,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Отозвать")
                }
            }

            Spacer(Modifier.height(Spacing.xs))

            if (!isRegistered) {
                OutlinedButton(
                    onClick = onReregister,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Зарегистрировать на сервере")
                }
                Spacer(Modifier.height(Spacing.xs))
            }

            OutlinedButton(
                onClick = onGenerateLink,
                enabled = !isGeneratingLink,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isGeneratingLink) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Поделиться ссылкой")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationSelector(
    selectedMinutes: Int,
    onSelect: (Int) -> Unit
) {
    var customDays by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            PRESET_DURATIONS_MIN.forEach { (min, label) ->
                FilterChip(
                    selected = selectedMinutes == min,
                    onClick = { onSelect(min) },
                    label = { Text(label) }
                )
            }
        }

        // Своё количество дней: ввод 1..9999 пересчитывается в минуты.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            OutlinedTextField(
                value = customDays,
                onValueChange = { input ->
                    customDays = input.filter { it.isDigit() }.take(4)
                    customDays.toIntOrNull()?.let { days ->
                        if (days in 1..9999) onSelect(days * 1440)
                    }
                },
                label = { Text("Своё количество дней") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CreateClientDialog(
    onDismiss: () -> Unit,
    onGenerateId: () -> String,
    onCreate: (name: String, durationMinutes: Int, clientId: String, vkLink: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var durationMin by remember { mutableIntStateOf(1) }
    var clientId by remember { mutableStateOf("") }
    var vkLink by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Создать клиента") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя клиента") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("ID клиента (32 hex)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedButton(
                    onClick = { clientId = onGenerateId() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Сгенерировать ID")
                }

                OutlinedTextField(
                    value = vkLink,
                    onValueChange = { vkLink = it },
                    label = { Text("Ссылка на звонок ВК (необязательно)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(
                    "Срок подписки",
                    style = MaterialTheme.typography.titleSmall
                )
                DurationSelector(
                    selectedMinutes = durationMin,
                    onSelect = { durationMin = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, durationMin, clientId, vkLink) },
                enabled = name.isNotBlank() && clientId.length == 32
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun GeneratedLinkDialog(
    link: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ссылка для шаринга") },
        text = {
            Column {
                Text(
                    "Одноразовая ссылка для этого клиента:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(Spacing.sm))
                SelectionContainer {
                    Text(
                        link,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "Ссылку можно использовать только один раз.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onShare) {
                    Text("Поделиться")
                }
                TextButton(onClick = onCopy) {
                    Text("Копировать")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

@Composable
private fun ExtendClientDialog(
    onDismiss: () -> Unit,
    onExtend: (Int) -> Unit
) {
    var durationMin by remember { mutableIntStateOf(1440) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Продлить подписку") },
        text = {
            Column {
                Text(
                    "На сколько продлить?",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(Spacing.md))
                DurationSelector(
                    selectedMinutes = durationMin,
                    onSelect = { durationMin = it }
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "Новый срок подхватится клиентом сам: при следующем подключении приложение запросит его у сервера.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onExtend(durationMin) }) {
                Text("Продлить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}