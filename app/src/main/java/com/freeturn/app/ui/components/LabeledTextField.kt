@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.freeturn.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation

/**
 * OutlinedTextField для простого поля-настройки: метка/плейсхолдер/подсказка из ресурсов,
 * fillMaxWidth. При [isError] подсказка подменяется [errorRes]. Фильтрацию ввода держит
 * вызывающий в [onValueChange].
 */
@Composable
fun LabeledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes labelRes: Int,
    modifier: Modifier = Modifier,
    @StringRes placeholderRes: Int? = null,
    @StringRes supportingRes: Int? = null,
    @StringRes errorRes: Int? = null,
    isError: Boolean = false,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val supporting: @Composable (() -> Unit)? = when {
        isError && errorRes != null -> { { Text(stringResource(errorRes)) } }
        supportingRes != null -> { { Text(stringResource(supportingRes)) } }
        else -> null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        placeholder = placeholderRes?.let { { Text(stringResource(it)) } },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        readOnly = readOnly,
        isError = isError,
        supportingText = supporting,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon
    )
}
