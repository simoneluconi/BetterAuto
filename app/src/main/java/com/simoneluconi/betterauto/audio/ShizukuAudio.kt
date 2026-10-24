package com.simoneluconi.betterauto.audio

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import com.simoneluconi.betterauto.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Optional Shizuku support: with Shizuku installed, running and allowed, BetterAuto runs [AudioRouterService]
 * as the shell user to move media audio to the car the way the system Android Auto does. Without it,
 * audio is copied through playback capture as before.
 */
object ShizukuAudio {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val PLAY_STORE = "https://play.google.com/store/apps/details?id=$PACKAGE"

    enum class State { NOT_INSTALLED, NOT_RUNNING, NOT_ALLOWED, READY }

    private const val PERMISSION_REQUEST = 4242

    private val _changes = MutableStateFlow(0)
    /** Bumps when Shizuku starts, stops or answers a permission request: re-read [state]. */
    val changes: StateFlow<Int> = _changes

    private var listening = false

    /** Listens to Shizuku coming and going; safe to call more than once. */
    fun init() {
        if (listening) return
        listening = true
        Shizuku.addBinderReceivedListenerSticky {
            EventLog.log("Shizuku: connected")
            _changes.value++
        }
        Shizuku.addBinderDeadListener {
            EventLog.log("Shizuku: gone")
            _changes.value++
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            EventLog.log("Shizuku: permission ${if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied"}")
            _changes.value++
        }
    }

    fun state(context: Context): State = when {
        !installed(context) -> State.NOT_INSTALLED
        !running() -> State.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NOT_ALLOWED
        else -> State.READY
    }

    fun requestPermission() {
        if (running()) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    private fun installed(context: Context) =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    // Shizuku before v11 had another API: treat it as not running, the user can update it
    private fun running() = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    private fun serviceArgs(context: Context) = Shizuku.UserServiceArgs(ComponentName(context.packageName, AudioRouterService::class.java.name))
        .daemon(false)
        .processNameSuffix("audio")
        .debuggable(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        .version(serviceVersion(context))

    /**
     * Shizuku keeps the service process running until its version changes: tied to when the app was
     * installed, so that an update always runs its own shell-side code, not the previous one's.
     */
    private fun serviceVersion(context: Context): Int =
        runCatching { (context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime / 1000).toInt() }.getOrDefault(1)

    /** Starts the shell-user service and waits for it; null when Shizuku is not ready or it does not come up. */
    @Synchronized
    fun connect(context: Context, route: Int, timeoutMs: Long = 5000): Connection? {
        if (state(context) != State.READY) return null
        // The music and the prompts share one binding: the last of them to close ends the service
        val bound = shared ?: bind(context, owner = true, timeoutMs) {
            EventLog.log("Shizuku: audio service gone")
            synchronized(this) { shared = null }
        }?.also { shared = it }
        if (bound == null) {
            EventLog.log("Shizuku: audio service did not start")
            return null
        }
        bound.users++
        return Connection(bound.router, route) { release(bound) }
    }

    /** The binding that moves the audio, while any route uses it. */
    private var shared: Bound? = null

    @Synchronized
    private fun release(bound: Bound) {
        if (--bound.users > 0) return
        bound.unbind()
        if (shared === bound) shared = null
    }

    /** Turns dual WiFi on or back to the phone's default through the shell service; false if it did not take. */
    fun setDualWifi(context: Context, on: Boolean): Boolean {
        val state = state(context)
        if (state != State.READY) {
            EventLog.log("Wireless: dual WiFi not set, Shizuku is $state")
            return false
        }
        val result = withService(context) { it.setDualWifi(on) }
        if (result == null) {
            EventLog.log("Wireless: dual WiFi not set, the Shizuku service did not start")
            return false
        }
        EventLog.log("Wireless: dual WiFi ${if (on) "on" else "back to the phone's default"} ($result)")
        return result.startsWith("exit 0")
    }

    /** The phone's real Bluetooth address through the shell service; null without Shizuku. */
    fun bluetoothAddress(context: Context): String? =
        if (state(context) == State.READY) withService(context) { it.bluetoothAddress() } else null

    /** Confirms the pairing in progress with [address] without Android's dialog; false without Shizuku. */
    fun confirmPairing(context: Context, address: String): Boolean =
        state(context) == State.READY && withService(context) { service ->
            service.confirmPairing(address, true).also { done ->
                if (!done) EventLog.log("Bluetooth: Shizuku could not confirm (${runCatching { service.status() }.getOrDefault("?")})")
            }
        } == true

    /** Contacts and messages for the car without Android's two dialogs; false without Shizuku. */
    fun allowCarAccess(context: Context, address: String): Boolean =
        state(context) == State.READY && withService(context) { service ->
            service.allowCarAccess(address).also { done ->
                if (!done) EventLog.log("Bluetooth: Shizuku could not allow the car's access (${runCatching { service.status() }.getOrDefault("?")})")
            }
        } == true

    /**
     * Runs [block] on the shell service, binding it for the call only, without touching an audio routing
     * that may be running; null when it does not come up or the call fails.
     */
    private fun <T> withService(context: Context, block: (IAudioRouter) -> T): T? {
        val bound = bind(context, owner = false, 5000) ?: return null
        try {
            return runCatching { block(bound.router) }.getOrNull()
        } finally {
            bound.unbind()
        }
    }

    /**
     * Binds the shell service and waits for it, null if it does not come up. [owner]: the binding that moves
     * the audio, whose unbind ends the service; any other leaves it running, it may be moving the car's audio.
     */
    private fun bind(context: Context, owner: Boolean, timeoutMs: Long, onGone: () -> Unit = {}): Bound? {
        val ready = CountDownLatch(1)
        var router: IAudioRouter? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) router = IAudioRouter.Stub.asInterface(binder)
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = onGone()
        }
        val args = serviceArgs(context)
        Shizuku.bindUserService(args, connection)
        val bound = (if (ready.await(timeoutMs, TimeUnit.MILLISECONDS)) router else null)?.let { Bound(it, args, connection, owner) }
        if (bound == null) runCatching { Shizuku.unbindUserService(args, connection, owner) }
        return bound
    }

    private class Bound(
        val router: IAudioRouter,
        private val args: Shizuku.UserServiceArgs,
        private val connection: ServiceConnection,
        private val remove: Boolean,
    ) {
        /** Routes using this binding ([connect]). */
        var users = 0

        fun unbind() {
            runCatching { Shizuku.unbindUserService(args, connection, remove) }
        }
    }

    /** One route on the shared service; [close] stops it and lets the service go once no route is left. */
    class Connection(val router: IAudioRouter, private val route: Int, private val unbind: () -> Unit) {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Safe to call twice (a capture stopping while its worker gives up): the binding is counted once. */
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { router.stop(route) }
            unbind()
        }
    }
}
