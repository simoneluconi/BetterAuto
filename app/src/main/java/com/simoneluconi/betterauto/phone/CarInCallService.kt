package com.simoneluconi.betterauto.phone

import android.content.Intent
import android.os.IBinder
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

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

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) = Calls.onAudioState(audioState)
}
