package com.simoneluconi.betterauto.ui.launcher

/**
 * Lets the host give the launcher its first focus. Compose ignores keys while nothing is focused,
 * so the first knob/d-pad input from the car must place the focus from outside the key pipeline.
 */
class LauncherFocus {
    internal var ensure: (() -> Boolean)? = null

    /** Focuses a default control if nothing has focus; true if it had to (the key that triggered it is consumed). */
    fun ensureFocused(): Boolean = ensure?.invoke() ?: false
}
