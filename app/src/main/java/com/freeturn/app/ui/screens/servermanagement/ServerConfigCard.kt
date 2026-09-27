package com.freeturn.app.ui.screens.servermanagement

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.freeturn.app.R
import com.freeturn.app.ui.components.LabeledTextField
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsFieldSlot
import com.freeturn.app.ui.components.SettingsRowDivider

/** Серверный конфиг прокси: listen-IP/порт и TURN-адрес. SSH-only, показывается при живом подключении. */
@Composable
internal fun ServerConfigCard(
    listenIp: String,
    onListenIp: (String) -> Unit,
    listenPort: String,
    onListenPort: (String) -> Unit,
    connect: String,
    onConnect: (String) -> Unit
) {
    SectionLabel(stringResource(R.string.server_config))
    SettingsCard {
        SettingsFieldSlot {
            LabeledTextField(
                value = listenIp,
                onValueChange = { v -> onListenIp(v.filter { c -> c.isDigit() || c == '.' || c == ':' }) },
                labelRes = R.string.listen_ip,
                placeholderRes = R.string.listen_ip_placeholder,
                supportingRes = R.string.listen_ip_desc,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }
        SettingsRowDivider()
        SettingsFieldSlot {
            LabeledTextField(
                value = listenPort,
                onValueChange = { onListenPort(it.filter { c -> c.isDigit() }) },
                labelRes = R.string.listen_port,
                placeholderRes = R.string.listen_port_placeholder,
                supportingRes = R.string.listen_port_desc,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        SettingsRowDivider()
        SettingsFieldSlot {
            LabeledTextField(
                value = connect,
                onValueChange = onConnect,
                labelRes = R.string.turn_client_address,
                placeholderRes = R.string.turn_client_placeholder,
                supportingRes = R.string.turn_client_desc,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }
    }
}
