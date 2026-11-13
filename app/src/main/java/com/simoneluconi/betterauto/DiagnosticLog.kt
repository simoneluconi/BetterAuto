package com.simoneluconi.betterauto

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.simoneluconi.betterauto.audio.ShizukuAudio
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The log as one file to send through the phone's share sheet (mail, a chat...), for whoever looks into a
 * problem: a short header (app, phone, Android, Shizuku), then the log files, oldest first, zipped. Nothing
 * goes out by itself: the driver picks where it goes.
 *
 * The log keeps no positions, contacts or messages, and Bluetooth addresses only by their last two bytes; a
 * whole hardware address still in it (a WiFi access point's) is cut the same way here.
 */
object DiagnosticLog {
    /** In the cache, shared through the FileProvider (res/xml/shared_files.xml); each new one replaces the last. */
    private const val DIR = "diagnostics"
    private val HARDWARE_ADDRESS = Regex("\\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b")

    /** Builds the file (blocking: call it off the main thread) and returns the share sheet that sends it. */
    fun shareIntent(context: Context): Intent {
        val file = build(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostic_subject))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        return Intent.createChooser(send, context.getString(R.string.diagnostic_send))
    }

    private fun build(context: Context): File {
        val dir = File(context.cacheDir, DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        val name = "BetterAuto-log-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val zip = dir.resolve("$name.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("$name.txt"))
            // Not closed on its own: that would close the zip before its entry is done
            val text = out.writer()
            text.write(header(context))
            for (file in EventLog.files()) {
                file.useLines { lines ->
                    lines.forEach {
                        text.write(masked(it))
                        text.write("\n")
                    }
                }
            }
            text.flush()
            out.closeEntry()
        }
        return zip
    }

    private fun header(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return buildString {
            appendLine("BetterAuto ${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)}${if (debug) ", debug" else ""})")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Shizuku: ${runCatching { ShizukuAudio.state(context) }.getOrNull()}")
            appendLine("Made: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
            appendLine()
        }
    }

    /** Every whole hardware address cut to its last two bytes, as the log writes the car's. */
    internal fun masked(line: String): String =
        HARDWARE_ADDRESS.replace(line) { "XX:XX:XX:XX:" + it.value.takeLast(5).uppercase() }
}
