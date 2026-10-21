package com.simoneluconi.betterauto.aap

import aap_protobuf.service.phonestatus.PhoneStatusMessageIdOuterClass.PhoneStatusMessageId
import aap_protobuf.service.phonestatus.message.PhoneStatusInputOuterClass.PhoneStatusInput
import aap_protobuf.service.phonestatus.message.PhoneStatusOuterClass.PhoneStatus
import aap_protobuf.shared.InstrumentClusterInputOuterClass.InstrumentClusterInput.InstrumentClusterAction
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.protobuf.ByteString
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.phone.CallInfo
import com.simoneluconi.betterauto.phone.CallPhase
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.phone.PhoneSignal
import com.simoneluconi.betterauto.phone.maskNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One call as the car is told about it; the duration is worked out when it is sent. */
data class CallStatus(
    val id: Int,
    val state: PhoneStatus.State,
    /** Wall-clock time the call was answered; 0 before that. */
    val connectedAt: Long,
    val number: String?,
    val name: String?,
    /** "Mobile", "Work"... of a contact's number. */
    val numberType: String?,
    /** The contact photo's address, to send its thumbnail once. */
    val photo: String?,
)

/** Everything the phone status channel carries; two equal snapshots are not sent twice. */
data class PhoneStatusSnapshot(val calls: List<CallStatus>, val signal: Int?)

