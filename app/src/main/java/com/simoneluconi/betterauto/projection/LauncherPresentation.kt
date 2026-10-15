package com.simoneluconi.betterauto.projection

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
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
import com.simoneluconi.betterauto.ui.launcher.LauncherRoot

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
            setContent { LauncherRoot(focus = focus) }
        }
        setContentView(composeView)
        composeView.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onStop()
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
        val NavigationKeys = setOf(
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        )
    }
}
