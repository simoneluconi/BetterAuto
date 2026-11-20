package com.simoneluconi.betterauto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.MotionEvent
import com.simoneluconi.betterauto.audio.ShizukuAudio
import java.io.File

/**
 * Debug: the display for phone apps (AppDisplayHost, in the Shizuku shell service) without a car, drawn into an
 * image reader here; logs how many frames arrive, so that whether the app keeps running with the phone locked
 * can be read in the log.
 *   --es apps start,com.spotify.music[,1280x720@200]   make the display and start the app there
 *   --es apps key,61           a key (61 Tab, 66 Enter, 19-23 d-pad, 4 back), pressed and let go
 *   --es apps tap,640,360      a finger down and up, in the display's pixels
 *   --es apps shot             the next frame to files/app_display.png
 *   --es apps on / off         the display's own power
 *   --es apps stop             remove it
 * The shell service has one app display: starting one here replaces the car's (PhoneAppDisplay's), whose picture
 * stays black until its app is opened again. Every call to the service is guarded: a service gone (Shizuku stopped)
 * only logs, it never crashes the app from the receiver's thread.
 */
object AppDisplayProbe {
    private var connection: ShizukuAudio.Connection? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var frames = 0
    private var since = 0L
    @Volatile
    private var shotTo: File? = null

    @Synchronized
    fun command(context: Context, command: String) {
        runCatching { perform(context, command) }.onFailure { EventLog.log("Debug apps: $command failed: $it") }
    }

    private fun perform(context: Context, command: String) {
        val parts = command.split(',')
        when (parts[0]) {
            "start" -> start(context, parts.getOrNull(1) ?: return, parts.getOrNull(2) ?: "1280x720@200")
            "key" -> parts.getOrNull(1)?.toIntOrNull()?.let { code ->
                val router = connection?.router ?: return
                router.appDisplayKey(code, true, false)
                router.appDisplayKey(code, false, false)
                EventLog.log("Debug apps: key $code (${router.status()})")
            }
            "tap" -> {
                val router = connection?.router ?: return
                val x = parts.getOrNull(1)?.toFloatOrNull() ?: return
                val y = parts.getOrNull(2)?.toFloatOrNull() ?: return
                router.appDisplayTouch(0, MotionEvent.ACTION_DOWN, x, y)
                router.appDisplayTouch(0, MotionEvent.ACTION_UP, x, y)
                EventLog.log("Debug apps: tap $x,$y (${router.status()})")
            }
            "shot" -> shotTo = File(context.filesDir, "app_display.png")
            "on", "off" -> connection?.router?.let {
                it.setAppDisplayOn(parts[0] == "on")
                EventLog.log("Debug apps: ${it.status()}")
            }
            "stop" -> stop()
        }
    }

    private fun start(context: Context, packageName: String, size: String) {
        stop()
        val (width, height, dpi) = Regex("(\\d+)x(\\d+)@(\\d+)").matchEntire(size)?.destructured?.toList()?.map { it.toInt() }
            ?: return EventLog.log("Debug apps: size $size is not WxH@dpi")
        val newConnection = ShizukuAudio.connect(context, ROUTE)
            ?: return EventLog.log("Debug apps: Shizuku is ${ShizukuAudio.state(context)}, no service")
        connection = newConnection
        val looper = HandlerThread("debug-apps").also { it.start(); thread = it }
        @Suppress("WrongConstant")
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader = newReader
        frames = 0
        since = SystemClock.elapsedRealtime()
        newReader.setOnImageAvailableListener({ source -> onFrame(source, width, height) }, Handler(looper.looper))
        val id = newConnection.router.createAppDisplay(width, height, dpi, newReader.surface, null)
        EventLog.log("Debug apps: display $id (${newConnection.router.status()})")
        if (id < 0) return stop()
        val problem = newConnection.router.launchOnAppDisplay(packageName)
        EventLog.log("Debug apps: launch $packageName: ${problem ?: "ok"} (${newConnection.router.status()})")
    }

    private fun onFrame(source: ImageReader, width: Int, height: Int) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        image.use {
            frames++
            shotTo?.let { file ->
                shotTo = null
                val plane = it.planes[0]
                val bitmap = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                file.outputStream().use { out -> Bitmap.createBitmap(bitmap, 0, 0, width, height).compress(Bitmap.CompressFormat.PNG, 100, out) }
                EventLog.log("Debug apps: frame saved to ${file.name}")
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (now - since >= REPORT_MS) {
            EventLog.log("Debug apps: $frames frames in ${(now - since) / 1000} s")
            frames = 0
            since = now
        }
    }

    @Synchronized
    private fun stop() {
        connection?.let { runCatching { it.router.releaseAppDisplay() }; it.close() }
        connection = null
        reader?.close()
        reader = null
        thread?.quitSafely()
        thread = null
    }

    /** A route id of its own, so the shared Shizuku binding stays up while the display does. */
    private const val ROUTE = 77
    private const val REPORT_MS = 5000L
}
