package com.simoneluconi.betterauto.ui.launcher

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * The knob's nudges move between two zones, the app bar and the screen: a nudge toward the screen
 * lands on its main control (play/pause, the open tab...), one toward the bar lands on the current
 * section. Turning the knob still goes through every control in order.
 */
val LocalContentFocus = staticCompositionLocalOf<FocusRequester?> { null }

/** Marks the main control of the screen in view. Put it on one control per screen. */
fun Modifier.contentFocus(enabled: Boolean = true): Modifier = composed {
    val requester = LocalContentFocus.current
    if (enabled && requester != null) focusRequester(requester) else Modifier
}
