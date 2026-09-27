@file:OptIn(ExperimentalMaterial3Api::class)

package com.freeturn.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.freeturn.app.ui.theme.ColorMath
import com.freeturn.app.ui.theme.Spacing
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Стандартный акцент приложения (пока не выбран свой цвет). */
private val DefaultSeedArgb = 0xFF415F91.toInt()

/** Число секторов кольца. 48 даёт плавный переход и ровно 12 делений круга Иттена (по 30°). */
private const val WEDGE_COUNT = 48

private const val WEDGE_STEP = 360f / WEDGE_COUNT

/** Доля кольца между внутренним и внешним радиусом. */
private const val RING_INNER_RATIO = 0.62f

private fun hueToColor(hue: Float): Color =
    Color(ColorMath.hsvToColor(hue, 1f, 1f))

/**
 * Кольцевой выбор цвета. Внешнее кольцо - круг Иттена: 12 делений по 30°, каждое
 * секториально поделено на 4 ступени оттенка, внутри - живой предпросмотр выбранного
 * цвета. Насыщенность и яркость регулируются ползунками ниже; выбранный цвет отдаётся
 * через [onChanged] при любом изменении.
 */
@Composable
fun AccentColorPicker(
    seedArgb: Int,
    saturationLabel: String,
    brightnessLabel: String,
    onChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val (initialHue, initialSat, initialVal) = remember(seedArgb) {
        val hsv = ColorMath.colorToHsv(if (seedArgb == 0) DefaultSeedArgb else seedArgb)
        // Чёрный/белый сед (sat или val ≈ 0) на круге непоимаем: тап по кольцу ничего
        // не изменит. Заходим с разумными значениями, чтобы цвет можно было выбрать.
        Triple(
            hsv[0],
            if (hsv[1] < 0.05f) 0.6f else hsv[1],
            if (hsv[2] < 0.05f) 0.8f else hsv[2]
        )
    }
    var hue by remember(seedArgb) { mutableFloatStateOf(initialHue) }
    var sat by remember(seedArgb) { mutableFloatStateOf(initialSat) }
    var value by remember(seedArgb) { mutableFloatStateOf(initialVal) }

    val current = Color(ColorMath.hsvToColor(hue, sat, value))

    // Живое значение на момент вызова: current из прошлой композиции отстаёт на одно
    // изменение, и «Применить» успело бы сохранить цвет до последнего жеста.
    fun emit() {
        onChanged(ColorMath.hsvToColor(hue, sat, value))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        IttenWheel(
            hue = hue,
            onHue = { picked ->
                if (!picked.isNaN()) {
                    hue = picked
                    emit()
                }
            },
            modifier = Modifier.size(250.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = current,
                modifier = Modifier.size(28.dp)
            ) {}
            Text(
                "#%06X".format(current.toArgb() and 0xFFFFFF),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        SliderLabeled(
            label = saturationLabel,
            fraction = sat,
            onChange = { next ->
                sat = next
                emit()
            }
        )
        SliderLabeled(
            label = brightnessLabel,
            fraction = value,
            onChange = { next ->
                value = next
                emit()
            }
        )
    }
}

/** Ползунок 0..1 с подписью-процентом, как в системных настройках. */
@Composable
private fun SliderLabeled(
    label: String,
    fraction: Float,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = onChange,
            valueRange = 0f..1f
        )
    }
}

/**
 * Кольцо круга Иттена: 12 сегментов по 30°, каждый - плавная смена оттенков. Тап или
 * перетаскивание по кольцу выбирает оттенок; жирная точка отмечает текущее положение.
 */
