package com.simoneluconi.betterauto.aap

import aap_protobuf.service.inputsource.InputMessageIdOuterClass.InputMessageId
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.media.sink.message.KeyBindingRequestOuterClass.KeyBindingRequest
import aap_protobuf.service.media.sink.message.KeyBindingResponseOuterClass.KeyBindingResponse
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.projection.Projection

/**
 * Touchscreen, keys and rotary knob of the head unit. Touches arrive in the touchscreen's own
 * coordinate space ([touchWidth] x [touchHeight]); keys must first be bound with a KeyBindingRequest.
 */
class InputChannel(
    session: AapSession,
    channelId: Int,
    private val touchWidth: Int,
    private val touchHeight: Int,
    private val keycodes: List<Int>,
    private val projection: Projection,
) : ServiceChannel(session, channelId) {
    override val name = "input"

    override fun onOpened() {
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
                projection.dispatchKey(key.keycode, key.down, key.longpress)
            }
        }
        if (report.hasRelativeEvent()) {
            for (rel in report.relativeEvent.dataList) {
                if (rel.keycode == KEYCODE_ROTARY_CONTROLLER) projection.dispatchRotary(rel.delta)
            }
        }
    }

    private companion object {
        /** aap_protobuf KeyCode.KEYCODE_ROTARY_CONTROLLER */
        const val KEYCODE_ROTARY_CONTROLLER = 65536
    }
}
