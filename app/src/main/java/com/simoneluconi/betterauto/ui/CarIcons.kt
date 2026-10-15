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
    val Pause = icon(
        filled = listOf(
            "M6.7 4h2.1a1.2 1.2 0 0 1 1.2 1.2v13.6a1.2 1.2 0 0 1-1.2 1.2H6.7a1.2 1.2 0 0 1-1.2-1.2V5.2A1.2 1.2 0 0 1 6.7 4z",
            "M15.2 4h2.1a1.2 1.2 0 0 1 1.2 1.2v13.6a1.2 1.2 0 0 1-1.2 1.2h-2.1a1.2 1.2 0 0 1-1.2-1.2V5.2A1.2 1.2 0 0 1 15.2 4z",
        )
    )
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
