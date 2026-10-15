package com.simoneluconi.betterauto.aap

import aap_protobuf.service.control.message.ChannelOpenRequestOuterClass.ChannelOpenRequest
import aap_protobuf.service.control.message.ChannelOpenResponseOuterClass.ChannelOpenResponse
import aap_protobuf.service.control.message.ControlMessageTypeOuterClass.ControlMessageType
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import com.google.protobuf.MessageLite
import com.simoneluconi.betterauto.EventLog
import java.io.Closeable

/**
 * One head unit service (video, input, ...). The phone opens it with CHANNEL_OPEN_REQUEST,
 * sent on the channel itself as a control-type message, and the head unit answers there.
 */
abstract class ServiceChannel(private val session: AapSession, val channelId: Int) : Closeable {
    abstract val name: String

    fun open() {
        val request = ChannelOpenRequest.newBuilder().setPriority(0).setServiceId(channelId).build()
        send(ControlMessageType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE, request, controlType = true)
    }

    fun onMessage(msg: AapMessage) {
        if (msg.controlType && msg.id == ControlMessageType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE) {
            val status = ChannelOpenResponse.parseFrom(msg.payload).status
            EventLog.log("Channel $channelId ($name) opened: $status")
            if (status == MessageStatus.STATUS_SUCCESS) onOpened()
        } else {
            onChannelMessage(msg)
        }
    }

    protected abstract fun onOpened()

    protected abstract fun onChannelMessage(msg: AapMessage)

    protected fun send(id: Int, message: MessageLite, controlType: Boolean = false) =
        send(id, message.toByteArray(), controlType)

    protected fun send(id: Int, payload: ByteArray, controlType: Boolean = false) =
        session.send(channelId, id, payload, controlType = controlType)

    override fun close() = Unit
}
