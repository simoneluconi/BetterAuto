package com.simoneluconi.betterauto.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Line icons on a 24x24 grid (same set as the design mockup). */
object CarIcons {
    val Home = icon("M3 11l9-8 9 8", "M5 10v10h14V10")
    val Music = icon("M9 18V5l12-2v13", circle(6f, 18f, 3f), circle(18f, 16f, 3f))
    val Map = icon("M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z", circle(12f, 10f, 2.5f))
    val Phone = icon("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z")
    val Bell = icon("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    val Settings = icon(
        "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12",
        circle(16f, 6f, 2f), circle(10f, 12f, 2f), circle(18f, 18f, 2f),
    )
    val Layout = icon(
        "M5 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M17 3h2a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M17 13h2a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z",
    )
    val Moon = icon("M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
    val Sun = icon(
        circle(12f, 12f, 4f),
        "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
    )
    val Previous = icon("M5 19V5", filled = listOf("M19 20L9 12l10-8v16z"))
    val Next = icon("M19 5v14", filled = listOf("M5 4l10 8-10 8V4z"))
    val Play = icon(filled = listOf("M7 4l13 8-13 8V4z"))
    val FastForward = icon(filled = listOf("M2 5l10 7-10 7V5z", "M12 5l10 7-10 7V5z"))
    val FastRewind = icon(filled = listOf("M22 5l-10 7 10 7V5z", "M12 5L2 12l10 7V5z"))
    val Pause = icon(
        filled = listOf(
            "M6.7 4h2.1a1.2 1.2 0 0 1 1.2 1.2v13.6a1.2 1.2 0 0 1-1.2 1.2H6.7a1.2 1.2 0 0 1-1.2-1.2V5.2A1.2 1.2 0 0 1 6.7 4z",
            "M15.2 4h2.1a1.2 1.2 0 0 1 1.2 1.2v13.6a1.2 1.2 0 0 1-1.2 1.2h-2.1a1.2 1.2 0 0 1-1.2-1.2V5.2A1.2 1.2 0 0 1 15.2 4z",
        )
    )
    val Artist = icon("M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2", circle(12f, 7f, 4f))
    val Album = icon(circle(12f, 12f, 10f), circle(12f, 12f, 3f))
    val Chevron = icon("M9 6l6 6-6 6")
    val Back = icon("M15 6l-6 6 6 6")
    val Expand = icon("M8 3H5a2 2 0 0 0-2 2v3", "M21 8V5a2 2 0 0 0-2-2h-3", "M3 16v3a2 2 0 0 0 2 2h3", "M16 21h3a2 2 0 0 0 2-2v-3")
    val UpNext = icon("M3 6h12M3 12h12M3 18h7", "M15 15l6 3-6 3z")
    val Check = icon("M5 12.5l4.5 4.5L19 7.5")
    val Wireless = icon("M2 9a15 15 0 0 1 20 0", "M5.5 12.5a10 10 0 0 1 13 0", "M9 16a5 5 0 0 1 6 0", circle(12f, 19.5f, 0.6f))
    val Speaker = icon("M11 5L6 9H3v6h3l5 4V5z", "M15.5 8.5a5 5 0 0 1 0 7", "M18.5 5.5a9.5 9.5 0 0 1 0 13")
    val Car = icon("M5 16V11l2-5h10l2 5v5", "M3 16h18v2H3z", "M5 11h14", circle(7.5f, 14f, 0.6f), circle(16.5f, 14f, 0.6f))
    val CallEnd = icon(filled = listOf(
        "M12 9.5c-2.6 0-5.1.5-7.3 1.6-.7.3-1.1 1-1.1 1.8v2.3c0 .8.8 1.3 1.5 1.1l2.9-.9c.6-.2 1-.7 1-1.4v-1.6c1-.3 2-.4 3-.4s2 .1 3 .4V14c0 .6.4 1.2 1 1.4l2.9.9c.7.2 1.5-.3 1.5-1.1v-2.3c0-.8-.4-1.5-1.1-1.8-2.2-1.1-4.7-1.6-7.3-1.6z"
    ))
    val CallAnswer = icon(filled = listOf(
        "M20 15.5c-1.2 0-2.4-.2-3.6-.6-.3-.1-.7 0-1 .2l-2.2 2.2c-2.8-1.4-5.1-3.8-6.6-6.6l2.2-2.2c.3-.3.4-.7.2-1-.3-1.1-.5-2.3-.5-3.5 0-.6-.4-1-1-1H4c-.6 0-1 .4-1 1 0 9.4 7.6 17 17 17 .6 0 1-.4 1-1v-3.5c0-.6-.4-1-1-1z"
    ))
    val Mic = icon("M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z", "M19 11a7 7 0 0 1-14 0", "M12 18v3")
    val MicOff = icon("M9 9v3a3 3 0 0 0 5.1 2.1", "M15 9.3V6a3 3 0 0 0-5.9-.8", "M19 11a7 7 0 0 1-1.1 3.8", "M5 11a7 7 0 0 0 11.6 5.3", "M12 18v3", "M3 3l18 18")
    val Dialpad = icon(
        circle(6f, 4.5f, 1.3f), circle(12f, 4.5f, 1.3f), circle(18f, 4.5f, 1.3f),
        circle(6f, 10f, 1.3f), circle(12f, 10f, 1.3f), circle(18f, 10f, 1.3f),
        circle(6f, 15.5f, 1.3f), circle(12f, 15.5f, 1.3f), circle(18f, 15.5f, 1.3f),
        circle(12f, 21f, 1.3f),
    )
    val Backspace = icon("M21 5H8l-6 7 6 7h13z", "M11 9l6 6", "M17 9l-6 6")
    val Star = icon("M12 3l2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3 6.4 20.2l1.1-6.2L3 9.6l6.2-.9z")
    val Clock = icon(circle(12f, 12f, 9f), "M12 7v5l3 2")
    val Contacts = icon("M17 21v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2", circle(10f, 8f, 4f), "M21 21v-2a4 4 0 0 0-3-3.9", "M16 4.1a4 4 0 0 1 0 7.8")
    val Incoming = icon("M17 7L7 17", "M7 9v8h8")
    val Outgoing = icon("M7 17L17 7", "M9 7h8v8")
    val Missed = icon("M4 8l8 8 8-8", "M15 8h5v5")
    val BellOff = icon("M8.7 3.9A6 6 0 0 1 18 8c0 3.2.6 5.3 1.4 6.7", "M17 17H3s3-2 3-9c0-.6.1-1.2.3-1.7", "M10.3 21a1.94 1.94 0 0 0 3.4 0", "M3 3l18 18")
    val Close = icon("M6 6l12 12", "M18 6L6 18")
    val Check2 = icon("M4 12.5l5 5L20 6.5")
    /** The check-engine symbol of instrument clusters. */
    val Engine = icon(
        "M8 7h6", "M11 7v2",
        "M5 11h1.5V9H16l2 2h1.5v-1H21v6h-1.5v-1H18v2h-2.5L14 18.5H8.5L6.5 16.5V15H5v1.5H3v-5h2z",
    )
    val Fuel = icon("M4 20V5a1 1 0 0 1 1-1h7a1 1 0 0 1 1 1v15", "M3 20h11", "M4 10h9", "M13 8h2l2 2v7a1.5 1.5 0 0 0 3 0V9l-2.5-2.5")
    val Gauge = icon("M4.5 17a8 8 0 1 1 15 0", "M12 13l3.5-4", circle(12f, 13f, 1.2f))
    val Tire = icon(circle(12f, 12f, 8.5f), circle(12f, 12f, 4f), "M12 3.5v4", "M12 16.5v4", "M3.5 12h4", "M16.5 12h4")
    val Thermometer = icon("M14 14.8V5a2 2 0 0 0-4 0v9.8a4 4 0 1 0 4 0z", "M12 10v7")
    val Door = icon("M5 20V6l9-3v17", "M5 20h14", "M14 6h4v14", "M11 12h.01")
    val Light = icon("M9 18h6", "M10 21h4", "M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z")
    val List = icon("M9 6h12M9 12h12M9 18h12", circle(4f, 6f, 1.2f), circle(4f, 12f, 1.2f), circle(4f, 18f, 1.2f))
    val Shuffle = icon("M16 3h5v5", "M4 20L21 3", "M21 16v5h-5", "M15 15l6 6", "M4 4l5 5")
    val Repeat = icon("M17 2l4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "M7 22l-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3")

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun icon(vararg strokes: String, filled: List<String> = emptyList()): ImageVector {
        val builder = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in strokes) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        for (d in filled) {
            builder.addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
        }
        return builder.build()
    }
}

@Composable
fun CarIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Image(imageVector = icon, contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = modifier)
}
