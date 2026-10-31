package com.simoneluconi.betterauto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.simoneluconi.betterauto.car.Timed
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.car.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug builds only: records what the car says about its movement during every session (position, satellites,
 * speed, gear, compass, gyroscope, steering), so a drive done once can be played again on the bench by
 * [TripReplay], tunnels and all. One JSON object per line, `t` = ms from the start. The files stay on the
 * phone (files/trips, the last [KEEP] drives): they hold where the car went.
 */
object TripRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dir: File? = null
    private var out: BufferedWriter? = null
    private var startedAt = 0L
    private var lastFlush = 0L
    private var last = VehicleSnapshot()

    /** A pretend drive or a replay is feeding the car data: not a drive to record. */
    @Volatile var paused = false

    fun start(context: Context) {
        dir = File(context.filesDir, "trips")
        scope.launch {
            VehicleData.snapshot.collect { s ->
                if (s.connected && !paused) record(s) else close()
                last = s
            }
        }
    }

    private fun record(s: VehicleSnapshot) {
        fun changed(a: Timed<*>?, b: Timed<*>?) = a != null && a.at != b?.at
        // What changed, the time filled in once the file is open: a car (or a bench) that says nothing about
        // its movement leaves no file behind
        val lines = ArrayList<(Long) -> String>()
        if (changed(s.position, last.position)) s.position?.value?.let { p ->
            lines += { t -> """{"t":$t,"pos":[${p.latitude},${p.longitude},${p.accuracyM},${p.altitudeM},${p.speedKmh},${p.bearing}]}""" }
        }
        if (changed(s.satellites, last.satellites)) s.satellites?.value?.let { lines += { t -> """{"t":$t,"sat":[${it.inUse},${it.inView}]}""" } }
        if (changed(s.speed, last.speed)) s.speed?.value?.let { lines += { t -> """{"t":$t,"spd":${it.kmh}}""" } }
        if (changed(s.gear, last.gear)) s.gear?.value?.let { lines += { t -> """{"t":$t,"gear":"${it.position.name}"}""" } }
        if (changed(s.motion, last.motion)) s.motion?.value?.let { m ->
            val r = m.rotation
            lines += { t -> """{"t":$t,"mot":[${m.compassBearing},${r?.first},${r?.second},${r?.third},${m.steeringAngle}]}""" }
        }
        if (lines.isEmpty()) return
        val now = System.currentTimeMillis()
        val writer = out ?: open(now) ?: return
        for (line in lines) {
            writer.write(line(now - startedAt))
            writer.newLine()
        }
        if (now - lastFlush > FLUSH_MS) {
            writer.flush()
            lastFlush = now
        }
    }

    private fun open(now: Long): BufferedWriter? {
        val dir = dir ?: return null
        dir.mkdirs()
        // The oldest drives go: only the last few are kept
        dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP - 1)?.forEach { it.delete() }
        val name = "trip-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now))}.jsonl"
        startedAt = now
        lastFlush = now
        last = VehicleSnapshot()
        EventLog.log("Debug: recording the drive's car data to trips/$name")
        return runCatching { File(dir, name).bufferedWriter() }.getOrNull()?.also { out = it }
    }

    private fun close() {
        out?.let {
            runCatching { it.close() }
            EventLog.log("Debug: drive recording closed")
        }
        out = null
    }

    /** The recorded drives, newest first. */
    fun trips(context: Context): List<File> =
        File(context.filesDir, "trips").listFiles()?.sortedByDescending { it.name }.orEmpty()

    private const val KEEP = 20
    private const val FLUSH_MS = 10_000L
}

/** Starts [TripRecorder] with the app, in debug builds only (declared in the debug manifest). */
class TripRecorderStarter : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { TripRecorder.start(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
