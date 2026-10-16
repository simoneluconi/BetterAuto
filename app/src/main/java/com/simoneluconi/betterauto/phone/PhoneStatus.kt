package com.simoneluconi.betterauto.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class PhoneStatus(
    val time: String,
    val date: String,
    /** The moment [time] and [date] describe, for other formats. */
    val at: LocalDateTime = LocalDateTime.now(),
    val batteryPercent: Int?,
    val charging: Boolean,
    /** 0..4, null when unknown (no SIM, airplane mode). */
    val signalLevel: Int?,
    val onWifi: Boolean,
)

/**
 * Clock and phone indicators for the status bar, driven by the system's own events: the minute tick,
 * the battery, the signal strength and the default network. Nothing is polled.
 */
@Composable
fun rememberPhoneStatus(): PhoneStatus {
    val context = LocalContext.current.applicationContext
    var status by remember { mutableStateOf(initial(context)) }
    DisposableEffect(context) {
        val main = Handler(Looper.getMainLooper())
        val clock = receiver { status = status.withClock(context) }
        val battery = receiver { intent -> status = status.withBattery(intent) }
        register(context, clock, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        register(context, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val telephony = context.getSystemService(TelephonyManager::class.java)
        val stopSignal = telephony?.let { watchSignal(context, it) { level -> status = status.copy(signalLevel = level) } }

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                main.post { status = status.copy(onWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) }
            }

            override fun onLost(network: Network) {
                main.post { status = status.copy(onWifi = false) }
            }
        }
        runCatching { connectivity?.registerDefaultNetworkCallback(network) }

        onDispose {
            context.unregisterReceiver(clock)
            context.unregisterReceiver(battery)
            stopSignal?.invoke()
            runCatching { connectivity?.unregisterNetworkCallback(network) }
        }
    }
    return status
}

private val Time24: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val Time12: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm")
private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

private fun initial(context: Context): PhoneStatus {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val signal = runCatching { context.getSystemService(TelephonyManager::class.java)?.signalStrength?.level }.getOrNull()
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val wifi = runCatching {
        connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)
    val status = PhoneStatus(time = "", date = "", batteryPercent = null, charging = false, signalLevel = signal, onWifi = wifi)
    return (battery?.let { status.withBattery(it) } ?: status).withClock(context)
}

private fun PhoneStatus.withClock(context: Context): PhoneStatus {
    val now = LocalDateTime.now()
    return copy(
        time = now.format(if (DateFormat.is24HourFormat(context)) Time24 else Time12),
        date = now.format(DayFormat),
        at = now,
    )
}

private fun PhoneStatus.withBattery(intent: Intent): PhoneStatus {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return copy(
        batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
        charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
    )
}

private inline fun receiver(crossinline onReceive: (Intent) -> Unit) = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
}

/** System broadcasts only the system can send: exporting the receiver gives nothing away. */
private fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    } else {
        context.registerReceiver(receiver, filter)
    }
}

/** Signal bars from the telephony service, on the main thread; returns how to stop watching. */
private fun watchSignal(context: Context, telephony: TelephonyManager, onLevel: (Int?) -> Unit): () -> Unit =
    if (Build.VERSION.SDK_INT >= 31) {
        val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = onLevel(signalStrength.level)
        }
        runCatching { telephony.registerTelephonyCallback(context.mainExecutor, callback) }
        val stop = { runCatching { telephony.unregisterTelephonyCallback(callback) }; Unit }
        stop
    } else {
        @Suppress("DEPRECATION")
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) = onLevel(signalStrength?.level)
        }
        @Suppress("DEPRECATION")
        runCatching { telephony.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) }
        val stop = {
            @Suppress("DEPRECATION")
            runCatching { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }
            Unit
        }
        stop
    }
