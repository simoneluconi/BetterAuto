package com.simoneluconi.betterauto.projection

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.simoneluconi.betterauto.ui.launcher.LauncherFocus

/** The launcher UI on the car's virtual display. A Presentation is a Dialog, so it gets its own lifecycle for Compose. */
class LauncherPresentation(context: Context, display: Display) :
    Presentation(context, display, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen),
    LifecycleOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private lateinit var composeView: ComposeView
    private val focus = LauncherFocus()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        composeView = ComposeView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            // Focusable, it takes the window's focus back whenever a touch clears Compose's: Android then drew its
            // default focus highlight over the whole launcher, 16% white (the gray veil over phone apps, lighter
            // keyboards) until the knob moved. Our own rings show the focus
            defaultFocusHighlightEnabled = false
            setContent { ProjectedLauncher(focus) }
        }
        setContentView(composeView)
        composeView.requestFocus()
        window?.decorView?.viewTreeObserver?.addOnDrawListener(settleOnDraw)
    }

    /**
     * Once the screen stops changing it is drawn once more. The encoder drops frames faster than the car's
     * rate, often the last of an animation, and its repeats then kept showing one from halfway (the arrival
     * panel half faded out) until something else redrew. Only after a frame that came close behind another:
     * a lone one (the music's progress, every second) is never dropped, and settling it too added a frame a second.
     */
    private val settle = Runnable {
        settling = true
        window?.decorView?.invalidate()
    }
    private var settling = false
    private var lastDrawAt = 0L
    private val settleOnDraw = ViewTreeObserver.OnDrawListener {
        val decor = window?.decorView ?: return@OnDrawListener
        val now = SystemClock.uptimeMillis()
        val quick = now - lastDrawAt < QUICK_FRAME_MS
        lastDrawAt = now
        decor.removeCallbacks(settle)
        // The settling draw itself asks for no other
        if (settling) settling = false else if (quick) decor.postDelayed(settle, SETTLE_MS)
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        window?.decorView?.let {
            it.removeCallbacks(settle)
            it.viewTreeObserver.removeOnDrawListener(settleOnDraw)
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onStop()
    }

    /**
     * Stopped while the car shows its own screen: Compose's frame clock follows the lifecycle, so nothing
     * recomposes, animates or draws for frames the encoder would throw away. Back to resumed, it catches up at once.
     */
    fun setActive(active: Boolean) {
        val current = lifecycleRegistry.currentState
        if (!current.isAtLeast(Lifecycle.State.CREATED)) return // not shown yet, or already gone
        lifecycleRegistry.currentState = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
    }

    fun dispatchTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) focus.clearFocus()
        window?.decorView?.dispatchTouchEvent(event)
    }

    fun dispatchKey(event: KeyEvent) {
        // Keys are injected straight into the view tree, so leave touch mode ourselves or nothing can show focus
        if (composeView.isInTouchMode) composeView.requestFocusFromTouch()
        // The first knob/d-pad move only reveals the focus, like on any car UI
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in NavigationKeys && focus.ensureFocused()) {
            swallowUp = event.keyCode
            return
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowUp) {
            swallowUp = null
            return
        }
        // Compose drops keys while nothing is focused: back must still work after the driver used touch
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) focus.ensureFocused()
        window?.decorView?.dispatchKeyEvent(event)
    }

    private var swallowUp: Int? = null

    private companion object {
        /** Well past one frame at the car's rate (30 fps at the least), so this one is kept. */
        const val SETTLE_MS = 120L

        /** Closer than this to the previous draw, a frame may be dropped (one frame at 30 fps, with some jitter). */
        const val QUICK_FRAME_MS = 45L

        private val NavigationKeys = setOf(
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        )
    }
}
