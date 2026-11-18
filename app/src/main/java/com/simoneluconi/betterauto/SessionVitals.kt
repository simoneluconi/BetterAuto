package com.simoneluconi.betterauto

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One line a minute while a session runs: the app's memory, the phone's, and the WiFi signal on a wireless
 * link. A session ended by Android killing the app for memory, or by a link that stalled, is explained only
 * by what came before it.
 */
class SessionVitals(private val context: Context, private val wifiSignal: () -> String?) {
    private val stopped = CountDownLatch(1)

    fun start() {
        thread(name = "session-vitals", isDaemon = true) {
            while (!stopped.await(INTERVAL_MS, TimeUnit.MILLISECONDS)) log()
        }
    }

    fun stop() = stopped.countDown()

    /** One line now (also the debug tools'). */
    fun log() = runCatching {
        // Reading the app's own memory takes a moment (it walks its mappings): fine once a minute, off the main thread
        val app = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val phone = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        fun part(name: String) = (app.getMemoryStat(name)?.toLongOrNull() ?: 0) / 1024
        val wifi = wifiSignal()?.let { ", WiFi $it" }.orEmpty()
        EventLog.log(
            "Memory: app ${app.totalPss / 1024} MB (Java ${part("summary.java-heap")}, native ${part("summary.native-heap")}, " +
                "graphics ${part("summary.graphics")}), phone ${phone.availMem.mb()} MB free of ${phone.totalMem.mb()}" +
                (if (phone.lowMemory) ", low" else "") + wifi
        )
    }.onFailure { EventLog.log("Memory: not read ($it)") }

    private fun Long.mb() = this / (1024 * 1024)

    private companion object {
        const val INTERVAL_MS = 60_000L
    }
}
