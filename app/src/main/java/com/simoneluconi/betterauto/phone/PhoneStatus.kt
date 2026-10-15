package com.simoneluconi.betterauto.phone

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class PhoneStatus(
    val time: String,
    val date: String,
    val batteryPercent: Int?,
    val charging: Boolean,
    /** 0..4, null when unknown (no SIM, airplane mode). */
    val signalLevel: Int?,
    val onWifi: Boolean,
)

/** Clock and phone indicators for the status bar, refreshed at every minute and a few times in between. */
@Composable
fun rememberPhoneStatus(): PhoneStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(read(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val nextMinute = now - now % 60_000 + 60_000
            delay(minOf(nextMinute, now + INDICATORS_MS) - now + 50)
            status = read(context)
        }
    }
    return status
}

/** Battery, signal and network are polled at this pace; the clock on the minute. */
private const val INDICATORS_MS = 15_000L
private val Time24 = DateTimeFormatter.ofPattern("HH:mm")
private val Time12 = DateTimeFormatter.ofPattern("h:mm")
private val DayFormat = DateTimeFormatter.ofPattern("EEE d MMM")

private fun read(context: Context): PhoneStatus {
    val now = LocalDateTime.now()
    val timeFormat = if (DateFormat.is24HourFormat(context)) Time24 else Time12
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val signal = runCatching {
        context.getSystemService(TelephonyManager::class.java)?.signalStrength?.level
    }.getOrNull()
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val wifi = runCatching {
        connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    return PhoneStatus(
        time = now.format(timeFormat),
        date = now.format(DayFormat),
        batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
        charging = plugged,
        signalLevel = signal,
        onWifi = wifi,
    )
}
