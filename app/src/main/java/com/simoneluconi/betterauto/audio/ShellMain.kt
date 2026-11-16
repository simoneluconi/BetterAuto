package com.simoneluconi.betterauto.audio

import android.content.AttributionSource
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlin.system.exitProcess

/**
 * The entry point of BetterAuto's own shell process ([ShizukuShellProcess]): `app_process` started by Shizuku
 * as the shell user, with our APK as its class path. It runs no app code but [AudioRouterService]:
 * no Application is made (that is where Shizuku's own starter fails on some systems), the service gets the
 * shell package's context instead, and its binder goes to the app through [ShellServiceProvider]. The reply
 * carries a binder of the app's process: when that dies the service stops every route and this process ends.
 *
 * Arguments: the provider's authority, the handshake nonce, the app's user id. Nothing here may touch the
 * app's files ([com.simoneluconi.betterauto.EventLog] included): this is another user; it logs to logcat
 * and stderr, which the app reads.
 */
object ShellMain {
    private const val TAG = "BetterAuto"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(args.size == 3) { "expected authority, nonce and user, got ${args.size} arguments" }
            val (authority, nonce, user) = args
            @Suppress("DEPRECATION")
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val service = AudioRouterService(shellContext())
            val app = handOver(authority, user.toInt(), nonce, service)
                ?: fail("the app did not take the service")
            // Ends this process when the app's dies, from now on (already dead: at once)
            service.watchApp(app)
            Log.i(TAG, "Shell process: serving the app (uid ${Process.myUid()}, pid ${Process.myPid()})")
            detachOutput()
            Looper.loop()
        } catch (e: Throwable) {
            fail("failed: ${e.cause ?: e}", e)
        }
    }

    /**
     * The pipes to the app (through Shizuku) only matter while starting; Shizuku closes them when the shell that
     * started this process is done with, and a write to a closed pipe would kill the process. Logcat stays.
     */
    private fun detachOutput() {
        runCatching {
            val devNull = Os.open("/dev/null", OsConstants.O_RDWR, 0)
            for (fd in 0..2) Os.dup2(devNull, fd)
            Os.close(devNull)
        }.onFailure { Log.w(TAG, "Shell process: output still on the pipes: $it") }
    }

    private fun fail(message: String, e: Throwable? = null): Nothing {
        Log.w(TAG, "Shell process: $message", e)
        System.err.println(message)
        System.err.flush()
        exitProcess(1)
    }

    /**
     * The system's ActivityThread (what Shizuku's starter sets up too, minus the app's Application) and the
     * shell package's context on it: the audio server and the Bluetooth service want the shell user's own
     * package name next to its uid.
     */
    private fun shellContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        return runCatching { system.createPackageContext(SHELL_PACKAGE, 0) }
            .onFailure { Log.w(TAG, "Shell process: no shell package context, using the system one: $it") }
            .getOrDefault(system)
    }

    /**
     * Gives [service] to the app's [ShellServiceProvider], the way the shell's `content call` reaches a provider
     * (this process is no app the activity manager knows): returns the app's binder from the reply, or null.
     */
    private fun handOver(authority: String, user: Int, nonce: String, service: IBinder): IBinder? {
        val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val holder = activityManager.javaClass.methods
            .first { it.name == "getContentProviderExternal" && it.parameterTypes.size == 4 }
            .invoke(activityManager, authority, user, token, TAG)
            ?: error("no provider $authority")
        try {
            val provider = holder.javaClass.getField("provider").get(holder) ?: error("no provider in the holder")
            val extras = Bundle().apply {
                putString(ShellServiceProvider.EXTRA_NONCE, nonce)
                putBinder(ShellServiceProvider.EXTRA_SERVICE, service)
                putInt(ShellServiceProvider.EXTRA_PID, Process.myPid())
            }
            val reply = callProvider(provider, authority, ShellServiceProvider.METHOD_ATTACH, extras)
            return reply?.getBinder(ShellServiceProvider.EXTRA_APP)
        } finally {
            runCatching {
                activityManager.javaClass.methods
                    .first { it.name == "removeContentProviderExternalAsUser" && it.parameterTypes.size == 3 }
                    .invoke(activityManager, authority, token, user)
            }.recoverCatching {
                activityManager.javaClass.methods
                    .first { it.name == "removeContentProviderExternal" && it.parameterTypes.size == 2 }
                    .invoke(activityManager, authority, token)
            }
        }
    }

    /** IContentProvider.call, whose arguments changed in Android 11 and 12. */
    private fun callProvider(provider: Any, authority: String, method: String, extras: Bundle): Bundle? {
        val calls = Class.forName("android.content.IContentProvider").methods.filter { it.name == "call" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val modern = calls.firstOrNull { it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java }
            if (modern != null) {
                val source = AttributionSource.Builder(Process.myUid()).build()
                return modern.invoke(provider, source, authority, method, null, extras) as Bundle?
            }
        }
        val withTag = calls.firstOrNull { it.parameterTypes.size == 6 && it.parameterTypes[0] == String::class.java }
        if (withTag != null) return withTag.invoke(provider, null, null, authority, method, null, extras) as Bundle?
        val plain = calls.first { it.parameterTypes.size == 5 && it.parameterTypes[0] == String::class.java }
        return plain.invoke(provider, null, authority, method, null, extras) as Bundle?
    }

    private const val SHELL_PACKAGE = "com.android.shell"
}
