@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.domain.UpdateState
import com.freeturn.app.ui.components.BusyProgressIndicator
import com.freeturn.app.ui.components.SettingsBackButton
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.viewmodel.settings.SettingsViewModel

/** "О проекте": hero с лого, версия и необязательное обновление. Скрытое нажатие на логотип 5 раз -> Admin. */
@Composable
fun AboutScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
    onAdminAccess: () -> Unit = {}
) {
    val appVersion = rememberAppVersion()
    val updateState by settingsViewModel.updateState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var tapCount by remember { mutableIntStateOf(0) }

    // Автопроверка при открытии: если новее версии нет, сразу пишем "установлена последняя".
    LaunchedEffect(Unit) {
        if (updateState is UpdateState.Idle) settingsViewModel.checkForUpdate()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_about)) },
                navigationIcon = { SettingsBackButton(onBack) },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SettingsContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                AboutHero(
                    appVersion = appVersion,
                    onLogoTap = {
                        tapCount++
                        if (tapCount >= 5) {
                            tapCount = 0
                            onAdminAccess()
                        }
                    }
                )
                UpdateSection(
                    state = updateState,
                    onCheck = { settingsViewModel.checkForUpdate() },
                    onDownload = { settingsViewModel.downloadUpdate() },
                    onInstall = { settingsViewModel.installUpdate() },
                    onReset = { settingsViewModel.resetUpdateState() }
                )
            }
        }
    }
}

/** Hero "О проекте": лого, имя, версия-пилюля. */
@Composable
private fun AboutHero(appVersion: String, onLogoTap: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md, bottom = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = MaterialShapes.Cookie9Sided.toShape(),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .size(112.dp)
                .clickable { onLogoTap() }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Image(
                    painterResource(R.drawable.logo_freefi),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.turn_proxy_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                "v$appVersion",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
            )
        }
    }
}

/**
 * Блок "Обновление" внутри "О проекте": необязательно, доступно всегда.
 * Проверка при открытии уже запущена; здесь - состояние и управление.
 */
@Composable
private fun UpdateSection(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onReset: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                stringResource(R.string.update_title),
                style = MaterialTheme.typography.titleMedium
            )
            when (val s = state) {
                is UpdateState.Idle -> OutlinedButton(onClick = onCheck) {
                    Text(stringResource(R.string.update_check))
                }

                is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.update_checking))
                }

                is UpdateState.NoUpdate -> Text(
                    stringResource(R.string.update_no_update),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is UpdateState.Available -> Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Text(
                        stringResource(R.string.update_available, s.version),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Button(onClick = onDownload) {
                            Text(stringResource(R.string.update_download))
                        }
                        TextButton(onClick = onReset) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }

                is UpdateState.Downloading -> Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Text(
                        stringResource(R.string.update_downloading, s.progress),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    BusyProgressIndicator(progress = { s.progress / 100f })
                }

                is UpdateState.ReadyToInstall -> Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Button(onClick = onInstall) {
                        Text(stringResource(R.string.update_install))
                    }
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.cancel))
                    }
                }

                is UpdateState.Error -> Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Text(
                        stringResource(R.string.update_error, s.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(onClick = onCheck) {
                        Text(stringResource(R.string.update_check))
                    }
                }
            }
        }
    }
}