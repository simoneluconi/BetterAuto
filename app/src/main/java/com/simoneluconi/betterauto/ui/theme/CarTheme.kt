package com.simoneluconi.betterauto.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.simoneluconi.betterauto.R

@Immutable
data class CarColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val text2: Color,
    val focus: Color,
    /** Accent taken from the album art (or a default), used by the music widget. */
    val album: Color,
) {
    /** Readable content color on top of [album]. */
    val onAlbum: Color get() = if (album.luminance() > 0.35f) Color(0xFF1B1006) else Color.White

    /** Card background tinted by the album color. */
    val albumCard: Color get() = lerp(surface, album, 0.16f)
}

val NightColors = CarColors(
    bg = Color(0xFF090B0E),
    surface = Color(0xFF14171C),
    surface2 = Color(0xFF1C2128),
    line = Color(0xFF2A303A),
    text = Color(0xFFF3F5F8),
    text2 = Color(0xFFA9B1BE),
    focus = Color(0xFF5EA8FF),
    album = Color(0xFF5EA8FF),
)

val DayColors = CarColors(
    bg = Color(0xFFE7EBF0),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF1F3F6),
    line = Color(0xFFD3D9E1),
    text = Color(0xFF0F1318),
    text2 = Color(0xFF4A5462),
    focus = Color(0xFF0B63CE),
    album = Color(0xFF0B63CE),
)

private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(manrope(500), manrope(600), manrope(700), manrope(800))

val LocalCarColors = staticCompositionLocalOf { NightColors }

/** Base text style; sizes are in design units (1 unit = 1 px on a 1280x720 screen). */
val CarText = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)

private const val THEME_FADE_MS = 1400

/**
 * Day/night palette with a slow fade instead of a flash. Surfaces fade over the whole duration
 * while text flips quickly around the point where the background crosses mid-luminance,
 * so text never fades into the background. [album] tints the music widget and follows the track.
 */
@Composable
fun CarTheme(night: Boolean, album: Color?, content: @Composable () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = if (night) 1f else 0f,
        animationSpec = tween(THEME_FADE_MS, easing = FastOutSlowInEasing),
        label = "theme",
    )
    val textProgress = smoothStep((progress - TEXT_FLIP_START) / (TEXT_FLIP_END - TEXT_FLIP_START))
    val day = DayColors
    val dark = NightColors
    val albumColor by animateColorAsState(album ?: lerp(day.album, dark.album, progress), tween(900), label = "album")

    CompositionLocalProvider(
        LocalCarColors provides CarColors(
            bg = lerp(day.bg, dark.bg, progress),
            surface = lerp(day.surface, dark.surface, progress),
            surface2 = lerp(day.surface2, dark.surface2, progress),
            line = lerp(day.line, dark.line, progress),
            text = lerp(day.text, dark.text, textProgress),
            text2 = lerp(day.text2, dark.text2, textProgress),
            focus = lerp(day.focus, dark.focus, progress),
            album = albumColor,
        ),
        content = content,
    )
}

private const val TEXT_FLIP_START = 0.42f
private const val TEXT_FLIP_END = 0.58f

private fun smoothStep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
