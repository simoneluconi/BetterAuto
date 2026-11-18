package com.simoneluconi.betterauto.transport.wireless

import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.simoneluconi.betterauto.AapService
import com.simoneluconi.betterauto.EventLog

/**
 * A wireless session cut by Android killing the app (for memory: some phones do it even to the app on the
 * car's screen) is connected again when the app starts next, which the system does by itself while the car
 * is still there. The car connecting over Bluetooth again does not happen: it never went away.
 */
object WirelessResume {
    private const val KEY_DEVICE = "live_session_device"
    /** Later than this the car has most likely been left: the app was started by the user, not by the system. */
    private const val RESUME_WITHIN_MS = 3 * 60_000L

    /** The kills that say nothing about the user: they did not close the app, and it did not crash. */
    private val Killed = setOf(
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_FREEZER,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
    )

    /** A wireless session with [address] got through the setup: until [ended], the app dying means it was cut. */
    fun running(context: Context, address: String) = context.wirelessPrefs().edit { putString(KEY_DEVICE, address) }

    fun ended(context: Context) = context.wirelessPrefs().edit { remove(KEY_DEVICE) }

    /**
     * At the app's start, with how the last run ended (null when Android doesn't say): connects again if
     * that run was killed during a wireless session a moment ago and auto-connect is on for the car.
     */
    fun afterStart(context: Context, exit: ApplicationExitInfo?) {
        val address = context.wirelessPrefs().getString(KEY_DEVICE, null) ?: return
        ended(context)
        val resume = exit != null &&
            shouldResume(exit.reason, exit.timestamp, System.currentTimeMillis(), WirelessAutoConnect.isWatched(context, address))
        if (!resume) return
        EventLog.log("Wireless: Android ended the app during the session, connecting again")
        runCatching {
            context.startForegroundService(
                Intent(context, AapService::class.java).setAction(AapService.ACTION_CONNECT_WIRELESS)
                    .putExtra(AapService.EXTRA_DEVICE, address)
                    .putExtra(AapService.EXTRA_AUTO, true)
            )
        }.onFailure { EventLog.log("Wireless: could not connect again: $it") }
    }

    /** A run that ended for [reason] at [exitedAt] (epoch ms) during a session is connected again at [now]. */
    internal fun shouldResume(reason: Int, exitedAt: Long, now: Long, autoConnect: Boolean): Boolean =
        autoConnect && reason in Killed && now - exitedAt in 0..RESUME_WITHIN_MS
}
