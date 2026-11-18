package com.simoneluconi.betterauto

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.core.content.edit
import com.simoneluconi.betterauto.transport.wireless.WirelessResume
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** Starts what every entry point needs before anything logs: the service, the app, the listeners. */
class BetterAutoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        if (Build.VERSION.SDK_INT >= 30) thread(name = "exit-reason") { logLastExit() }
    }

    /**
     * Why Android ended the app last time, once: a session that stops with nothing in the log was the app
     * killed (some phones' battery savers) or crashing, and only Android knows which. A wireless session it
     * cut is connected again ([WirelessResume]).
     */
    private fun logLastExit() {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
            val exit = getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()
                ?.takeIf { it.timestamp > prefs.getLong(KEY_LAST_EXIT, 0) }
            WirelessResume.afterStart(this, exit)
            if (exit == null) return
            prefs.edit { putLong(KEY_LAST_EXIT, exit.timestamp) }
            // The user closing the app, or an update, says nothing about a problem
            if (exit.reason in Quiet) return
            // How much it held when it was killed: whether the app was big, or the phone short of memory
            val memory = if (exit.pss > 0) ", memory ${exit.pss / 1024} MB (resident ${exit.rss / 1024} MB)" else ""
            EventLog.log(
                "App: the last run ended at ${ExitTime.format(Instant.ofEpochMilli(exit.timestamp))}, ${reasonName(exit.reason)}" +
                    (exit.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                    ", importance ${exit.importance}$memory"
            )
        }.onFailure { EventLog.log("App: last exit reason unknown: $it") }
    }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crashed"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed in native code"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for memory"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for using too much"
        ApplicationExitInfo.REASON_OTHER -> "killed by the system"
        ApplicationExitInfo.REASON_FREEZER -> "killed while frozen"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a process it needed died"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_EXIT_SELF -> "it exited by itself"
        else -> "reason $reason"
    }

    private companion object {
        const val KEY_LAST_EXIT = "last_exit_logged"
        val ExitTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        val Quiet = setOf(
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
        )
    }
}
