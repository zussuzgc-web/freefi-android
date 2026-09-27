@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.home

import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.freeturn.app.R
import com.freeturn.app.data.config.SplitTunnelMode
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.domain.ping.PingState
import com.freeturn.app.domain.share.MySubscription
import com.freeturn.app.ui.screens.splittunnel.SplitTunnelModal
import com.freeturn.app.viewmodel.proxy.ProxyViewModel
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.freeturn.app.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Главный экран (собирает состояния, держит системные ланчеры и чистые компоненты). */
@Composable
fun HomeScreen(
    settingsViewModel: SettingsViewModel,
    proxyViewModel: ProxyViewModel,
    onOpenServerSettings: (String) -> Unit,
    onAddServer: () -> Unit
) {
    val context = LocalContext.current
    val status by proxyViewModel.status.collectAsStateWithLifecycle()
    val uptimeText = rememberProxyUptime(status.connectedSince)
    val clientConfig by settingsViewModel.clientConfig.collectAsStateWithLifecycle()
    val updateState by settingsViewModel.updateState.collectAsStateWithLifecycle()
    val suppressUpdatePrompt by settingsViewModel.suppressUpdatePrompt.collectAsStateWithLifecycle()
    val privacyMode by settingsViewModel.privacyMode.collectAsStateWithLifecycle()
    val seasonalDecor by settingsViewModel.seasonalDecor.collectAsStateWithLifecycle()
    val serversSnapshot by settingsViewModel.serversSnapshot.collectAsStateWithLifecycle()
    val subscription by settingsViewModel.subscription.collectAsStateWithLifecycle()
    val serverPings by settingsViewModel.serverPings.collectAsStateWithLifecycle()

    // Автообновление срока подписки: сразу при возврате на экран (из админки
    // после создания/продления подписки) и периодически, пока экран виден.
    // Раньше обновление шло только при старте, переключении сервера и тапе
    // тумблера - продлил подписку -> надпись оставалась застывшей.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                settingsViewModel.refreshSubscription()
                delay(HomeSubscriptionRefreshMillis)
            }
        }
    }

    RequestStartupPermissions(settingsViewModel)

    val showSplitSheet = rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Нижний лист серверов (всегда виден).
    val sheetScaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            enabledValues = setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded)
        )
    )
    // Запрос VPN-разрешения для WireGuard.
    val wireGuardPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (VpnService.prepare(context) == null) {
            proxyViewModel.start()
        }
    }

    fun startProxyWithTunnel() {
        if (clientConfig.wireGuardActive) {
            val vpnIntent: Intent? = VpnService.prepare(context)
            if (vpnIntent != null) {
                wireGuardPermissionLauncher.launch(vpnIntent)
                return
            }
        }
        proxyViewModel.start()
    }

    val sheetColor = MaterialTheme.colorScheme.surfaceContainerLow

    // Без серверов: Scaffold с приглашением добавить. Не загружен: пустое тело.
    when {
        !serversSnapshot.loaded ->
            Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding))
            }

        serversSnapshot.list.isEmpty() ->
            Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                HomeEmptyState(
                    onAddServer = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onAddServer()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }

        else -> BottomSheetScaffold(
            scaffoldState = sheetScaffoldState,
            sheetPeekHeight = 112.dp,
            sheetContainerColor = sheetColor,
            sheetContent = {
                ServersSheetContent(
                    snapshot = serversSnapshot,
                    privacyMode = privacyMode,
                    callLink = clientConfig.vkLink,
                    // Правка ссылки только пока прокси стоит (новая комната = реконнект).
                    callLinkLocked = status.busy,
                    onApplyServer = { id ->
                        settingsViewModel.applyServer(id)
                        scope.launch { sheetScaffoldState.bottomSheetState.partialExpand() }
                    },
                    onOpenServerSettings = { id ->
                        // Сворачиваем лист перед уходом в настройки.
                        scope.launch { sheetScaffoldState.bottomSheetState.partialExpand() }
                        onOpenServerSettings(id)
                    },
                    onSaveCallLink = { settingsViewModel.setActiveVkLink(it) },
                    pings = serverPings,
                    onCheckAllPings = settingsViewModel::checkAllPings
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = SettingsContentMaxWidth)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    ConnectionHero(
                        status = status,
                        uptimeText = uptimeText,
                        decorEnabled = seasonalDecor,
                        onToggle = {
                            // Любая непокоящаяся фаза (включая капчу и старт) - остановка.
                            if (status.busy) {
                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_OFF)
                                proxyViewModel.stop()
                            } else {
                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                startProxyWithTunnel()
                            }
                            settingsViewModel.refreshSubscription()
                        }
                    )
                    SubscriptionHint(
                        subscription = subscription,
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
                    val activeId = serversSnapshot.activeId
                    if (activeId != null) {
                        PingStatusButton(
                            state = serverPings[activeId],
                            onClick = { settingsViewModel.checkServerPing(activeId) },
                            modifier = Modifier.padding(top = Spacing.xs)
                        )
                    }
                }

                // Индикатор split-tunneling (только для WG).
                if (clientConfig.wireGuardActive) {
                    SplitTunnelButton(
                        splitActive = clientConfig.splitTunnelMode != SplitTunnelMode.ALL,
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            showSplitSheet.value = true
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = Spacing.md)
                    )
                }
            }
        }
    }

    if (showSplitSheet.value) {
        SplitTunnelModal(
            mode = clientConfig.splitTunnelMode,
            apps = clientConfig.splitTunnelApps,
            locked = status.busy,
            onModeChange = settingsViewModel::setSplitTunnelMode,
            onAppsChange = settingsViewModel::setSplitTunnelApps,
            onDismiss = { showSplitSheet.value = false },
            containerColor = sheetColor
        )
    }

    UpdateDialogs(
        updateState = updateState,
        suppressAvailablePrompt = suppressUpdatePrompt,
        onDownload = settingsViewModel::downloadUpdate,
        onInstall = settingsViewModel::installUpdate,
        onReset = settingsViewModel::resetUpdateState
    )
}

