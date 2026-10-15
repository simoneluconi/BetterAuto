package com.simoneluconi.betterauto.ui.theme

import androidx.compose.animation.animateColorAsState
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
 * Day/night palette with a slow cross-fade between them instead of a flash.
 * [album] tints the music widget and follows the playing track.
 */
@Composable
fun CarTheme(night: Boolean, album: Color?, content: @Composable () -> Unit) {
    val target = if (night) NightColors else DayColors
    val spec = tween<Color>(THEME_FADE_MS)
    val bg by animateColorAsState(target.bg, spec, label = "bg")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val surface2 by animateColorAsState(target.surface2, spec, label = "surface2")
    val line by animateColorAsState(target.line, spec, label = "line")
    val text by animateColorAsState(target.text, spec, label = "text")
    val text2 by animateColorAsState(target.text2, spec, label = "text2")
    val focus by animateColorAsState(target.focus, spec, label = "focus")
    val albumColor by animateColorAsState(album ?: target.album, tween(900), label = "album")

    CompositionLocalProvider(
        LocalCarColors provides CarColors(bg, surface, surface2, line, text, text2, focus, albumColor),
        content = content,
    )
}
