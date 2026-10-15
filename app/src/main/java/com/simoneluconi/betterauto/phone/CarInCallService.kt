package com.simoneluconi.betterauto.phone

import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import androidx.annotation.RequiresApi

/**
 * Bound by Android for every call, because the app holds CALL_COMPANION_APP (like smartwatch apps).
 * It shows nothing on the phone: the phone's own dialer keeps its screen. It only feeds [Calls].
 */
class CarInCallService : InCallService() {
    override fun onBind(intent: Intent?): IBinder? {
        Calls.onServiceBound(this)
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Calls.onServiceUnbound()
        return super.onUnbind(intent)
    }

    override fun onCallAdded(call: Call) = Calls.onCallAdded(call)

    override fun onCallRemoved(call: Call) = Calls.onCallRemoved(call)

    // Android 14 split the audio state into an endpoint and a mute callback; the old one serves earlier versions
    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        if (Build.VERSION.SDK_INT < 34) Calls.onAudioState(audioState)
    }

    @RequiresApi(34)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) = Calls.onEndpoint(callEndpoint)

    @RequiresApi(34)
    override fun onMuteStateChanged(isMuted: Boolean) = Calls.onMuted(isMuted)
}
