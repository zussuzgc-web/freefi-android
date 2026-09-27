@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.components.AccentColorPicker
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsBackButton
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.components.SettingsGroup
import com.freeturn.app.ui.components.SettingsGroupItem
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.viewmodel.settings.BackupEvent
import com.freeturn.app.viewmodel.settings.RestoreFailReason
import com.freeturn.app.viewmodel.settings.SettingsViewModel

/** "РџСЂРёР»РѕР¶РµРЅРёРµ": РёРЅС‚РµСЂС„РµР№СЃРЅС‹Рµ РЅР°СЃС‚СЂРѕР№РєРё Рё РїРѕРґРєР»СЋС‡РµРЅРёРµ. */
@Composable
fun AppScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val privacyMode by settingsViewModel.privacyMode.collectAsStateWithLifecycle()
    val dynamicTheme by settingsViewModel.dynamicTheme.collectAsStateWithLifecycle()
    val seasonalDecor by settingsViewModel.seasonalDecor.collectAsStateWithLifecycle()
    val autoConnect by settingsViewModel.autoConnect.collectAsStateWithLifecycle()
    val accentColor by settingsViewModel.accentColor.collectAsStateWithLifecycle()
    val backgroundColor by settingsViewModel.backgroundColor.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }

    // Р”РµР»РµРЅРёРµ В«РРЅС‚РµСЂС„РµР№СЃВ»: СЃС‚СЂРѕРєРё СЃРІРѕРёС… С†РІРµС‚РѕРІ РїРѕСЏРІР»СЏСЋС‚СЃСЏ С‚РѕР»СЊРєРѕ РїСЂРё РІС‹РєР»СЋС‡РµРЅРЅРѕР№
    // РґРёРЅР°РјРёС‡РµСЃРєРѕР№ С‚РµРјРµ, РѕСЃС‚Р°Р»СЊРЅС‹Рµ СЃС‚СЂРѕРєРё Р¶РёРІСѓС‚ РІСЃРµРіРґР°.
    val interfaceCount = if (dynamicTheme) 3 else 5

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_app)) },
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
                SectionLabel(stringResource(R.string.app_section_interface))
                SettingsGroup {
                    SettingsGroupItem(0, interfaceCount) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.privacy_mode_title),
                            subtitle = stringResource(R.string.privacy_mode_desc),
                            iconRes = R.drawable.visibility_off_24px,
                            checked = privacyMode,
                            onCheckedChange = { settingsViewModel.setPrivacyMode(it) }
                        )
                    }
                    SettingsGroupItem(1, interfaceCount) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.dynamic_theme_title),
                            subtitle = stringResource(R.string.dynamic_theme_desc),
                            iconRes = R.drawable.palette_24px,
                            checked = dynamicTheme,
                            onCheckedChange = { settingsViewModel.setDynamicTheme(it) }
                        )
                    }
                    if (!dynamicTheme) {
                        SettingsGroupItem(2, interfaceCount) {
                            ColorSwatchRow(
                                colorValue = accentColor,
                                title = stringResource(R.string.accent_color_title),
                                description = stringResource(R.string.accent_color_desc),
                                iconRes = R.drawable.palette_24px,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    colorTarget = ColorTarget.Accent
                                }
                            )
                        }
                        SettingsGroupItem(3, interfaceCount) {
                            ColorSwatchRow(
                                colorValue = backgroundColor,
                                title = stringResource(R.string.background_color_title),
                                description = stringResource(R.string.background_color_desc),
                                iconRes = R.drawable.format_color_fill_24px,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    colorTarget = ColorTarget.Background
                                }
                            )
                        }
                    }
                    SettingsGroupItem(if (dynamicTheme) 2 else 4, interfaceCount) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.seasonal_decor_title),
                            subtitle = stringResource(R.string.seasonal_decor_desc),
                            iconRes = R.drawable.eco_outlined_24px,
                            checked = seasonalDecor,
                            onCheckedChange = { settingsViewModel.setSeasonalDecor(it) }
                        )
                    }
                }

                SectionLabel(stringResource(R.string.app_section_connection))
                SettingsGroup {
                    SettingsGroupItem(0, 2) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.auto_connect_title),
                            subtitle = stringResource(R.string.auto_connect_desc),
                            iconRes = R.drawable.vpn_key_24px,
                            checked = autoConnect,
                            onCheckedChange = { settingsViewModel.setAutoConnect(it) }
                        )
                    }
                    SettingsGroupItem(1, 2) {
                        BatteryOptimizationRow()
                    }
                }

            }
        }
    }

    colorTarget?.let { target ->
        val currentColor = when (target) {
            ColorTarget.Accent -> accentColor
            ColorTarget.Background -> backgroundColor
        }
        var pendingColor by remember(target) { mutableIntStateOf(currentColor) }
        AlertDialog(
            onDismissRequest = { colorTarget = null },
            title = {
                Text(
                    stringResource(
                        if (target == ColorTarget.Accent) R.string.accent_color_dialog_title
                        else R.string.background_color_dialog_title
                    )
                )
            },
            text = {
                AccentColorPicker(
                    seedArgb = currentColor,
                    saturationLabel = stringResource(R.string.accent_color_saturation),
                    brightnessLabel = stringResource(R.string.accent_color_brightness),
                    onChanged = { pendingColor = it }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (target) {
                            ColorTarget.Accent -> settingsViewModel.setAccentColor(pendingColor)
                            ColorTarget.Background -> settingsViewModel.setBackgroundColor(pendingColor)
                        }
                        colorTarget = null
                    }
                ) {
                    Text(stringResource(R.string.accent_color_apply))
                }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            when (target) {
                                ColorTarget.Accent -> settingsViewModel.setAccentColor(0)
                                ColorTarget.Background -> settingsViewModel.setBackgroundColor(0)
                            }
                            colorTarget = null
                        }
                    ) {
                        Text(stringResource(R.string.accent_color_standard))
                    }
                    TextButton(onClick = { colorTarget = null }) {
                        Text(stringResource(R.string.accent_color_cancel))
                    }
                }
            }
        )
    }
}

