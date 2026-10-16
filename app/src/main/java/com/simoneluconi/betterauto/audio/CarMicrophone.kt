package com.simoneluconi.betterauto.audio

import com.simoneluconi.betterauto.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The car's microphone as seen by the rest of the app: available or not, and its PCM stream. */
object CarMicrophone {
    /** Format of the car's microphone, null while the car offers none (or no car is connected). */
    private val _format = MutableStateFlow<PcmFormat?>(null)
    val format: StateFlow<PcmFormat?> = _format

    /** Set by the protocol channel: opens and closes the microphone in the car. */
    internal var control: ((Boolean) -> Unit)? = null

    @Volatile
    private var listener: ((ByteArray) -> Unit)? = null

    internal fun onAvailable(format: PcmFormat?, control: ((Boolean) -> Unit)?) {
        _format.value = format
        this.control = control
        if (format == null) listener = null
    }

    /** Starts the car's microphone; [onPcm] gets its audio on the connection's thread. False without a car mic. */
    fun start(onPcm: (ByteArray) -> Unit): Boolean {
        val open = control ?: return false
        if (_format.value == null) return false
        listener = onPcm
        EventLog.log("Car microphone: open")
        open(true)
        return true
    }

    fun stop() {
        if (listener == null) return
        listener = null
        EventLog.log("Car microphone: close")
        control?.invoke(false)
    }

    internal fun onPcm(data: ByteArray) {
        listener?.invoke(data)
    }
}
