package com.simoneluconi.betterauto.projection

import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.phone.CallPhase
import com.simoneluconi.betterauto.phone.Calls

/**
 * The car screen: a private virtual display rendering into the encoder surface,
 * with the launcher shown on it as a Presentation. All UI work runs on the main thread.
 */
class Projection(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: LauncherPresentation? = null
    private var width = 0
    private var height = 0
    private var touchDownTime = 0L
    private val keyDownTimes = HashMap<Int, Long>()

    fun start(surface: Surface, width: Int, height: Int, densityDpi: Int) = main.post {
        active = this
        this.width = width
        this.height = height
        try {
            val display = context.getSystemService(DisplayManager::class.java)
                .createVirtualDisplay("BetterAuto", width, height, densityDpi, surface, 0)
            virtualDisplay = display
            NowPlaying.acquire(context)
            presentation = LauncherPresentation(context, display.display).also { it.show() }
            EventLog.log("Projection started: ${width}x$height @ $densityDpi dpi")
        } catch (e: Exception) {
            EventLog.log("Projection failed: $e")
        }
    }

    fun stop() = main.post {
        if (active === this) active = null
        if (presentation != null) NowPlaying.release()
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    /** Converts a head unit touch report into a MotionEvent for the launcher UI. */
    fun dispatchTouch(touch: TouchEvent, touchWidth: Int, touchHeight: Int) = main.post {
        val target = presentation ?: return@post
        val pointers = touch.pointerDataList
        if (pointers.isEmpty()) return@post // MotionEvent needs at least one pointer
        val viewport = CarState.viewportFor(width, height)
        val pointerIndex = touch.actionIndex.coerceIn(0, pointers.lastIndex) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        val action = when (touch.action) {
            PointerAction.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            PointerAction.ACTION_UP -> MotionEvent.ACTION_UP
            PointerAction.ACTION_POINTER_DOWN -> MotionEvent.ACTION_POINTER_DOWN or pointerIndex
            PointerAction.ACTION_POINTER_UP -> MotionEvent.ACTION_POINTER_UP or pointerIndex
            else -> MotionEvent.ACTION_MOVE
        }
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = now

        val properties = Array(pointers.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointers[i].pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pointers.size) { i ->
            val (frameX, frameY) = viewport.touchToFrame(pointers[i].x.toFloat(), pointers[i].y.toFloat(), touchWidth, touchHeight)
            MotionEvent.PointerCoords().apply {
                x = frameX
                y = frameY
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            touchDownTime, now, action, pointers.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        target.dispatchTouch(event)
        event.recycle()
    }

    /** A key from the car (d-pad, knob press, back, media keys); AA keycodes below 65536 are Android's. */
    fun dispatchKey(keyCode: Int, down: Boolean, longPress: Boolean) = main.post {
        val target = presentation ?: return@post
        // Media keys (steering wheel) act on the player directly, whatever has focus
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down) NowPlaying.playPause()
                return@post
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (down) NowPlaying.next()
                return@post
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (down) NowPlaying.previous()
                return@post
            }
            // Steering wheel phone buttons: answer a ringing call, hang up or decline
            KeyEvent.KEYCODE_CALL -> if (Calls.current.value?.phase == CallPhase.RINGING) {
                if (down) Calls.answer()
                return@post
            }
            KeyEvent.KEYCODE_ENDCALL -> if (Calls.current.value != null) {
                if (down) Calls.end()
                return@post
            }
        }
        val now = SystemClock.uptimeMillis()
        if (down) keyDownTimes[keyCode] = now
        val downTime = keyDownTimes[keyCode] ?: now
        val flags = if (longPress) KeyEvent.FLAG_LONG_PRESS else 0
        target.dispatchKey(
            KeyEvent(downTime, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, flags, InputDevice.SOURCE_DPAD)
        )
        if (!down) keyDownTimes.remove(keyCode)
    }

    companion object {
        /** The projection currently on the car screen, if any (used by debug tools). */
        @Volatile
        var active: Projection? = null
            private set
    }

    /** Rotary knob: each detent moves focus to the next (clockwise) or previous control, like Tab / Shift+Tab. */
    fun dispatchRotary(delta: Int) = main.post {
        val target = presentation ?: return@post
        val meta = if (delta < 0) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        repeat(kotlin.math.abs(delta)) {
            val now = SystemClock.uptimeMillis()
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                target.dispatchKey(KeyEvent(now, now, action, KeyEvent.KEYCODE_TAB, 0, meta))
            }
        }
    }
}