/** Р¦РµР»СЊ РґРёР°Р»РѕРіР° РІС‹Р±РѕСЂР° С†РІРµС‚Р°: РєР°РєСѓСЋ РЅР°СЃС‚СЂРѕР№РєСѓ РјРµРЅСЏРµРј. */
private enum class ColorTarget { Accent, Background }

/**
 * РЎС‚СЂРѕРєР° РЅР°СЃС‚СЂРѕР№РєРё С†РІРµС‚Р° (РїСЂРёР»РѕР¶РµРЅРёРµ/С„РѕРЅ): РёРєРѕРЅРєР°-СЃРІРѕС‚С‡ С‚РµРєСѓС‰РµРіРѕ С†РІРµС‚Р° (РёР»Рё С€С‚Р°С‚РЅР°СЏ,
 * РїРѕРєР° СЃРІРѕР№ РЅРµ РІС‹Р±СЂР°РЅ), РїРѕРґРїРёСЃСЊ В«РЎС‚Р°РЅРґР°СЂС‚РЅС‹Р№В» - С‡С‚РѕР±С‹ Р±С‹Р»Рѕ РІРёРґРЅРѕ, С‡С‚Рѕ С†РІРµС‚ РјРѕР¶РЅРѕ РІРµСЂРЅСѓС‚СЊ.
 */
@Composable
private fun ColorSwatchRow(
    colorValue: Int,
    title: String,
    description: String,
    iconRes: Int,
    onClick: () -> Unit
) {
    val container = if (colorValue == 0) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color(colorValue)
    }
    val tint = if (colorValue == 0) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else if (Color(colorValue).luminance() > 0.5f) {
        Color.Black
    } else {
        Color.White
    }
    SettingsEntryRow(
        iconRes = iconRes,
        title = title,
        subtitle = if (colorValue == 0) {
            description
        } else {
            "#%06X".format(colorValue and 0xFFFFFF)
        },
        iconContainer = container,
        iconTint = tint,
        onClick = onClick
    )
}
@SuppressLint("BatteryLife")
@Composable
private fun BatteryOptimizationRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exempt by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }

    // Р РµС€РµРЅРёРµ РїСЂРёРЅРёРјР°РµС‚СЃСЏ РІ СЃРёСЃС‚РµРјРЅРѕРј СЌРєСЂР°РЅРµ: СЃРѕСЃС‚РѕСЏРЅРёРµ СЃРІРµСЂСЏРµРј РЅР° РєР°Р¶РґРѕРј РІРѕР·РІСЂР°С‚Рµ.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = context.isIgnoringBatteryOptimizations()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // РљР»РёРєР°Р±РµР»СЊРЅР° РІСЃРµРіРґР°: РЅР° OxygenOS В«СѓРјРЅС‹Р№ СЂРµР¶РёРјВ» СЂР°РїРѕСЂС‚СѓРµС‚ РєР°Рє В«РЅРµ РѕРїС‚РёРјРёР·РёСЂСѓРµС‚СЃСЏВ»,
    // С…РѕС‚СЏ РїСЂРѕС†РµСЃСЃ РІ Doze РІСЃС‘ СЂР°РІРЅРѕ Р·Р°РјРѕСЂР°Р¶РёРІР°СЋС‚ - СЂРµС€РµРЅРёРµ РѕСЃС‚Р°С‘С‚СЃСЏ Р·Р° РїРѕР»СЊР·РѕРІР°С‚РµР»РµРј.
    SettingsEntryRow(
        iconRes = R.drawable.vpn_key_24px,
        title = stringResource(R.string.battery_opt_title),
        subtitle = stringResource(
            if (exempt) R.string.battery_opt_on else R.string.battery_opt_off
        ),
        onClick = {
            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
            context.openBatterySettings(exempt)
        }
    )
}

