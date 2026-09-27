package com.freeturn.app.ui.screens.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.ui.theme.Spacing

/**
 * Кнопка split-tunneling над свёрнутым листом серверов: состояние (вкл/выкл)
 * + стрелка раскрытия. Выглядит как настоящая кнопка. Тап открывает модалку
 * выбора режима/приложений.
 */
@Composable
internal fun SplitTunnelButton(
    splitActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Icon(
            painterResource(R.drawable.tune_24px),
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = if (splitActive) stringResource(R.string.split_tunnel_status_on)
            else stringResource(R.string.split_tunnel_status_off)
        )
        Spacer(Modifier.width(Spacing.xs))
        Icon(
            painterResource(R.drawable.unfold_more_24px),
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
    }
}