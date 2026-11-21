package com.simoneluconi.betterauto.aap

import aap_protobuf.service.inputsource.InputMessageIdOuterClass.InputMessageId
import aap_protobuf.service.inputsource.InputSourceServiceOuterClass.InputSourceService
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import aap_protobuf.service.media.sink.message.KeyBindingRequestOuterClass.KeyBindingRequest
import aap_protobuf.service.media.sink.message.KeyBindingResponseOuterClass.KeyBindingResponse
import aap_protobuf.service.media.sink.message.KeyCodeOuterClass.KeyCode
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.projection.Projection
import com.simoneluconi.betterauto.projection.TouchpadGestures

/**
 * Touchscreen, keys, rotary knob and touchpad of the head unit. Touches arrive in the touchscreen's own
 * coordinate space ([touchWidth] x [touchHeight]); keys must first be bound with a KeyBindingRequest.
 * A touchpad that navigates (not one mapped onto the screen) steps from control to control, see [TouchpadGestures].
 */
class InputChannel(
    session: AapSession,
    channelId: Int,
    private val touchWidth: Int,
    private val touchHeight: Int,
    private val keycodes: List<Int>,
    private val projection: Projection,
    touchpad: InputSourceService.TouchPad? = null,
) : ServiceChannel(session, channelId) {
    override val name = "input"

    private val gestures = touchpad?.takeUnless { it.uiAbsolute }?.let {
        TouchpadGestures(it.width, if (it.hasPhysicalWidth()) it.physicalWidth else null, it.tapAsSelect)
    }
    private val main = Handler(Looper.getMainLooper())

    override fun onOpened() {
        // A car with its own answer and hang-up keys (an Audi) may have a phone key too, only to show the phone
        projection.phoneKeyHandlesCalls = KeyEvent.KEYCODE_CALL !in keycodes && KeyEvent.KEYCODE_ENDCALL !in keycodes
        if (keycodes.isEmpty()) return
        send(
            InputMessageId.INPUT_MESSAGE_KEY_BINDING_REQUEST_VALUE,
            KeyBindingRequest.newBuilder().addAllKeycodes(keycodes).build(),
        )
    }

    override fun onChannelMessage(msg: AapMessage) {
        when (msg.id) {
            InputMessageId.INPUT_MESSAGE_INPUT_REPORT_VALUE -> onReport(InputReport.parseFrom(msg.payload))
            InputMessageId.INPUT_MESSAGE_KEY_BINDING_RESPONSE_VALUE ->
                EventLog.log("Input: key binding status ${KeyBindingResponse.parseFrom(msg.payload).status}")
            else -> EventLog.log("Input: unhandled message ${msg.id} (${msg.payload.size} bytes)")
        }
    }

    private fun onReport(report: InputReport) {
        if (report.hasTouchEvent()) projection.dispatchTouch(report.touchEvent, touchWidth, touchHeight)
        if (report.hasKeyEvent()) {
            for (key in report.keyEvent.keysList) {
                // Which keys a car really sends, and how (e.g. its voice button only on a long press)
                EventLog.log("Input: key ${key.keycode} ${if (key.down) "down" else "up"}${if (key.longpress) " (long press)" else ""}")
                // A touchpad's own buttons: the first selects, the second goes back
                val keycode = when (key.keycode) {
                    KeyCode.KEYCODE_PRIMARY_BUTTON_VALUE -> KeyEvent.KEYCODE_DPAD_CENTER
                    KeyCode.KEYCODE_SECONDARY_BUTTON_VALUE -> KeyEvent.KEYCODE_BACK
                    else -> key.keycode
                }
                projection.dispatchKey(keycode, key.down, key.longpress)
            }
        }
        if (report.hasRelativeEvent()) {
            for (rel in report.relativeEvent.dataList) {
                if (rel.keycode == KEYCODE_ROTARY_CONTROLLER) projection.dispatchRotary(rel.delta)
                else logUnhandled("relative keycode ${rel.keycode} delta ${rel.delta}")
            }
        }
        if (report.hasTouchpadEvent()) onTouchpad(report.touchpadEvent)
        // Not handled yet: logged (the first ones) to learn what a car sends
        if (report.hasAbsoluteEvent()) logUnhandled("absolute " + report.absoluteEvent.dataList.joinToString { "${it.keycode}=${it.value}" })
    }

    /** The first finger only: one finger drives the focus, a second one is ignored. */
    private fun onTouchpad(event: TouchEvent) {
        val pad = gestures
        val finger = event.pointerDataList.firstOrNull()
        if (pad == null || finger == null) {
            logUnhandled("touchpad ${event.action.name} " + event.pointerDataList.joinToString { "${it.pointerId}@${it.x},${it.y}" })
            return
        }
        val now = SystemClock.uptimeMillis()
        val actions = synchronized(pad) {
            when (event.action) {
                PointerAction.ACTION_DOWN -> pad.down(finger.x, finger.y, now)
                PointerAction.ACTION_MOVED -> pad.move(finger.x, finger.y, now)
                PointerAction.ACTION_UP -> pad.up(finger.x, finger.y, now)
                else -> emptyList()
            }
        }
        perform(actions)
        if (event.action == PointerAction.ACTION_DOWN) main.postDelayed(::checkLongPress, TouchpadGestures.LONG_PRESS_MS)
    }

    private fun checkLongPress() {
        val pad = gestures ?: return
        perform(synchronized(pad) { pad.tick(SystemClock.uptimeMillis()) })
    }

    /** Steps to the next control one way, and the knob's press. */
    private fun perform(actions: List<TouchpadGestures.Action>) {
        for (action in actions) {
            when (action) {
                is TouchpadGestures.Action.Step -> projection.dispatchStep(
                    when (action.direction) {
                        TouchpadGestures.Direction.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                        TouchpadGestures.Direction.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                        TouchpadGestures.Direction.UP -> KeyEvent.KEYCODE_DPAD_UP
                        TouchpadGestures.Direction.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                    }
                )
                TouchpadGestures.Action.Press -> {
                    projection.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER, down = true, longPress = false)
                    projection.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER, down = false, longPress = false)
                }
                // As a car's knob holds: pressed, then pressed again marked long, then let go
                TouchpadGestures.Action.LongPressStart -> {
                    projection.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER, down = true, longPress = false)
                    projection.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER, down = true, longPress = true)
                }
                TouchpadGestures.Action.LongPressEnd ->
                    projection.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER, down = false, longPress = false)
            }
        }
    }

    private var unhandledLogged = 0

    private fun logUnhandled(what: String) {
        if (unhandledLogged >= MAX_UNHANDLED_LOGGED) return
        unhandledLogged++
        EventLog.log("Input: $what (not handled)")
    }

    private companion object {
        /** aap_protobuf KeyCode.KEYCODE_ROTARY_CONTROLLER */
        const val KEYCODE_ROTARY_CONTROLLER = 65536
        /** Enough to read a few gestures, not a flood for a whole drive. */
        const val MAX_UNHANDLED_LOGGED = 200
    }
}
