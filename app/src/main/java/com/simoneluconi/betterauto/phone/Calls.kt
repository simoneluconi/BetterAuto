package com.simoneluconi.betterauto.phone

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import android.telecom.VideoProfile
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.settings.LauncherSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class CallPhase { RINGING, DIALING, ACTIVE, HOLDING, ENDED }

/** The call shown on the car screen. */
data class CallInfo(
    val id: Int,
    val phase: CallPhase,
    /** Contact name, or the network's caller name; null for unknown numbers. */
    val name: String?,
    val number: String?,
    val photo: Uri?,
    /** "Mobile", "Work", "Home"... of the number, when it belongs to a contact. */
    val numberLabel: String?,
    val company: String?,
    /** Wall-clock time the call was answered, for the timer; 0 before that. */
    val connectedAt: Long,
    val canHold: Boolean,
    val muted: Boolean,
    /** Other calls going on at the same time (call waiting, a call on hold). */
    val others: Int,
)

/**
 * Phone calls, seen through [CarInCallService]: Android binds it for every call because BetterAuto
 * is a call companion app, so calls can be answered and controlled without being the phone app.
 * All methods run on the main thread.
 */
object Calls {
    private val _current = MutableStateFlow<CallInfo?>(null)
    val current: StateFlow<CallInfo?> = _current

    /** The driver put the call screen away; it comes back for the next ringing call. */
    val minimized = MutableStateFlow(false)

