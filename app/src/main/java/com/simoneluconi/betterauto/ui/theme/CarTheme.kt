package com.simoneluconi.betterauto.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
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
    /** The map's own colors, fading with the rest. */
    val map: MapColors,
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
    map = MapColors(
        land = Color(0xFF101318),
        landuse = Color(0xFF13171D),
        park = Color(0xFF111816),
        water = Color(0xFF0C1C2D),
        building = Color(0xFF1A1F27),
        rail = Color(0xFF262C35),
        roadMinor = Color(0xFF262C36),
        // Casings the color of the land: at night roads stand out by their own shade
        roadMinorCasing = Color(0xFF101318),
        roadMajor = Color(0xFF343C49),
        roadMajorCasing = Color(0xFF101318),
        motorway = Color(0xFF4B5568),
        motorwayCasing = Color(0xFF101318),
        boundary = Color(0xFF4A5260),
        labelRoad = Color(0xFFA9B1BE),
        labelPlace = Color(0xFFF3F5F8),
        labelMinor = Color(0xFF7D8796),
        labelMotorway = Color(0xFFF3F5F8),
        halo = Color(0xFF101318),
    ),
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
    map = MapColors(
        land = Color(0xFFEEF1F4),
        landuse = Color(0xFFE8EBEF),
        park = Color(0xFFDCEAD9),
        water = Color(0xFFBFD5EA),
        building = Color(0xFFDFE3E9),
        rail = Color(0xFFC9CFD8),
        roadMinor = Color(0xFFFFFFFF),
        roadMinorCasing = Color(0xFFD8DDE4),
        roadMajor = Color(0xFFFFFFFF),
        roadMajorCasing = Color(0xFFC5CDD7),
        motorway = Color(0xFFF9D99B),
        motorwayCasing = Color(0xFFE0B868),
        boundary = Color(0xFF9AA3AF),
        labelRoad = Color(0xFF4A5462),
        labelPlace = Color(0xFF0F1318),
        labelMinor = Color(0xFF6B7584),
        labelMotorway = Color(0xFF5A3E06),
        halo = Color(0xFFFFFFFF),
    ),
)

/** A road sign on the map (the speed limit): the same by day and by night, like the real ones. */
val SignRed = Color(0xFFD7262E)
val SignWhite = Color(0xFFFFFFFF)
val SignInk = Color(0xFF15181D)

/** Road-number signs, in the colours each country uses (motorway green, state road blue...). */
val ShieldGreen = Color(0xFF00804A)
val ShieldBlue = Color(0xFF1C4BA0)
val ShieldRed = Color(0xFFC8102E)
val ShieldYellow = Color(0xFFF7C600)
val ShieldUkYellow = Color(0xFFFFD200)

/**
 * The map's palette. Each name is a placeholder in `res/raw/map_style.json` ("@water"), so the style
 * file says where a color goes and this says which one; day and night fade like the rest of the UI.
 */
@Immutable
data class MapColors(
    val land: Color,
    val landuse: Color,
    val park: Color,
    val water: Color,
    val building: Color,
    val rail: Color,
    val roadMinor: Color,
    val roadMinorCasing: Color,
    val roadMajor: Color,
    val roadMajorCasing: Color,
    val motorway: Color,
    val motorwayCasing: Color,
    val boundary: Color,
    val labelRoad: Color,
    val labelPlace: Color,
    val labelMinor: Color,
    val labelMotorway: Color,
    val halo: Color,
) {
    /** The colors by their placeholder names. */
    fun byName(): Map<String, Color> = mapOf(
        "land" to land, "landuse" to landuse, "park" to park, "water" to water, "building" to building,
        "rail" to rail, "roadMinor" to roadMinor, "roadMinorCasing" to roadMinorCasing, "roadMajor" to roadMajor,
        "roadMajorCasing" to roadMajorCasing, "motorway" to motorway, "motorwayCasing" to motorwayCasing,
        "boundary" to boundary, "labelRoad" to labelRoad, "labelPlace" to labelPlace, "labelMinor" to labelMinor,
        "labelMotorway" to labelMotorway, "halo" to halo,
    )

    companion object {
        fun lerp(a: MapColors, b: MapColors, t: Float) = MapColors(
            land = lerp(a.land, b.land, t),
            landuse = lerp(a.landuse, b.landuse, t),
            park = lerp(a.park, b.park, t),
            water = lerp(a.water, b.water, t),
            building = lerp(a.building, b.building, t),
            rail = lerp(a.rail, b.rail, t),
            roadMinor = lerp(a.roadMinor, b.roadMinor, t),
            roadMinorCasing = lerp(a.roadMinorCasing, b.roadMinorCasing, t),
            roadMajor = lerp(a.roadMajor, b.roadMajor, t),
            roadMajorCasing = lerp(a.roadMajorCasing, b.roadMajorCasing, t),
            motorway = lerp(a.motorway, b.motorway, t),
            motorwayCasing = lerp(a.motorwayCasing, b.motorwayCasing, t),
            boundary = lerp(a.boundary, b.boundary, t),
            labelRoad = lerp(a.labelRoad, b.labelRoad, t),
            labelPlace = lerp(a.labelPlace, b.labelPlace, t),
            labelMinor = lerp(a.labelMinor, b.labelMinor, t),
            labelMotorway = lerp(a.labelMotorway, b.labelMotorway, t),
            halo = lerp(a.halo, b.halo, t),
        )
    }
}

private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(manrope(500), manrope(600), manrope(700), manrope(800))

// The palette changes on every frame of a theme fade or album color change: a tracked local
// recomposes only what reads it, a static one would recompose the whole launcher each frame
val LocalCarColors = compositionLocalOf { NightColors }

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
            // Labels flip with the text, so they never fade into the land under them
            map = MapColors.lerp(day.map, dark.map, progress).copy(
                labelRoad = lerp(day.map.labelRoad, dark.map.labelRoad, textProgress),
                labelPlace = lerp(day.map.labelPlace, dark.map.labelPlace, textProgress),
                labelMinor = lerp(day.map.labelMinor, dark.map.labelMinor, textProgress),
                labelMotorway = lerp(day.map.labelMotorway, dark.map.labelMotorway, textProgress),
                halo = lerp(day.map.halo, dark.map.halo, textProgress),
            ),
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
