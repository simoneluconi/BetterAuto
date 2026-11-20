package com.simoneluconi.betterauto.projection

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The density a phone app gets on its display in the car, for any car screen: its short side comes to about
 * [TARGET_SHORT_DP], a little more than a phone held sideways (about 410 dp), the room these apps are made for; at
 * 520-560 dp their text was too small to use from the seat. Kept near the launcher's own density, so that the app is
 * neither tiny on a big screen nor huge on a small one; the driver's size choice ([scale]) goes on top.
 */
object AppDensity {
    const val TARGET_SHORT_DP = 460f

    /** No smaller than this much of the launcher's density... */
    private const val MIN_OF_LAUNCHER = 1.0f

    /** ...and no bigger than this (a small, dense screen keeps the app legible, with less room). */
    private const val MAX_OF_LAUNCHER = 1.5f

    /**
     * [widthPx] x [heightPx]: the app's picture on the car screen; [launcherDensity]: pixels per design unit of
     * the launcher there (LocalDensity); [scale]: the driver's size choice (1 = Auto). The display's densityDpi.
     */
    fun dpi(widthPx: Int, heightPx: Int, launcherDensity: Float, scale: Float = 1f): Int {
        val launcherDpi = launcherDensity * 160f
        val fitting = min(widthPx, heightPx) * 160f / TARGET_SHORT_DP
        return (fitting.coerceIn(launcherDpi * MIN_OF_LAUNCHER, launcherDpi * MAX_OF_LAUNCHER) * scale).roundToInt().coerceAtLeast(MIN_DPI)
    }

    private const val MIN_DPI = 80
}
