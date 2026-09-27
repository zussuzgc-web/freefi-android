package com.freeturn.app.viewmodel

import android.content.Context
import com.freeturn.app.R

/** Текст ошибки для UI: message исключения либо общий фоллбэк из ресурсов. */
fun Throwable.uiError(context: Context): String =
    message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.error_ssh_generic)
