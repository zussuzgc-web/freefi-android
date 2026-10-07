package com.freeturn.app.ui.navigation

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import com.freeturn.app.ui.screens.clientsetup.ClientSetupScreen
import com.freeturn.app.ui.screens.connectionmode.ConnectionModeScreen
import com.freeturn.app.ui.screens.servermanagement.ServerManagementScreen
import com.freeturn.app.ui.screens.serverdetail.ServerDetailScreen
import com.freeturn.app.ui.screens.admin.AdminDashboardScreen
import com.freeturn.app.ui.screens.admin.AdminLoginScreen
import com.freeturn.app.ui.screens.settings.AboutScreen
import com.freeturn.app.ui.screens.settings.AdvancedScreen
import com.freeturn.app.ui.screens.settings.AppScreen
import com.freeturn.app.ui.screens.settings.NerdScreen
import com.freeturn.app.ui.screens.settings.ServersListScreen
import com.freeturn.app.ui.screens.settings.SettingsScreen
import com.freeturn.app.ui.screens.sshsetup.SshSetupScreen
import com.freeturn.app.viewmodel.admin.AdminViewModel
import com.freeturn.app.viewmodel.proxy.ProxyViewModel
import com.freeturn.app.viewmodel.server.ServerViewModel
import com.freeturn.app.viewmodel.settings.SettingsViewModel

/** Вкладка "Настройки": Настройки -> Серверы -> [сервер] -> подключение/режим/сервер -> SSH. */
internal fun NavGraphBuilder.settingsGraph(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
    proxyViewModel: ProxyViewModel,
    serverViewModel: ServerViewModel
) {
    navigation<SettingsGraph>(startDestination = Settings) {
        composable<AdminLogin> {
            val adminViewModel: AdminViewModel = org.koin.androidx.compose.koinViewModel()
            val state by adminViewModel.state.collectAsStateWithLifecycle()
            AdminLoginScreen(
                error = state.error,
                onLogin = { password ->
                    if (adminViewModel.login(password)) {
                        navController.navigate(AdminDashboard)
                    }
                },
                onBack = { navController.popBackStack() },
                onClearError = { adminViewModel.clearError() }
            )
        }

        composable<AdminDashboard> {
            val adminViewModel: AdminViewModel = org.koin.androidx.compose.koinViewModel()
            val state by adminViewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { adminViewModel.startPolling() }
            DisposableEffect(Unit) {
                onDispose { adminViewModel.stopPolling() }
            }
            AdminDashboardScreen(
                clients = state.clients,
                servers = state.servers,
                hiddenServerIds = state.hiddenServerIds,
                loading = state.loading,
                error = state.error,
                notice = state.notice,
                generatedLinkId = state.generatedLinkId,
                generatedLink = state.generatedLink,
                registeredIds = state.registeredIds,
                peerHandshakes = state.peerHandshakes,
                peerSessionStarts = state.peerSessionStarts,
                peerEndpoints = state.peerEndpoints,
                fleetOutage = state.fleetOutage,
                fleetOutageSeconds = state.fleetOutageSeconds,
                knownClientCount = state.knownClientCount,
                onlineClientCount = state.onlineClientCount,
                onBack = { navController.popBackStack() },
                onRefresh = {
                    adminViewModel.refreshAll()
                },
                onHideServer = { adminViewModel.hideServer(it) },
                onUnhideServer = { adminViewModel.unhideServer(it) },
                onBlock = { adminViewModel.blockClient(it) },
                onUnblock = { adminViewModel.unblockClient(it) },
                onRevoke = { adminViewModel.revokeClient(it) },
                onExtend = { id, days, subtract -> adminViewModel.extendClient(id, days, subtract) },
                onBlockMany = { adminViewModel.blockClients(it) },
                onRevokeMany = { adminViewModel.revokeClients(it) },
                onExtendMany = { ids, days, subtract -> adminViewModel.extendClients(ids, days, subtract) },
                onCreate = { name, days, clientId, vkLink -> adminViewModel.createClient(name, days, clientId, vkLink) },
                onGenerateId = { adminViewModel.generateClientId() },
                onGenerateLink = { adminViewModel.generateShareLink(it) },
                onReregister = { adminViewModel.reregisterClient(it) },
                onBounce = { adminViewModel.bounceClient(it) },
                onCopyLink = { adminViewModel.copyLinkToClipboard() },
                onDismissGeneratedLink = { adminViewModel.dismissGeneratedLink() },
                onClearError = { adminViewModel.clearError() },
                onClearNotice = { adminViewModel.clearNotice() },
                onLogout = {
                    adminViewModel.logout()
                    navController.popBackStack()
                }
            )
        }
        composable<Settings> {
            SettingsScreen(
                onOpenServers = { navController.navigate(ServersList) },
                onOpenApp = { navController.navigate(AppSettings) },
                onOpenAdvanced = { navController.navigate(Advanced) },
                onOpenAbout = { navController.navigate(About) }
            )
        }

        composable<AppSettings> {
            AppScreen(
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable<About> {
            AboutScreen(
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onAdminAccess = { navController.navigate(AdminLogin) }
            )
        }

        composable<Advanced> {
            AdvancedScreen(
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable<ServersList> {
            ServersListScreen(
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenServer = { id -> navController.navigate(ServerDetail(id)) }
            )
        }

        composable<ServerDetail> { entry ->
            val id = entry.toRoute<ServerDetail>().serverId
            ServerDetailScreen(
                serverId = id,
                settingsViewModel = settingsViewModel,
                serverViewModel = serverViewModel,
                onBack = { navController.popBackStack() },
                onOpenConnection = { navController.navigate(ClientSetup(id)) },
                onOpenConnectionMode = { navController.navigate(ConnectionMode(id)) },
                onOpenServerSettings = { navController.navigate(ServerManagement(id)) },
                onOpenNerdInfo = { navController.navigate(NerdInfo(id)) },
                onOpenSshSetup = { navController.navigate(SshSetup) },
                onCloned = { newId -> navController.navigate(ServerDetail(newId)) }
            )
        }

        composable<NerdInfo> { entry ->
            val id = entry.toRoute<NerdInfo>().serverId
            NerdScreen(
                serverId = id,
                settingsViewModel = settingsViewModel,
                serverViewModel = serverViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable<ConnectionMode> { entry ->
            val id = entry.toRoute<ConnectionMode>().serverId
            ConnectionModeScreen(
                settingsViewModel = settingsViewModel,
                proxyViewModel = proxyViewModel,
                serverId = id,
                onBack = { navController.popBackStack() }
            )
        }

        composable<ServerManagement> { entry ->
            val id = entry.toRoute<ServerManagement>().serverId
            ServerManagementScreen(
                serverViewModel = serverViewModel,
                settingsViewModel = settingsViewModel,
                serverId = id,
                onBack = { navController.popBackStack() },
                onEditConnection = { navController.navigate(SshSetup) }
            )
        }

        composable<ClientSetup> { entry ->
            val id = entry.toRoute<ClientSetup>().serverId
            ClientSetupScreen(
                settingsViewModel = settingsViewModel,
                serverViewModel = serverViewModel,
                serverId = id,
                onBack = { navController.popBackStack() }
            )
        }

        composable<SshSetup> {
            SshSetupScreen(
                serverViewModel = serverViewModel,
                settingsViewModel = settingsViewModel,
                // Форма поверх настроек сервера - после успеха возвращаемся назад.
                onConnected = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
