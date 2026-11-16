package com.simoneluconi.betterauto.audio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.simoneluconi.betterauto.EventLog
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * BetterAuto's own shell process, for phones where Shizuku cannot start [AudioRouterService] as a user service.
 * Shizuku's starter makes an Application for the app (LoadedApk.makeApplication), and some systems' hooks in
 * there fail outside a real app process (ColorOS 13 reads the process name, which is null there), so the
 * service never comes up. Here Shizuku runs `app_process` as the shell user with our APK as its class path:
 * [ShellMain] builds the same service without an Application and hands its binder to [ShellServiceProvider].
 *
 * Lifetime as a user service that is not a daemon: the process ends when the app's dies (it watches the app's
 * binder from before it serves anything), or when the audio is done with it ([stop]). The audio policies die with
 * it, so the phone's speaker comes back whatever ends it.
 */
internal object ShizukuShellProcess {
    private class Running(val router: IAudioRouter)

    /** One handshake: the process started with [nonce] brings its binder here. */
    private class Pending {
        val ready = CountDownLatch(1)
        @Volatile
        var service: IBinder? = null
        @Volatile
        var pid = 0
    }

    private val lock = Any()
    private var running: Running? = null
    private val pending = ConcurrentHashMap<String, Pending>()

    /** The running service, or a new process started for it; null if it does not come up within [timeoutMs]. */
    fun obtain(context: Context, timeoutMs: Long): IAudioRouter? = synchronized(lock) {
        running?.let { if (it.router.asBinder().isBinderAlive) return it.router }
        running = null
        start(context, timeoutMs)?.also { running = it }?.router
    }

    /** Ends the process serving [router], if it is still the current one: every route stops with it. */
    fun stop(router: IAudioRouter) {
        val ended = synchronized(lock) {
            running?.takeIf { it.router === router }?.also { running = null }
        } ?: return
        // destroy() stops the routes and ends the process in the middle of the call: the call itself fails
        runCatching { ended.router.destroy() }
    }

    private fun start(context: Context, timeoutMs: Long): Running? {
        val shizuku = Shizuku.getBinder()?.let(IShizukuService.Stub::asInterface) ?: return null
        val nonce = UUID.randomUUID().toString()
        val wait = Pending()
        pending[nonce] = wait
        try {
            val arguments = listOf(authority(context), nonce, (Process.myUid() / PER_USER_RANGE).toString())
            val command = command(context.applicationInfo.sourceDir, "${context.packageName}:shell", ShellMain::class.java.name, arguments)
            // Shizuku's newProcess is no longer public in its API library, but still in its service's interface
            val process = runCatching { shizuku.newProcess(arrayOf("/system/bin/sh", "-c", command), null, null) }
                .onFailure { EventLog.log("Shizuku: own shell process not started: $it") }
                .getOrNull() ?: return null
            relay(process)
            if (!wait.ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                // Late, it finds no one waiting for it and ends by itself
                EventLog.log("Shizuku: own shell process did not hand its service over")
                return null
            }
            val binder = wait.service ?: return null
            binder.linkToDeath({
                EventLog.log("Shizuku: own shell process gone")
                synchronized(lock) { if (running?.router?.asBinder() === binder) running = null }
            }, 0)
            EventLog.log("Shizuku: audio service running in our own shell process (pid ${wait.pid})")
            return Running(IAudioRouter.Stub.asInterface(binder))
        } catch (e: Exception) {
            EventLog.log("Shizuku: own shell process failed: $e")
            return null
        } finally {
            pending.remove(nonce)
        }
    }

    /** What the process writes (why it gave up, mostly) goes to the log; reading also keeps its pipes from filling. */
    private fun relay(process: IRemoteProcess) {
        for ((name, open) in listOf("out" to { process.inputStream }, "err" to { process.errorStream })) {
            val pipe = runCatching { open() }.getOrNull() ?: continue
            thread(name = "shell-process-$name", isDaemon = true) {
                runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().useLines { lines ->
                        lines.forEachIndexed { i, line -> if (i < MAX_LOGGED_LINES) EventLog.log("Shizuku: shell process: $line") }
                    }
                }
            }
        }
    }

    /** From [ShellServiceProvider]: the binder of the process started with [nonce]; false if none waits for it. */
    fun deliver(nonce: String, service: IBinder, pid: Int): Boolean {
        val wait = pending[nonce] ?: return false
        wait.pid = pid
        wait.service = service
        wait.ready.countDown()
        return true
    }

    /** Only the shell user (or root, with Shizuku started as root) may hand a service over. */
    fun trustedCaller(uid: Int) = uid == SHELL_UID || uid == ROOT_UID

    fun authority(context: Context) = "${context.packageName}.shellservice"

    /**
     * The shell command that starts [mainClass] from [apk] as a plain Java program (app_process "tool" mode,
     * whose stdout and stderr stay the process' own). In the background, out of the shell Shizuku holds:
     * Shizuku kills the processes it started as soon as the app dies, before the service could unregister its
     * routes, and the audio device vanishing at once made the phone pause the music ("becoming noisy"). The
     * service ends by itself when the app dies, cleanly, as a user service does.
     */
    fun command(apk: String, niceName: String, mainClass: String, arguments: List<String>): String =
        "CLASSPATH=${quote(apk)} /system/bin/app_process /system/bin --nice-name=${quote(niceName)} " +
            (listOf(mainClass) + arguments).joinToString(" ") { quote(it) } + " &"

    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    /**
     * Once Shizuku's user service failed here and this process worked, the next times go straight to this
     * process (no 5 s wait first) until the phone's system or Shizuku is updated, which may have fixed it.
     */
    fun preferred(context: Context) = prefs(context).getString(KEY_PHONE, null) == phoneKey(context)

    fun remember(context: Context) = prefs(context).edit().putString(KEY_PHONE, phoneKey(context)).apply()

    fun forget(context: Context) = prefs(context).edit().remove(KEY_PHONE).apply()

    private fun phoneKey(context: Context): String {
        val shizuku = runCatching { context.packageManager.getPackageInfo(ShizukuAudio.PACKAGE, 0).longVersionCode }.getOrDefault(0L)
        return "${Build.FINGERPRINT}|$shizuku"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "shizuku_shell"
    private const val KEY_PHONE = "own_process_for"
    private const val MAX_LOGGED_LINES = 20
    private const val PER_USER_RANGE = 100000
    private const val SHELL_UID = 2000
    private const val ROOT_UID = 0
}

/**
 * Where [ShellMain] hands its service to the app. Exported for the shell user only: the permission keeps apps
 * out (the shell holds it), and [call] checks the caller's uid besides.
 */
class ShellServiceProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_ATTACH || extras == null) return null
        val uid = Binder.getCallingUid()
        if (!ShizukuShellProcess.trustedCaller(uid)) {
            EventLog.log("Shizuku: a service from uid $uid refused")
            return null
        }
        val nonce = extras.getString(EXTRA_NONCE) ?: return null
        val service = extras.getBinder(EXTRA_SERVICE) ?: return null
        if (!ShizukuShellProcess.deliver(nonce, service, extras.getInt(EXTRA_PID))) return null
        // The process watches this binder: it ends when this process does
        return Bundle().apply { putBinder(EXTRA_APP, ShizukuAudio.appToken) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val METHOD_ATTACH = "attach"
        const val EXTRA_NONCE = "nonce"
        const val EXTRA_SERVICE = "service"
        const val EXTRA_PID = "pid"
        const val EXTRA_APP = "app"
    }
}
