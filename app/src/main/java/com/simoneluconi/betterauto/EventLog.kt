package com.simoneluconi.betterauto

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.Writer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Protocol log shown in the app and mirrored to logcat (tag "BetterAuto"). Also kept in a file in the
 * app's storage, so a session in the car can be read afterwards: logcat only holds a few minutes.
 */
object EventLog {
    private const val TAG = "BetterAuto"
    private const val MAX_LINES = 500
    /** Each file holds 1 MB (several car trips); the current one and two older ones are kept, 3 MB at most. */
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024
    private const val KEPT_FILES = 3
    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    /** Where the time starts in a [dateTimeFormat] stamp: the app's log shows the time only. */
    private const val TIME_START = 11

    private val lines = ArrayDeque<String>()
    private val lineListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(String?) -> Unit>()

    @Volatile
    private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "event-log").apply { isDaemon = true } }
    private val queued = AtomicInteger()

    // The open file, on the writer thread only: one open per file, not one per line
    private var out: Writer? = null
    private var outBytes = 0L

    /** Name of the connected head unit, null when idle. */
    @Volatile
    var status: String? = null
        set(value) {
            field = value
            statusListeners.forEach { it(value) }
        }

    /** Starts the file copy: files/log/events.txt (read it with `adb shell run-as <package> cat files/log/events.txt`). */
    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "log").apply { mkdirs() }.resolve("events.txt")
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val stamp = LocalDateTime.now().format(dateTimeFormat)
        val line = "${stamp.substring(TIME_START)} $message"
        synchronized(lines) {
            lines.addLast(line)
            if (lines.size > MAX_LINES) lines.removeFirst()
        }
        lineListeners.forEach { it(line) }
        file?.let { target ->
            queued.incrementAndGet()
            writer.execute { append(target, "$stamp $message\n") }
        }
    }

    private fun append(target: File, text: String) {
        try {
            if (outBytes > MAX_FILE_BYTES) {
                runCatching { out?.close() }
                out = null
                rotate(target)
            }
            val writer = out ?: FileOutputStream(target, true).bufferedWriter().also {
                out = it
                outBytes = target.length()
            }
            writer.write(text)
            outBytes += text.length
            // Flushed as soon as no other line waits: in the file within milliseconds, a burst costs one write
            if (queued.decrementAndGet() == 0) writer.flush()
        } catch (_: Exception) {
            queued.set(0)
            runCatching { out?.close() }
            out = null
        }
    }

    /** events.txt -> events.1.txt -> ... ; the oldest goes, so the log never takes more than [KEPT_FILES] MB. */
    private fun rotate(target: File) {
        val dir = target.parentFile ?: return
        dir.resolve("events.${KEPT_FILES - 1}.txt").delete()
        for (i in KEPT_FILES - 2 downTo 1) dir.resolve("events.$i.txt").renameTo(dir.resolve("events.${i + 1}.txt"))
        target.renameTo(dir.resolve("events.1.txt"))
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun addListeners(onLine: (String) -> Unit, onStatus: (String?) -> Unit) {
        lineListeners += onLine
        statusListeners += onStatus
    }

    fun removeListeners(onLine: (String) -> Unit, onStatus: (String?) -> Unit) {
        lineListeners -= onLine
        statusListeners -= onStatus
    }
}
