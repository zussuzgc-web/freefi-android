package com.freeturn.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Чистая Kotlin-математика цвета: конвертации HSL/HSV совпадают с каноническими. */
internal object ColorMath {

    /** красный/зелёный/синий 0..255 из Int ARGB. */
    private fun channels(color: Int) = Triple(
        (color shr 16) and 0xFF,
        (color shr 8) and 0xFF,
        color and 0xFF
    )

    private fun argb(r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 255f)).roundToInt()
        val gi = (g.coerceIn(0f, 255f)).roundToInt()
        val bi = (b.coerceIn(0f, 255f)).roundToInt()
        return 0xFF000000.toInt() or (ri shl 16) or (gi shl 8) or bi
    }

    private fun norm360(v: Float): Float = ((v % 360f) + 360f) % 360f

    /** h 0..360, s 0..1, v 0..1 -> Int ARGB. */
    fun hsvToColor(h: Float, s: Float, v: Float): Int {
        val hue = norm360(h)
        val sat = s.coerceIn(0f, 1f)
        val value = v.coerceIn(0f, 1f)
        val c = value * sat
        val x = c * (1f - abs((hue / 60f) % 2f - 1f))
        val m = value - c
        val (r, g, b) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb((r + m) * 255f, (g + m) * 255f, (b + m) * 255f)
    }

    /** Int ARGB -> [h, s, v]: h 0..360, s/v 0..1. */
    fun colorToHsv(color: Int): FloatArray {
        val (rr, gg, bb) = channels(color)
        val r = rr / 255f
        val g = gg / 255f
        val b = bb / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val v = mx
        val s = if (mx == 0f) 0f else d / mx
        val h = when {
            d == 0f -> 0f
            mx == r -> (g - b) / d
            mx == g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        return floatArrayOf(norm360(h), s, v)
    }

    /** h 0..360, s/l 0..1 -> Int ARGB. */
    fun hslToColor(h: Float, s: Float, l: Float): Int {
        val hue = norm360(h)
        val sat = s.coerceIn(0f, 1f)
        val light = l.coerceIn(0f, 1f)
        val c = (1f - abs(2f * light - 1f)) * sat
        val x = c * (1f - abs((hue / 60f) % 2f - 1f))
        val m = light - c / 2f
        val (r, g, b) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb((r + m) * 255f, (g + m) * 255f, (b + m) * 255f)
    }

    /** Int ARGB -> [h, s, l]: h 0..360, s/l 0..1. */
    fun colorToHsl(color: Int): FloatArray {
        val (rr, gg, bb) = channels(color)
        val r = rr / 255f
        val g = gg / 255f
        val b = bb / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val l = (mx + mn) / 2f
        val s = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
        val h = when {
            d == 0f -> 0f
            mx == r -> (g - b) / d
            mx == g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        return floatArrayOf(norm360(h), s, l)
    }

    /** Относительная светимость WCAG (0..1) - такой же результат, как у ColorUtils. */
    fun relativeLuminance(color: Int): Float {
        fun srgb(c: Int): Float {
            val v = c / 255f
            return if (v <= 0.03928f) v / 12.92f
            else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        val (rr, gg, bb) = channels(color)
        return 0.2126f * srgb(rr) + 0.7152f * srgb(gg) + 0.0722f * srgb(bb)
    }
}

val primaryLight = Color(0xFF415F91)
val onPrimaryLight = Color(0xFFFFFFFF)
val primaryContainerLight = Color(0xFFD6E3FF)
val onPrimaryContainerLight = Color(0xFF284777)
val secondaryLight = Color(0xFF565F71)
val onSecondaryLight = Color(0xFFFFFFFF)
val secondaryContainerLight = Color(0xFFDAE2F9)
val onSecondaryContainerLight = Color(0xFF3E4759)
val tertiaryLight = Color(0xFF705575)
val onTertiaryLight = Color(0xFFFFFFFF)
val tertiaryContainerLight = Color(0xFFFAD8FD)
val onTertiaryContainerLight = Color(0xFF573E5C)
val errorLight = Color(0xFFBA1A1A)
val onErrorLight = Color(0xFFFFFFFF)
val errorContainerLight = Color(0xFFFFDAD6)
val onErrorContainerLight = Color(0xFF93000A)
val backgroundLight = Color(0xFFF9F9FF)
val onBackgroundLight = Color(0xFF191C20)
val surfaceLight = Color(0xFFF9F9FF)
val onSurfaceLight = Color(0xFF191C20)
val surfaceVariantLight = Color(0xFFE0E2EC)
val onSurfaceVariantLight = Color(0xFF44474E)
val outlineLight = Color(0xFF74777F)
val outlineVariantLight = Color(0xFFC4C6D0)
val scrimLight = Color(0xFF000000)
val inverseSurfaceLight = Color(0xFF2E3036)
val inverseOnSurfaceLight = Color(0xFFF0F0F7)
val inversePrimaryLight = Color(0xFFAAC7FF)
val surfaceDimLight = Color(0xFFD9D9E0)
val surfaceBrightLight = Color(0xFFF9F9FF)
val surfaceContainerLowestLight = Color(0xFFFFFFFF)
val surfaceContainerLowLight = Color(0xFFF3F3FA)
val surfaceContainerLight = Color(0xFFEDEDF4)
val surfaceContainerHighLight = Color(0xFFE7E8EE)
val surfaceContainerHighestLight = Color(0xFFE2E2E9)



val primaryDark = Color(0xFFAAC7FF)
val onPrimaryDark = Color(0xFF0A305F)
val primaryContainerDark = Color(0xFF284777)
val onPrimaryContainerDark = Color(0xFFD6E3FF)
val secondaryDark = Color(0xFFBEC6DC)
val onSecondaryDark = Color(0xFF283141)
val secondaryContainerDark = Color(0xFF3E4759)
val onSecondaryContainerDark = Color(0xFFDAE2F9)
val tertiaryDark = Color(0xFFDDBCE0)
val onTertiaryDark = Color(0xFF3F2844)
val tertiaryContainerDark = Color(0xFF573E5C)
val onTertiaryContainerDark = Color(0xFFFAD8FD)
val errorDark = Color(0xFFFFB4AB)
val onErrorDark = Color(0xFF690005)
val errorContainerDark = Color(0xFF93000A)
val onErrorContainerDark = Color(0xFFFFDAD6)
val backgroundDark = Color(0xFF111318)
val onBackgroundDark = Color(0xFFE2E2E9)
val surfaceDark = Color(0xFF111318)
val onSurfaceDark = Color(0xFFE2E2E9)
val surfaceVariantDark = Color(0xFF44474E)
val onSurfaceVariantDark = Color(0xFFC4C6D0)
val outlineDark = Color(0xFF8E9099)
val outlineVariantDark = Color(0xFF44474E)
val scrimDark = Color(0xFF000000)
val inverseSurfaceDark = Color(0xFFE2E2E9)
val inverseOnSurfaceDark = Color(0xFF2E3036)
val inversePrimaryDark = Color(0xFF415F91)
val surfaceDimDark = Color(0xFF111318)
val surfaceBrightDark = Color(0xFF37393E)
val surfaceContainerLowestDark = Color(0xFF0C0E13)
val surfaceContainerLowDark = Color(0xFF191C20)
val surfaceContainerDark = Color(0xFF1D2024)
val surfaceContainerHighDark = Color(0xFF282A2F)
val surfaceContainerHighestDark = Color(0xFF33353A)

/** HSV-хелперы для генерации схемы из выбранного цвета (круг Иттена). */

private fun Color.hueDeg(): Float = ColorMath.colorToHsl(toArgb())[0]

private fun Color.satur(): Float = ColorMath.colorToHsl(toArgb())[1]

private fun Color.light(): Float = ColorMath.colorToHsl(toArgb())[2]

/** Сборка цвета по HSL (hue в градусах, sat/light 0..1). */
private fun hslColor(hue: Float, sat: Float, light: Float): Color =
    Color(ColorMath.hslToColor(hue, sat, light))

private fun Color.relativeLuminance(): Float = ColorMath.relativeLuminance(toArgb())

/**
 * Схема из выбранного пользователем цвета-сида. Тональные пары приближены к Material 3:
 * акцент живёт в ролях primary/secondary/tertiary и их контейнерах, нейтральные плоскости
 * (surface/background/outline) остаются от штатных схем - они уже выверены по контрасту.
 */
fun seedColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val h = seed.hueDeg()
    val s = seed.satur().coerceIn(0.12f, 1f)
    val c = ::hslColor

    // Третичный тон - противоположная сторона круга (примерно +210°), чтобы не сливался
    // с основным цветом в элементах, где семантически нужна отдельная роль.
    val th = (h + 210f) % 360f
    val onSeed = if (seed.relativeLuminance() > 0.45f) Color.Black else Color.White

    return if (dark) {
        darkColorScheme(
            primary = c(h, s * 0.9f, 0.78f),
            onPrimary = c(h, s * 0.7f, 0.18f),
            primaryContainer = c(h, s * 0.5f, 0.30f),
            onPrimaryContainer = c(h, s * 0.45f, 0.88f),
            secondary = c(h, s * 0.35f, 0.70f),
            onSecondary = c(h, s * 0.6f, 0.20f),
            secondaryContainer = c(h, s * 0.3f, 0.26f),
            onSecondaryContainer = c(h, s * 0.4f, 0.84f),
            tertiary = c(th, s * 0.6f, 0.72f),
            onTertiary = c(th, s * 0.6f, 0.20f),
            tertiaryContainer = c(th, s * 0.4f, 0.28f),
            onTertiaryContainer = c(th, s * 0.45f, 0.86f),
            error = errorDark,
            onError = onErrorDark,
            errorContainer = errorContainerDark,
            onErrorContainer = onErrorContainerDark,
            background = backgroundDark,
            onBackground = onBackgroundDark,
            surface = surfaceDark,
            onSurface = onSurfaceDark,
            surfaceVariant = surfaceVariantDark,
            onSurfaceVariant = onSurfaceVariantDark,
            outline = outlineDark,
            outlineVariant = outlineVariantDark,
            scrim = scrimDark,
            inverseSurface = inverseSurfaceDark,
            inverseOnSurface = inverseOnSurfaceDark,
            inversePrimary = c(h, s * 0.7f, 0.48f),
            surfaceDim = surfaceDimDark,
            surfaceBright = surfaceBrightDark,
            surfaceContainerLowest = surfaceContainerLowestDark,
            surfaceContainerLow = surfaceContainerLowDark,
            surfaceContainer = surfaceContainerDark,
            surfaceContainerHigh = surfaceContainerHighDark,
            surfaceContainerHighest = surfaceContainerHighestDark
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = onSeed,
            primaryContainer = c(h, s * 0.35f, 0.92f),
            onPrimaryContainer = c(h, s * 0.6f, 0.18f),
            secondary = c(h, s * 0.3f, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = c(h, s * 0.25f, 0.86f),
            onSecondaryContainer = c(h, s * 0.5f, 0.22f),
            tertiary = c(th, s * 0.55f, 0.42f),
            onTertiary = Color.White,
            tertiaryContainer = c(th, s * 0.4f, 0.90f),
            onTertiaryContainer = c(th, s * 0.6f, 0.20f),
            error = errorLight,
            onError = onErrorLight,
            errorContainer = errorContainerLight,
            onErrorContainer = onErrorContainerLight,
            background = backgroundLight,
            onBackground = onBackgroundLight,
            surface = surfaceLight,
            onSurface = onSurfaceLight,
            surfaceVariant = surfaceVariantLight,
            onSurfaceVariant = onSurfaceVariantLight,
            outline = outlineLight,
            outlineVariant = outlineVariantLight,
            scrim = scrimLight,
            inverseSurface = inverseSurfaceLight,
            inverseOnSurface = inverseOnSurfaceLight,
            inversePrimary = c(h, s * 0.6f, 0.58f),
            surfaceDim = surfaceDimLight,
            surfaceBright = surfaceBrightLight,
            surfaceContainerLowest = surfaceContainerLowestLight,
            surfaceContainerLow = surfaceContainerLowLight,
            surfaceContainer = surfaceContainerLight,
            surfaceContainerHigh = surfaceContainerHighLight,
            surfaceContainerHighest = surfaceContainerHighestLight
        )
    }
}

/**
 * Фон приложения из пользовательского цвета: своя плоскость surface/background и
 * контейнеры, сохраняя hue/sat выбранного цвета и текст с гарантированным контрастом.
 * Акцентные роли (primary/secondary/tertiary) не трогаем - их задаёт отдельный цвет.
 */
fun schemeWithBackground(base: ColorScheme, seed: Color, dark: Boolean): ColorScheme {
    val h = seed.hueDeg()
    val s = seed.satur().coerceIn(0.10f, 1f)
    val l = seed.light()
    fun tone(lightDelta: Float, darkDelta: Float, satMul: Float = 0.85f): Color {
        val delta = if (dark) darkDelta else lightDelta
        return Color(ColorMath.hslToColor(h, (s * satMul).coerceIn(0f, 1f), (l + delta / 100f).coerceIn(0.02f, 0.98f)))
    }
    val onSurface = if (seed.relativeLuminance() > 0.45f) onSurfaceLight else onSurfaceDark
    return base.copy(
        background = seed,
        onBackground = onSurface,
        surface = seed,
        onSurface = onSurface,
        surfaceVariant = tone(-5f, 5f),
        onSurfaceVariant = if (dark) onSurfaceVariantDark else onSurfaceVariantLight,
        surfaceDim = tone(-7f, -3f),
        surfaceBright = tone(5f, 9f),
        surfaceContainerLowest = tone(13f, -8f),
        surfaceContainerLow = tone(8f, -2f),
        surfaceContainer = tone(3f, 3f),
        surfaceContainerHigh = tone(-2f, 8f),
        surfaceContainerHighest = tone(-6f, 12f),
        outline = if (dark) outlineDark else outlineLight,
        outlineVariant = if (dark) outlineVariantDark else outlineVariantLight
    )
}
