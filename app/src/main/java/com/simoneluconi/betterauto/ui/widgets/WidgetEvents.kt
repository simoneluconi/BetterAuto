package com.simoneluconi.betterauto.ui.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * The current minute, from the system's minute tick (and clock or time zone changes), like the status
 * bar: registered only while a widget showing it is on screen.
 */
@Composable
internal fun rememberMinute(): ZonedDateTime {
    val context = LocalContext.current.applicationContext
    var now by remember { mutableStateOf(currentMinute()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                now = currentMinute()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // System broadcasts only the system can send: exporting the receiver gives nothing away
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        now = currentMinute()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return now
}

private fun currentMinute() = ZonedDateTime.now().truncatedTo(ChronoUnit.MINUTES)

/**
 * A number that goes up whenever the data behind [uri] changes (an event added, a contact starred), to
 * read it again: the observer is registered only while the widget is on screen, and only when [enabled]
 * (no permission, nothing to watch).
 */
@Composable
internal fun rememberContentVersion(uri: Uri, enabled: Boolean = true): Int {
    val context = LocalContext.current.applicationContext
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(context, uri, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                version++
            }
        }
        runCatching { context.contentResolver.registerContentObserver(uri, true, observer) }
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return version
}
