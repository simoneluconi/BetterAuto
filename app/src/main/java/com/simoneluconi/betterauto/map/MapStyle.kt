package com.simoneluconi.betterauto.map

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.ui.theme.MapColors
import org.json.JSONObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyValue

/**
 * Our map style: `res/raw/map_style.json` (OpenFreeMap's vector tiles, OpenMapTiles schema) with the
 * colors as placeholders ("@water", names from [MapColors]). Places and streets carry their local names,
 * as on the road signs ("Firenze", whatever the phone's language), in Latin letters where the map has them.
 * The day/night fade changes the colors in place ([apply]), without reloading the map.
 */
object MapStyle {
    private var template: String? = null
    private var themed: List<ThemedPaint>? = null

    fun template(context: Context): String = template
        ?: context.resources.openRawResource(R.raw.map_style).bufferedReader().use { it.readText() }.also { template = it }

    /** The style to load, in [colors]. */
    fun json(context: Context, colors: MapColors): String = fill(template(context), colors.byName())

    /** Recolors a loaded style, e.g. on every frame of the day/night fade. */
    fun apply(context: Context, style: Style, colors: MapColors) {
        val byName = colors.byName()
        val slots = themed ?: themedPaint(template(context)).also { themed = it }
        for (slot in slots) {
            val color = byName[slot.color] ?: continue
            style.getLayer(slot.layer)?.setProperties(PropertyValue(slot.property, css(color)))
        }
    }

    /** One paint property that takes a palette color. */
    data class ThemedPaint(val layer: String, val property: String, val color: String)

    internal fun fill(template: String, colors: Map<String, Color>): String {
        var s = template
        for ((name, color) in colors) s = s.replace("\"@$name\"", "\"${css(color)}\"")
        return s
    }

    /** Every paint property of the template set to a placeholder, so [apply] knows what to change. */
    internal fun themedPaint(template: String): List<ThemedPaint> {
        val layers = JSONObject(template).getJSONArray("layers")
        val out = ArrayList<ThemedPaint>()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val paint = layer.optJSONObject("paint") ?: continue
            for (key in paint.keys()) {
                val value = paint.opt(key) as? String ?: continue
                if (value.startsWith("@")) out += ThemedPaint(layer.getString("id"), key, value.removePrefix("@"))
            }
        }
        return out
    }

    internal fun css(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)
}
