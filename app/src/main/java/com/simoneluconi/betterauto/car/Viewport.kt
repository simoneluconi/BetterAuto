package com.simoneluconi.betterauto.car

import kotlin.math.abs
import kotlin.math.roundToInt

/** Edges of an area, in pixels. */
data class Edges(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    companion object {
        val None = Edges()
    }
}

/**
 * Where the launcher is drawn inside the video frame, in frame pixels.
 * Head units whose screen isn't one of the standard video sizes (wide dashboards, portrait screens)
 * ask for a standard size with margins and show only the area inside them.
 */
data class Viewport(
    val frameWidth: Int,
    val frameHeight: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    /** Parts of the visible area that may be covered (curved glass, bezels): background only, no controls. */
    val insets: Edges = Edges.None,
) {
    val isFullFrame get() = left == 0 && top == 0 && width == frameWidth && height == frameHeight

    /**
     * Head unit touch coordinates to frame pixels. Touchscreens report either in the frame's space or
     * in the visible area's own: whichever has the closer aspect ratio is the one in use.
     */
    fun touchToFrame(x: Float, y: Float, touchWidth: Int, touchHeight: Int): Pair<Float, Float> {
        if (touchWidth <= 0 || touchHeight <= 0) return x to y
        val touchAspect = touchWidth.toFloat() / touchHeight
        val inArea = !isFullFrame &&
            abs(touchAspect - width.toFloat() / height) < abs(touchAspect - frameWidth.toFloat() / frameHeight)
        return if (inArea) {
            left + x * width / touchWidth to top + y * height / touchHeight
        } else {
            x * frameWidth / touchWidth to y * frameHeight / touchHeight
        }
    }

    companion object {
        fun full(width: Int, height: Int) = Viewport(width, height, 0, 0, width, height)

        /** A [width] x [height] area centered in the frame: how head units apply their margins. */
        fun centered(frameWidth: Int, frameHeight: Int, width: Int, height: Int): Viewport {
            val w = width.coerceIn(1, frameWidth)
            val h = height.coerceIn(1, frameHeight)
            return Viewport(frameWidth, frameHeight, (frameWidth - w) / 2, (frameHeight - h) / 2, w, h)
        }

        /** The largest area with the [aspect] ratio (width / height) centered in the frame. */
        fun aspect(frameWidth: Int, frameHeight: Int, aspect: Float): Viewport =
            if (aspect < frameWidth.toFloat() / frameHeight) {
                centered(frameWidth, frameHeight, (frameHeight * aspect).roundToInt(), frameHeight)
            } else {
                centered(frameWidth, frameHeight, frameWidth, (frameWidth / aspect).roundToInt())
            }
    }
}
