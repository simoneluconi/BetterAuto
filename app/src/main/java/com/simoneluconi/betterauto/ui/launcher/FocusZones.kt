package com.simoneluconi.betterauto.ui.launcher

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs

/**
 * The knob's nudges move between zones, turning goes through every control in order. The app bar is
 * one zone; on the screen, each [carZone] (a home widget, the music player and its panel, the settings
 * list and page) is one. A nudge toward the screen lands on its main control (play/pause, the open
 * tab...); a nudge goes to the zone beside the focused one, and toward the bar from the last zone that
 * way it lands on the current section.
 */
val LocalContentFocus = staticCompositionLocalOf<FocusRequester?> { null }

/** Marks the main control of the screen in view. Put it on one control per screen. */
fun Modifier.contentFocus(enabled: Boolean = true): Modifier = composed {
    val requester = LocalContentFocus.current
    if (enabled && requester != null) focusRequester(requester) else Modifier
}

/** The screen's zones on show now, with where they are. Main thread only. */
object CarZones {
    internal class Zone {
        val requester = FocusRequester()
        var bounds = Rect.Zero
        var hasFocus = false
    }

    private val zones = mutableListOf<Zone>()

    /** The zone the knob was in last, while it is on show: a nudge back from the bar goes there. */
    private var last: Zone? = null

    internal fun add(zone: Zone) {
        zones += zone
    }

    internal fun remove(zone: Zone) {
        zones -= zone
        if (last === zone) last = null
    }

    internal fun focused(zone: Zone) {
        last = zone
    }

    /**
     * Moves the focus to the zone beside the focused one toward [direction], skipping zones with nothing to
     * focus (a clock), back on the control it had there; false at the edge or when the focus is in no zone.
     */
    fun moveBeside(direction: FocusDirection): Boolean {
        val current = zones.firstOrNull { it.hasFocus } ?: return false
        val others = zones.filter { it !== current }
        val targets = order(current.bounds, others.map { it.bounds }, direction)
        if (targets.isEmpty()) return false
        save(current)
        return targets.any { i -> enter(others[i]) }
    }

    /** The focused zone remembers its control, the focus about to leave for the bar. */
    fun leaving() {
        zones.firstOrNull { it.hasFocus }?.let(::save)
    }

    /** Back into the zone the knob was in last, on its control; false with none on show (another section). */
    fun returnToLast(): Boolean = last?.let(::enter) ?: false

    // A focus moved by request skips the zone's own restorer (it saves and restores only for the knob's turns): done here
    @OptIn(ExperimentalComposeUiApi::class)
    private fun save(zone: Zone) {
        runCatching { zone.requester.saveFocusedChild() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun enter(zone: Zone): Boolean =
        runCatching { zone.requester.restoreFocusedChild() }.getOrDefault(false) ||
            runCatching { zone.requester.requestFocus() }.getOrDefault(false)

    /** The nearest of [candidates] toward [direction] (see [order]), null when none lies that way. */
    internal fun pick(from: Rect, candidates: List<Rect>, direction: FocusDirection): Int? = order(from, candidates, direction).firstOrNull()

    /**
     * The indices of the rectangles that lie toward [direction] from [from] (their middle past its edge),
     * nearest first: closest along the way, then best lined up across it.
     */
    internal fun order(from: Rect, candidates: List<Rect>, direction: FocusDirection): List<Int> {
        fun score(r: Rect): Float? {
            val (gap, across) = when (direction) {
                FocusDirection.Right -> (r.left - from.right) to (r.center.y - from.center.y)
                FocusDirection.Left -> (from.left - r.right) to (r.center.y - from.center.y)
                FocusDirection.Down -> (r.top - from.bottom) to (r.center.x - from.center.x)
                FocusDirection.Up -> (from.top - r.bottom) to (r.center.x - from.center.x)
                else -> return null
            }
            // Its middle past [from]'s edge: a big widget is not "above" the small one beside it
            val past = when (direction) {
                FocusDirection.Right -> r.center.x > from.right
                FocusDirection.Left -> r.center.x < from.left
                FocusDirection.Down -> r.center.y > from.bottom
                else -> r.center.y < from.top
            }
            if (!past) return null
            return gap.coerceAtLeast(0f) + 2 * abs(across)
        }
        return candidates.indices.mapNotNull { i -> score(candidates[i])?.let { i to it } }.sortedBy { it.second }.map { it.first }
    }
}

/**
 * One zone for the knob's nudges (see [CarZones]): entering it lands on the control last focused there,
 * the first one the first time, whether the knob turned or was nudged into it.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.carZone(): Modifier = composed {
    val zone = remember { CarZones.Zone() }
    DisposableEffect(zone) {
        CarZones.add(zone)
        onDispose { CarZones.remove(zone) }
    }
    onGloballyPositioned { zone.bounds = it.boundsInRoot() }
        .onFocusChanged {
            zone.hasFocus = it.hasFocus
            if (it.hasFocus) CarZones.focused(zone)
        }
        .focusRequester(zone.requester)
        .focusRestorer()
        .focusGroup()
}