@Composable
private fun IttenWheel(
    hue: Float,
    onHue: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val at = hueAt(size.toSize(), down.position)
                if (!at.isNaN()) onHue(at)
                drag(down.id) { change ->
                    change.consume()
                    val next = hueAt(size.toSize(), change.position)
                    if (!next.isNaN()) onHue(next)
                }
            }
        }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outer = size.minDimension / 2f - 6.dp.toPx()
        val inner = outer * RING_INNER_RATIO

        // Сектора кольца: цвет сменяется на 7.5° от сектора к сектору, классические
        // 12 тонов Иттена попадают на свои деления каждые 30°.
        for (i in 0 until WEDGE_COUNT) {
            val start = -90f + i * WEDGE_STEP
            val sectorColor = hueToColor(60f - (i + 0.5f) * WEDGE_STEP)
            drawPath(
                ringSector(cx, cy, outer, inner, start, WEDGE_STEP + 0.25f),
                color = sectorColor
            )
        }

        // Границы 12 делений Иттена: тонкие радиальные линии поверх кольца.
        val divider = Color.White.copy(alpha = 0.35f)
        for (k in 0 until 12) {
            val deg = ((-90f + k * 30f) * kotlin.math.PI.toFloat()) / 180f
            val from = Offset(cx + inner * cos(deg), cy + inner * sin(deg))
            val to = Offset(cx + outer * cos(deg), cy + outer * sin(deg))
            drawLine(divider, from, to, strokeWidth = 1.5.dp.toPx())
        }

        // Индикатор текущего оттенка на кольце.
        val frac = (((60f - hue) % 360f) + 360f) % 360f / 360f
        val angle = (frac * 360f - 90f) * kotlin.math.PI.toFloat() / 180f
        val ringR = (inner + outer) / 2f
        val marker = Offset(
            cx + ringR * cos(angle),
            cy + ringR * sin(angle)
        )
        drawCircle(
            color = Color.White,
            radius = 6.5.dp.toPx(),
            center = marker
        )
        drawCircle(
            color = hueToColor(hue),
            radius = 4.5.dp.toPx(),
            center = marker
        )

        // Живой предпросмотр в центре кольца.
        val previewR = inner * 0.55f
        drawCircle(
            color = Color(ColorMath.hsvToColor(hue, 1f, 1f)),
            radius = previewR,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.6f),
            radius = previewR + 2.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

/** Оттенок по позиции пальца: угол от центра кольца переводится в тон круга Иттена. */
private fun hueAt(canvasSize: Size, pos: Offset): Float {
    val cx = canvasSize.width / 2f
    val cy = canvasSize.height / 2f
    val dx = pos.x - cx
    val dy = pos.y - cy
    val outer = canvasSize.minDimension / 2f - 6f
    val inner = outer * RING_INNER_RATIO
    val r = hypot(dx, dy)
    if (r < inner * 0.9f || r > outer * 1.15f) return Float.NaN
    val degrees = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0).toFloat() % 360f
    val topBased = ((degrees + 90f) % 360f) / 360f
    var hue = (60f - topBased * 360f) % 360f
    if (hue < 0f) hue += 360f
    return hue
}

/** Сектор кольца (углы в градусах: 0 = вправо, растёт по часовой). */
private fun ringSector(
    cx: Float,
    cy: Float,
    outer: Float,
    inner: Float,
    startDeg: Float,
    sweepDeg: Float
): Path {
    val toRad = kotlin.math.PI.toFloat() / 180f
    val a0 = startDeg * toRad
    val a1 = (startDeg + sweepDeg) * toRad
    val path = Path()
    path.moveTo(cx + inner * cos(a0), cy + inner * sin(a0))
    path.lineTo(cx + outer * cos(a0), cy + outer * sin(a0))
    path.arcTo(
        Rect(cx - outer, cy - outer, cx + outer, cy + outer),
        startDeg,
        sweepDeg,
        false
    )
    path.lineTo(cx + inner * cos(a1), cy + inner * sin(a1))
    path.arcTo(
        Rect(cx - inner, cy - inner, cx + inner, cy + inner),
        startDeg + sweepDeg,
        -sweepDeg,
        false
    )
    path.close()
    return path
}