/**
 * [exempt] - РїСЂРёР»РѕР¶РµРЅРёРµ СѓР¶Рµ РІ СЃРїРёСЃРєРµ РёСЃРєР»СЋС‡РµРЅРёР№. РўРѕРіРґР° Р·Р°РїСЂРѕСЃ-РґРёР°Р»РѕРі СЃРёСЃС‚РµРјР° РјРѕР»С‡Р°
 * РёРіРЅРѕСЂРёСЂСѓРµС‚, Рё РІРµСЃС‚Рё РЅР°РґРѕ СЃСЂР°Р·Сѓ РІ РЅР°СЃС‚СЂРѕР№РєРё: РЅР° OxygenOS СЃРѕР±СЃС‚РІРµРЅРЅС‹Рµ СЂРµР¶РёРјС‹
 * СЌРЅРµСЂРіРѕСЃР±РµСЂРµР¶РµРЅРёСЏ Р¶РёРІСѓС‚ РѕС‚РґРµР»СЊРЅРѕ РѕС‚ СЃРёСЃС‚РµРјРЅРѕРіРѕ whitelist Рё РґСѓС€Р°С‚ РїСЂРѕС†РµСЃСЃ РЅРµР·Р°РІРёСЃРёРјРѕ.
 */
private fun Context.openBatterySettings(exempt: Boolean) {
    val pkg = "package:$packageName".toUri()
    val candidates = buildList {
        if (!exempt) {
            add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(pkg))
        }
        // РљР°СЂС‚РѕС‡РєР° РїСЂРёР»РѕР¶РµРЅРёСЏ РїРµСЂРІРѕР№: СЂРµР¶РёРј СЌРЅРµСЂРіРѕСЃР±РµСЂРµР¶РµРЅРёСЏ РІС‹Р±РёСЂР°РµС‚СЃСЏ РёРјРµРЅРЅРѕ С‚Р°Рј, Р°
        // СЃРёСЃС‚РµРјРЅС‹Р№ СЃРїРёСЃРѕРє РёСЃРєР»СЋС‡РµРЅРёР№ РІ СЌС‚РѕС‚ РјРѕРјРµРЅС‚ СѓР¶Рµ РїРѕРєР°Р·С‹РІР°РµС‚ В«РЅРµ РѕРїС‚РёРјРёР·РёСЂСѓРµС‚СЃСЏВ».
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(pkg))
        add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
    for (intent in candidates) {
        if (runCatching { startActivity(intent) }.isSuccess) return
    }
}

private fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

/** РўРµРєСЃС‚ СЃРЅРµРєР±Р°СЂР° РїРѕ СЂРµР·СѓР»СЊС‚Р°С‚Сѓ СЌРєСЃРїРѕСЂС‚Р°/РІРѕСЃСЃС‚Р°РЅРѕРІР»РµРЅРёСЏ (СЃС‚СЂРѕРєРё РІС‹Р±РёСЂР°РµС‚ UI, РЅРµ ViewModel). */
internal fun backupEventMessage(context: Context, event: BackupEvent): String =
    when (event) {
        BackupEvent.ExportSuccess -> context.getString(R.string.backup_export_ok)
        BackupEvent.ExportFailed -> context.getString(R.string.backup_export_fail)
        is BackupEvent.RestoreSuccess -> context.getString(R.string.backup_restore_ok, event.count)
        is BackupEvent.RestoreFailed -> when (event.reason) {
            RestoreFailReason.BAD_PASSWORD -> context.getString(R.string.backup_restore_bad_password)
            RestoreFailReason.BAD_FILE -> context.getString(R.string.backup_restore_bad_file)
            RestoreFailReason.IO -> context.getString(R.string.backup_restore_fail)
        }
    }