/**
 * Phone status for the car's own displays (instrument cluster, driver info screen): the calls going on,
 * who they are with, and the signal bars, like Android Auto does. Sent when something changes, never on a
 * timer: each update carries the seconds since the call was answered, and the car counts on from there.
 * The caller's photo goes once per call, as a small JPEG.
 *
 * The car can send back a button pressed on its display ([PhoneStatusInput]): call/enter answers a ringing
 * call, the rest is only logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneStatusChannel(session: AapSession, channelId: Int, private val context: Context) : ServiceChannel(session, channelId) {
    override val name = "phone status"

    // Off the main thread: sending blocks on the USB link, so an IO thread, one at a time to keep the order
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val main = Handler(Looper.getMainLooper())
    private var sent: PhoneStatusSnapshot? = null
    /** The photo whose thumbnail went with each call. */
    private val photosSent = HashMap<Int, String>()

    override fun onOpened() {
        val signal = PhoneSignal.of(context).map { it.level }.distinctUntilChanged()
        scope.launch {
            combine(Calls.all, signal) { calls, level -> snapshot(calls, level) }.collect(::sendIfChanged)
        }
    }

    override fun onChannelMessage(msg: AapMessage) {
        when (msg.id) {
            PhoneStatusMessageId.PHONE_STATUS_INPUT_VALUE -> {
                val input = PhoneStatusInput.parseFrom(msg.payload)
                val number = if (input.hasCallerNumber()) ", ${maskNumber(input.callerNumber)}" else ""
                EventLog.log("Phone status: input ${input.input.action}$number")
                main.post { onClusterAction(input.input.action) }
            }
            else -> EventLog.log("Phone status: unhandled message ${msg.id} (${msg.payload.size} bytes)")
        }
    }

    private fun sendIfChanged(snapshot: PhoneStatusSnapshot) {
        if (snapshot == sent) return
        sent = snapshot
        photosSent.keys.retainAll(snapshot.calls.map { it.id }.toSet())
        val thumbnails = photosToSend(snapshot, photosSent).mapNotNull { call ->
            val photo = call.photo ?: return@mapNotNull null
            // Recorded even when it cannot be read, so a broken photo is not tried on every update
            photosSent[call.id] = photo
            thumbnail(photo)?.let { call.id to it }
        }.toMap()
        send(PhoneStatusMessageId.PHONE_STATUS_VALUE, message(snapshot, System.currentTimeMillis(), thumbnails))
        EventLog.log("Phone status: ${describe(snapshot, thumbnails)}")
    }

    /** The contact photo, small: the car shows it as a thumbnail next to the caller. */
    private fun thumbnail(photo: String): ByteString? = runCatching {
        val uri = Uri.parse(photo)
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        bitmap.toCarJpeg(THUMBNAIL_PX, 85)
    }.onFailure { EventLog.log("Phone status: caller photo not readable: $it") }.getOrNull()

    override fun close() = scope.cancel()

    companion object {
        private const val THUMBNAIL_PX = 128

        /** A button on the car's display; on the main thread, like [Calls]. */
        fun onClusterAction(action: InstrumentClusterAction) {
            when (action) {
                // Conservative: only answering. Back is the car's own way out of its menus, not a reject
                InstrumentClusterAction.CALL, InstrumentClusterAction.ENTER ->
                    if (Calls.current.value?.phase == CallPhase.RINGING) Calls.answer()
                else -> Unit
            }
        }

        fun state(call: CallInfo): PhoneStatus.State = when (call.phase) {
            CallPhase.RINGING -> PhoneStatus.State.INCOMING
            // Dialing out is a call under way for the car; its time starts when answered
            CallPhase.DIALING -> PhoneStatus.State.IN_CALL
            CallPhase.ACTIVE -> when {
                call.conference -> PhoneStatus.State.CONFERENCED
                call.muted -> PhoneStatus.State.MUTED
                else -> PhoneStatus.State.IN_CALL
            }
            CallPhase.HOLDING -> PhoneStatus.State.ON_HOLD
            // The moment the phone shows "call ended", then the call goes from the list
            CallPhase.ENDED -> PhoneStatus.State.INACTIVE
        }

        fun snapshot(calls: List<CallInfo>, signal: Int?) = PhoneStatusSnapshot(
            calls.map { call ->
                CallStatus(
                    id = call.id,
                    state = state(call),
                    connectedAt = call.connectedAt,
                    number = call.number?.takeIf { it.isNotBlank() },
                    name = call.name?.takeIf { it.isNotBlank() },
                    numberType = call.numberLabel?.takeIf { it.isNotBlank() },
                    photo = call.photo?.toString(),
                )
            },
            // Android's bars, 0..4: the scale phones and head units share
            signal?.coerceIn(0, 4),
        )

        /** The calls whose photo the car has not had yet (or a different one). */
        fun photosToSend(snapshot: PhoneStatusSnapshot, sent: Map<Int, String>): List<CallStatus> =
            snapshot.calls.filter { it.photo != null && sent[it.id] != it.photo }

        fun message(snapshot: PhoneStatusSnapshot, now: Long, thumbnails: Map<Int, ByteString> = emptyMap()): PhoneStatus {
            val status = PhoneStatus.newBuilder()
            for (call in snapshot.calls) {
                val proto = PhoneStatus.Call.newBuilder()
                    .setPhoneState(call.state)
                    .setCallDurationSeconds(if (call.connectedAt > 0) ((now - call.connectedAt) / 1000).coerceAtLeast(0).toInt() else 0)
                call.number?.let(proto::setCallerNumber)
                call.name?.let(proto::setCallerId)
                call.numberType?.let(proto::setCallerNumberType)
                thumbnails[call.id]?.let(proto::setCallerThumbnail)
                status.addCalls(proto)
            }
            snapshot.signal?.let(status::setSignalStrength)
            return status.build()
        }

        /** For the log, without names, and numbers masked: the log goes to a file too. */
        fun describe(snapshot: PhoneStatusSnapshot, thumbnails: Map<Int, ByteString> = emptyMap()): String {
            val calls = if (snapshot.calls.isEmpty()) {
                "no calls"
            } else {
                snapshot.calls.joinToString("; ") { call ->
                    buildString {
                        append(call.state.name.lowercase().replace('_', ' '))
                        call.number?.let { append(' ').append(maskNumber(it)) }
                        if (call.name != null) append(", named")
                        thumbnails[call.id]?.let { append(", photo ${it.size()} bytes") }
                    }
                }
            }
            return "$calls, signal ${snapshot.signal ?: "unknown"}"
        }
    }
}