    /** True while Android has our in-call service bound (there is at least one call). */
    val serviceBound = MutableStateFlow(false)

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val calls = ArrayList<Call>()
    private val contacts = HashMap<String, Contact?>()
    private var service: CarInCallService? = null
    private var appContext: Context? = null
    private var muted = false
    private var lastShown: CallInfo? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            EventLog.log("Call ${call.tag()}: ${stateName(state)}")
            publish()
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
    }

    internal fun onServiceBound(service: CarInCallService) {
        this.service = service
        appContext = service.applicationContext
        serviceBound.value = true
        EventLog.log("Calls: in-call service bound")
    }

    internal fun onServiceUnbound() {
        service = null
        serviceBound.value = false
        EventLog.log("Calls: in-call service unbound")
    }

    internal fun onCallAdded(call: Call) {
        calls += call
        call.registerCallback(callback, main)
        EventLog.log("Call ${call.tag()} added: ${stateName(call.stateCompat())}, ${call.details.handle?.schemeSpecificPart?.let(::mask)}")
        if (call.stateCompat() == Call.STATE_RINGING) minimized.value = false
        publish()
    }

    internal fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        calls -= call
        EventLog.log("Call ${call.tag()} removed: ${call.details.disconnectCause?.let { "${it.code} ${it.reason}" }}")
        publish()
    }

    internal fun onAudioState(state: CallAudioState?) {
        muted = state?.isMuted == true
        EventLog.log("Calls: audio route ${state?.route?.let(CallAudioState::audioRouteToString)}, muted $muted")
        publish()
    }

    @RequiresApi(34)
    internal fun onEndpoint(endpoint: CallEndpoint) {
        val type = when (endpoint.endpointType) {
            CallEndpoint.TYPE_EARPIECE -> "earpiece"
            CallEndpoint.TYPE_BLUETOOTH -> "bluetooth"
            CallEndpoint.TYPE_WIRED_HEADSET -> "wired headset"
            CallEndpoint.TYPE_SPEAKER -> "speaker"
            CallEndpoint.TYPE_STREAMING -> "streaming"
            else -> "unknown"
        }
        EventLog.log("Calls: audio endpoint $type")
    }

    internal fun onMuted(value: Boolean) {
        muted = value
        EventLog.log("Calls: muted $muted")
        publish()
    }

    /** Development: a pretend call, so the call screen can be tried without phoning anyone. */
    private var simulated: CallInfo? = null

    fun simulate(phase: CallPhase?, name: String? = "Mario Rossi", number: String = "+39 333 123 4567") {
        val info = phase?.let {
            CallInfo(
                id = -1, phase = it, name = name, number = number, photo = null,
                numberLabel = "Cellulare", company = "Officina Rossi",
                connectedAt = if (it == CallPhase.ACTIVE || it == CallPhase.HOLDING) System.currentTimeMillis() else 0L,
                canHold = true, muted = false, others = 0,
            )
        }
        EventLog.log("Calls: simulated call ${phase ?: "removed"}")
        simulated = info
        if (phase == CallPhase.RINGING) minimized.value = false
        _current.value = info
        if (phase == CallPhase.ENDED) main.postDelayed({ if (_current.value === info) _current.value = null }, ENDED_SHOW_MS)
    }

    private fun updateSimulated(change: (CallInfo) -> CallInfo?): Boolean {
        val sim = simulated?.takeIf { _current.value === it } ?: return false
        val next = change(sim)
        simulated = next
        _current.value = next
        if (next?.phase == CallPhase.ENDED) main.postDelayed({ if (_current.value === next) _current.value = null }, ENDED_SHOW_MS)
        return true
    }

    fun answer() {
        if (updateSimulated { it.copy(phase = CallPhase.ACTIVE, connectedAt = System.currentTimeMillis()) }) return
        val call = calls.firstOrNull { it.stateCompat() == Call.STATE_RINGING } ?: return
        EventLog.log("Call ${call.tag()}: answer")
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    /** Rejects a ringing call, or hangs up the one in progress. */
    fun end() {
        if (updateSimulated { it.copy(phase = CallPhase.ENDED) }) return
        val call = primary() ?: return
        if (call.stateCompat() == Call.STATE_RINGING) {
            EventLog.log("Call ${call.tag()}: reject")
            call.reject(false, null)
        } else {
            EventLog.log("Call ${call.tag()}: hang up")
            call.disconnect()
        }
    }

    fun toggleMute() {
        if (updateSimulated { it.copy(muted = !it.muted) }) return
        val s = service ?: return
        EventLog.log("Calls: mute ${!muted}")
        s.setMuted(!muted)
    }

    fun toggleHold() {
        if (updateSimulated { it.copy(phase = if (it.phase == CallPhase.HOLDING) CallPhase.ACTIVE else CallPhase.HOLDING) }) return
        val call = primary() ?: return
        when (call.stateCompat()) {
            Call.STATE_HOLDING -> call.unhold().also { EventLog.log("Call ${call.tag()}: resume") }
            Call.STATE_ACTIVE -> call.hold().also { EventLog.log("Call ${call.tag()}: hold") }
        }
    }

    fun dtmf(digit: Char) {
        val call = primary() ?: return
        call.playDtmfTone(digit)
        main.postDelayed({ call.stopDtmfTone() }, 150)
    }

    /** Starts a call through the phone's own dialer; the audio goes to the car over Bluetooth. */
    fun place(context: Context, number: String) {
        if (!PhoneBook.canCall(context)) {
            EventLog.log("Calls: cannot call, CALL_PHONE not granted")
            return
        }
        EventLog.log("Calls: placing call to ${mask(number)}")
        runCatching {
            context.getSystemService(TelecomManager::class.java)
                .placeCall(Uri.fromParts("tel", number, null), Bundle())
        }.onFailure { EventLog.log("Calls: place failed: $it") }
    }

    /** The call that matters most: ringing first, then the active one, then any other. */
    private fun primary(): Call? =
        calls.firstOrNull { it.stateCompat() == Call.STATE_RINGING }
            ?: calls.firstOrNull { it.stateCompat() == Call.STATE_ACTIVE }
            ?: calls.firstOrNull { it.stateCompat() in setOf(Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_NEW) }
            ?: calls.firstOrNull { it.stateCompat() != Call.STATE_DISCONNECTED }

    private fun publish() {
        val call = primary()
        if (call == null) {
            // Show "call ended" for a moment instead of vanishing
            val last = lastShown
            if (last != null && last.phase != CallPhase.ENDED) {
                val ended = last.copy(phase = CallPhase.ENDED)
                _current.value = ended
                lastShown = ended
                main.postDelayed({ if (_current.value === ended) _current.value = null }, ENDED_SHOW_MS)
            }
            return
        }
        val details = call.details
        val number = details.handle?.schemeSpecificPart
        val contact = number?.let { contactFor(it) }
        val phase = when (call.stateCompat()) {
            Call.STATE_RINGING -> CallPhase.RINGING
            Call.STATE_ACTIVE -> CallPhase.ACTIVE
            Call.STATE_HOLDING -> CallPhase.HOLDING
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> CallPhase.ENDED
            else -> CallPhase.DIALING
        }
        val info = CallInfo(
            id = System.identityHashCode(call),
            phase = phase,
            name = contact?.name ?: details.callerDisplayName?.takeIf { it.isNotBlank() }
                ?: if (Build.VERSION.SDK_INT >= 30) details.contactDisplayName?.takeIf { it.isNotBlank() } else null,
            number = number,
            photo = contact?.photoLarge ?: contact?.photo,
            numberLabel = contact?.numberLabel,
            company = contact?.company,
            connectedAt = details.connectTimeMillis.takeIf { it > 0 } ?: 0L,
            canHold = details.can(Call.Details.CAPABILITY_HOLD) || details.can(Call.Details.CAPABILITY_SUPPORT_HOLD),
            muted = muted,
            others = calls.count { it !== call && it.stateCompat() != Call.STATE_DISCONNECTED },
        )
        _current.value = info
        lastShown = info
    }

    /** Contact for a number, looked up once in the background; the call is republished when found. */
    private fun contactFor(number: String): Contact? {
        if (contacts.containsKey(number)) return contacts[number]
        val context = appContext ?: return null
        contacts[number] = null
        scope.launch {
            val order = LauncherSettings.get(context).nameOrder.value
            val found = PhoneBook.lookup(context, number, order)
            contacts[number] = found
            if (found != null) publish()
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun Call.stateCompat(): Int = if (Build.VERSION.SDK_INT >= 31) details.state else state

    private fun Call.tag() = Integer.toHexString(System.identityHashCode(this))

    /** Numbers are personal data: the log keeps only the last digits. */
    private fun mask(number: String) = if (number.length <= 3) number else "…" + number.takeLast(3)

    private fun stateName(state: Int) = when (state) {
        Call.STATE_NEW -> "new"
        Call.STATE_RINGING -> "ringing"
        Call.STATE_DIALING -> "dialing"
        Call.STATE_CONNECTING -> "connecting"
        Call.STATE_ACTIVE -> "active"
        Call.STATE_HOLDING -> "holding"
        Call.STATE_DISCONNECTING -> "disconnecting"
        Call.STATE_DISCONNECTED -> "disconnected"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "select account"
        Call.STATE_PULLING_CALL -> "pulling"
        Call.STATE_AUDIO_PROCESSING -> "audio processing"
        Call.STATE_SIMULATED_RINGING -> "simulated ringing"
        else -> "state $state"
    }

    private const val ENDED_SHOW_MS = 2500L
}
