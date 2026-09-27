package com.freeturn.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.freeturn.app.ui.theme.Spacing

// Сегментированная группа строк (M3 expressive, как в системных настройках Android 16):
// наружные углы группы большие, внутренние - маленькие, между элементами микро-зазор.

// Внутренний радиус сегментированной группы (между элементами). Внешний - shapes.large,
// чтобы группа из одного элемента визуально совпадала с SettingsCard.
private val GroupInnerCorner = CornerSize(5.dp)

/** Форма элемента сегментированной группы по позиции (см. [SettingsGroup]). */
@Composable
fun settingsItemShape(index: Int, count: Int): Shape {
    val outer = MaterialTheme.shapes.large.topStart
    return RoundedCornerShape(
        topStart = if (index == 0) outer else GroupInnerCorner,
        topEnd = if (index == 0) outer else GroupInnerCorner,
        bottomStart = if (index == count - 1) outer else GroupInnerCorner,
        bottomEnd = if (index == count - 1) outer else GroupInnerCorner
    )
}

/** Колонка сегментированной группы: элементы с зазором 2dp вместо разделителей. */
@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        modifier = Modifier.fillMaxWidth(),
        content = content
    )
}

/** Элемент сегментированной группы: тональный контейнер с формой по позиции. */
@Composable
fun SettingsGroupItem(index: Int, count: Int, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = settingsItemShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}
