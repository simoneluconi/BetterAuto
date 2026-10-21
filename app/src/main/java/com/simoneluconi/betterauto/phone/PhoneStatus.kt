package com.simoneluconi.betterauto.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDateTime

data class PhoneStatus(
    /** The current minute: the status bar formats it as the driver chose. */
    val at: LocalDateTime = LocalDateTime.now(),
    val batteryPercent: Int?,
    val charging: Boolean,
    /** 0..4, null when unknown (no SIM, airplane mode). */
    val signalLevel: Int?,
    val onWifi: Boolean,
    /** 5G, 4G+, 4G, H+, 3G, E...; null when unknown or not allowed to read it (phone permission). */
    val network: String? = null,
    /** The other SIM's bars, 0..4, with two SIMs; [signalLevel] and [network] are the SIM used for data. */
    val secondSignalLevel: Int? = null,
)

/** The mobile network of the phone's SIMs. */
data class Signal(
    /** 0..4, null when unknown (no SIM, airplane mode). */
    val level: Int?,
    /** 5G, 4G+, 4G, H+, 3G, E...; null when unknown or not allowed to read it (phone permission). */
    val network: String? = null,
    /** The other SIM's bars, 0..4, with two SIMs; [level] and [network] are the SIM used for data. */
    val secondLevel: Int? = null,
)

/**
 * Signal bars and network type, shared by everyone who shows them (the status bar, the car's phone status
 * channel): the telephony listeners are registered once while someone collects, and stopped after the last.
 */
object PhoneSignal {
    @Volatile
    private var shared: StateFlow<Signal>? = null

    fun of(context: Context): StateFlow<Signal> = shared ?: synchronized(this) {
        shared ?: create(context.applicationContext).also { shared = it }
    }

    private fun create(context: Context): StateFlow<Signal> = callbackFlow {
        // On the main thread, like the telephony callbacks: no locking around the current value
        val (telephony, second) = simTelephony(context)
        var signal = current(context, telephony, second)
        trySend(signal)
        fun update(change: (Signal) -> Signal) {
            signal = change(signal)
            trySend(signal)
        }
        val stopSignal = telephony?.let { watchSignal(context, it) { level -> update { s -> s.copy(level = level) } } }
        val stopNetwork = telephony?.let { watchNetwork(context, it) { label -> update { s -> s.copy(network = label) } } }
        val stopSecond = second?.let { watchSignal(context, it) { level -> update { s -> s.copy(secondLevel = level) } } }
        awaitClose {
            stopSignal?.invoke()
            stopNetwork?.invoke()
            stopSecond?.invoke()
        }
    }.stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        SharingStarted.WhileSubscribed(),
        simTelephony(context).let { (telephony, second) -> current(context, telephony, second) },
    )

    private fun current(context: Context, telephony: TelephonyManager?, second: TelephonyManager?): Signal {
        val network = if (canReadPhoneState(context)) {
            @SuppressLint("MissingPermission")
            val type = runCatching { telephony?.dataNetworkType }.getOrNull()
            type?.let { networkLabel(it) }
        } else {
            null
        }
        return Signal(
            level = runCatching { telephony?.signalStrength?.level }.getOrNull(),
            network = network,
            secondLevel = second?.let { runCatching { it.signalStrength?.level }.getOrNull() ?: 0 },
        )
    }
}

/**
 * Clock and phone indicators for the status bar, driven by the system's own events: the minute tick,
 * the battery, the signal strength ([PhoneSignal]) and the default network. Nothing is polled.
 */
@Composable
fun rememberPhoneStatus(): PhoneStatus {
    val context = LocalContext.current.applicationContext
    var status by remember { mutableStateOf(initial(context)) }
    val signal by PhoneSignal.of(context).collectAsStateWithLifecycle()
    DisposableEffect(context) {
        val main = Handler(Looper.getMainLooper())
        val clock = receiver { status = status.copy(at = LocalDateTime.now()) }
        val battery = receiver { intent -> status = status.withBattery(intent) }
        register(context, clock, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        register(context, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

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
            runCatching { connectivity?.unregisterNetworkCallback(network) }
        }
    }
    return remember(status, signal) {
        status.copy(signalLevel = signal.level, network = signal.network, secondSignalLevel = signal.secondLevel)
    }
}

private fun initial(context: Context): PhoneStatus {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val wifi = runCatching {
        connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)
    // The signal fields come from PhoneSignal
    val status = PhoneStatus(batteryPercent = null, charging = false, signalLevel = null, onWifi = wifi)
    return battery?.let { status.withBattery(it) } ?: status
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
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
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

private fun canReadPhoneState(context: Context) =
    context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

/**
 * The network label (5G, 4G+...) as the phone's own status bar shows it, which needs the phone permission:
 * a callback of its own, so that without it the signal bars still work. Returns how to stop watching.
 */
private fun watchNetwork(context: Context, telephony: TelephonyManager, onLabel: (String?) -> Unit): (() -> Unit)? {
    if (!canReadPhoneState(context)) return null
    return if (Build.VERSION.SDK_INT >= 31) {
        val callback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) =
                onLabel(networkLabel(info.networkType, info.overrideNetworkType))
        }
        runCatching { telephony.registerTelephonyCallback(context.mainExecutor, callback) }
        val stop = { runCatching { telephony.unregisterTelephonyCallback(callback) }; Unit }
        stop
    } else {
        @Suppress("DEPRECATION")
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) =
                onLabel(networkLabel(info.networkType, info.overrideNetworkType))

            @Deprecated("Deprecated in Java")
            override fun onDataConnectionStateChanged(state: Int, networkType: Int) = onLabel(networkLabel(networkType))
        }
        // Android 11 tells the display info; Android 10 only the data connection's technology
        @Suppress("DEPRECATION")
        val events = if (Build.VERSION.SDK_INT >= 30) PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED else PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
        @Suppress("DEPRECATION")
        runCatching { telephony.listen(listener, events) }
        val stop = {
            @Suppress("DEPRECATION")
            runCatching { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }
            Unit
        }
        stop
    }
}

/**
 * The SIM used for data (its bars and network type are the main ones) and, with two SIMs, the other one.
 * Listing the SIMs needs the phone permission: without it, the phone's default SIM alone.
 */
private fun simTelephony(context: Context): Pair<TelephonyManager?, TelephonyManager?> {
    val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null to null
    if (!canReadPhoneState(context)) return telephony to null
    @SuppressLint("MissingPermission")
    val sims = runCatching {
        context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }
    }.getOrDefault(emptyList())
    val data = SubscriptionManager.getDefaultDataSubscriptionId()
    val main = if (data in sims) telephony.createForSubscriptionId(data) else telephony
    val other = sims.firstOrNull { it != data }?.takeIf { sims.size >= 2 }?.let(telephony::createForSubscriptionId)
    return main to other
}
