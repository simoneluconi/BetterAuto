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
    private const val SERVICE_VERSION = 3

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
        .version(SERVICE_VERSION)

    /** Starts the shell-user service and waits for it; null when Shizuku is not ready or it does not come up. */
    fun connect(context: Context, timeoutMs: Long = 5000): Connection? {
        if (state(context) != State.READY) return null
        val ready = CountDownLatch(1)
        var router: IAudioRouter? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) router = IAudioRouter.Stub.asInterface(binder)
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                EventLog.log("Shizuku: audio service gone")
            }
        }
        val args = serviceArgs(context)
        Shizuku.bindUserService(args, connection)
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS) || router == null) {
            EventLog.log("Shizuku: audio service did not start")
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            return null
        }
        return Connection(router!!) { runCatching { Shizuku.unbindUserService(args, connection, true) } }
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
        state(context) == State.READY && withService(context) { it.confirmPairing(address, true) } == true

    /**
     * Runs [block] on the shell service, binding it for the call only, without touching an audio routing
     * that may be running; null when it does not come up or the call fails.
     */
    private fun <T> withService(context: Context, block: (IAudioRouter) -> T): T? {
        val ready = CountDownLatch(1)
        var router: IAudioRouter? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) router = IAudioRouter.Stub.asInterface(binder)
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val args = serviceArgs(context)
        Shizuku.bindUserService(args, connection)
        try {
            val service = (if (ready.await(5000, TimeUnit.MILLISECONDS)) router else null) ?: return null
            return runCatching { block(service) }.getOrNull()
        } finally {
            // Unbind without removing it: the same service may be moving the car's audio
            runCatching { Shizuku.unbindUserService(args, connection, false) }
        }
    }

    class Connection(val router: IAudioRouter, private val unbind: () -> Unit) {
        fun close() {
            runCatching { router.stop() }
            unbind()
        }
    }
}
