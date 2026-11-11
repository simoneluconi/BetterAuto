package com.simoneluconi.betterauto.aap

import aap_protobuf.service.control.message.ChannelOpenRequestOuterClass.ChannelOpenRequest
import aap_protobuf.service.control.message.ChannelOpenResponseOuterClass.ChannelOpenResponse
import aap_protobuf.service.control.message.ControlMessageTypeOuterClass.ControlMessageType
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import com.google.protobuf.MessageLite
import com.simoneluconi.betterauto.EventLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.Closeable
import kotlin.coroutines.CoroutineContext

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
        when {
            msg.controlType && msg.id == ControlMessageType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE -> {
                val status = ChannelOpenResponse.parseFrom(msg.payload).status
                EventLog.log("Channel $channelId ($name) opened: $status")
                if (status == MessageStatus.STATUS_SUCCESS) onOpened()
            }
            msg.controlType && msg.id == ControlMessageType.MESSAGE_CHANNEL_CLOSE_NOTIFICATION_VALUE -> {
                EventLog.log("Channel $channelId ($name) closed by the head unit")
                runCatching { close() }
            }
            else -> onChannelMessage(msg)
        }
    }

    protected abstract fun onOpened()

    protected abstract fun onChannelMessage(msg: AapMessage)

    protected fun send(id: Int, message: MessageLite, controlType: Boolean = false) =
        send(id, message.toByteArray(), controlType)

    protected fun send(id: Int, payload: ByteArray, controlType: Boolean = false) =
        session.send(channelId, id, payload, controlType = controlType)

    /**
     * A scope for the channel's own jobs. A send fails when the link drops under it (the socket closed as the
     * session ends): that ends the job, logged, instead of crashing the whole app.
     */
    protected fun channelScope(dispatcher: CoroutineContext): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> EventLog.log("Channel $channelId ($name): stopped, $e") })

    override fun close() = Unit
}