private const val SubscriptionWarnDays = 7L

/** Период живого опроса срока подписки на главном экране. */
private const val HomeSubscriptionRefreshMillis = 60_000L

private val SubscriptionDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    .withZone(ZoneId.systemDefault())

private fun daysWord(n: Long): String = when {
    n % 10L == 1L && n % 100L != 11L -> "день"
    n % 10L in 2L..4L && n % 100L !in 12L..14L -> "дня"
    else -> "дней"
}

/** Напоминание о подписке на главном экране (дата окончания + остаток дней). */
@Composable
private fun SubscriptionHint(subscription: MySubscription?, modifier: Modifier = Modifier) {
    if (subscription == null || !subscription.found) return
    val nowSec = Instant.now().epochSecond
    val unlimited = subscription.expiresEpoch == null && !subscription.expired
    if (unlimited) return
    val expired = subscription.expired || (subscription.expiresEpoch ?: 0L) <= nowSec
    val daysLeft = subscription.expiresEpoch?.let { ((it - nowSec + 86399L) / 86400L).coerceAtLeast(0L) }
    val warn = !expired && (daysLeft ?: 0L) <= SubscriptionWarnDays
    val color = when {
        expired -> MaterialTheme.colorScheme.error
        warn -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = buildString {
        if (expired) {
            append("Срок подписки истёк")
        } else {
            val until = subscription.expiresEpoch ?: return@buildString
            append("Подписка до ")
            append(SubscriptionDateFormat.format(Instant.ofEpochSecond(until)))
            if (daysLeft != null) {
                append(" · осталось ")
                append(daysLeft)
                append(' ')
                append(daysWord(daysLeft))
            }
        }
    }
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = modifier
    )
}

/** Быстрая проверка пинга активного сервера с главного экрана. */
@Composable
private fun PingStatusButton(
    state: PingState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(onClick = onClick, modifier = modifier) {
        when (state) {
            PingState.Checking -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp).width(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.ping_checking))
            }
            is PingState.Ok -> {
                Icon(
                    painterResource(R.drawable.wifi_24px),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.ping_ms, state.ms))
            }
            PingState.Fail -> {
                Icon(
                    painterResource(R.drawable.wifi_24px),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.ping_fail), color = MaterialTheme.colorScheme.error)
            }
            null -> {
                Icon(
                    painterResource(R.drawable.wifi_24px),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.ping_check))
            }
        }
    }
}
