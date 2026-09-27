package com.freeturn.app.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Версия приложения из PackageManager; прочерк, если недоступна. */
@Composable
internal fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "-"
        } catch (_: Exception) {
            "-"
        }
    }
}

