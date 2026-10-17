package com.simoneluconi.betterauto.transport.wireless

import aap_protobuf.aaw.StatusOuterClass.Status
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.edit
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.transport.TcpTransport
import com.simoneluconi.betterauto.transport.Transport
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The steps of a wireless connection, in order. */
enum class WirelessStep { BLUETOOTH, WIFI_DETAILS, WIFI_JOIN, TCP }

/** Where the wireless connection is, for the phone app. */
sealed interface WirelessState {
    data object Idle : WirelessState
    data class Connecting(val step: WirelessStep) : WirelessState
    data object Connected : WirelessState
    /** Stopped at [step]; [detail] is the technical reason, as in the log. */
    data class Failed(val step: WirelessStep, val detail: String) : WirelessState
}

/**
 * Wireless Android Auto, phone side: Bluetooth to the paired head unit or dongle, the bootstrap that
 * gives its WiFi, the WiFi join, then TCP to its AAP server. Returns a [Transport] like the USB one; the
 * Bluetooth link and the WiFi stay up for the whole session and go with the transport.
 * Every step has a timeout and says what it does in the log. [cancel] stops a connection in progress.
 */
@SuppressLint("MissingPermission") // checked in missingPermissions(), asked by the phone app
class WirelessConnector(private val context: Context) : Closeable {
    @Volatile
    private var cancelled = false
    private var bluetooth: BluetoothSocket? = null
    private var wifi: WifiJoin? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** The head unit's WiFi went away during the session: it was switched off, no point trying again. */
    @Volatile
    var wifiLost = false
        private set

    /** The step in progress, or the one that failed. */
    @Volatile
    var step = WirelessStep.BLUETOOTH
        private set

    private fun enter(next: WirelessStep) {
        step = next
        if (!cancelled) report(WirelessState.Connecting(next))
    }

    fun connect(address: String): Transport {
        enter(WirelessStep.BLUETOOTH)
        missingPermissions(context).takeIf { it.isNotEmpty() }?.let {
            throw IOException("missing permissions: ${it.joinToString { p -> p.substringAfterLast('.') }}")
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("this phone has no Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        val name = runCatching { device.name }.getOrNull() ?: address

        val socket = connectBluetooth(device, name)
        check()

        enter(WirelessStep.WIFI_DETAILS)
        val bootstrap = WirelessBootstrap(socket.inputStream, socket.outputStream)
        val endpoint = withTimeout(BOOTSTRAP_TIMEOUT_MS, "WiFi details from $name") { bootstrap.negotiate() }
        check()

        enter(WirelessStep.WIFI_JOIN)
        EventLog.log("Wireless: joining \"${endpoint.ssid}\"")
        val join = WifiJoin(context)
        wifi = join
        val network = try {
            join.join(endpoint, WIFI_TIMEOUT_MS)
        } catch (e: IOException) {
            runCatching { bootstrap.reportConnection(Status.STATUS_WIFI_NETWORK_UNAVAILABLE) }
            throw e
        }
        check()

        enter(WirelessStep.TCP)
        EventLog.log("Wireless: TCP to ${endpoint.host}:${endpoint.port}")
        val tcp = network.socketFactory.createSocket()
        try {
            tcp.tcpNoDelay = true
            tcp.keepAlive = true
            tcp.connect(InetSocketAddress(endpoint.host, endpoint.port), TCP_TIMEOUT_MS)
        } catch (e: IOException) {
            runCatching { tcp.close() }
            runCatching { bootstrap.reportConnection(Status.STATUS_INVALID_HOST) }
            throw e
        }
        runCatching { bootstrap.reportConnection(Status.STATUS_SUCCESS) }
        EventLog.log("Wireless: connected to $name")
        holdLowLatencyWifi()
        val transport = TcpTransport(tcp, onClose = ::close)
        // The WiFi going away ends the session, as unplugging the cable does (the car was switched off)
        join.onLost = {
            wifiLost = true
            transport.close()
        }
        bluetooth = socket
        return transport
    }

    /**
     * Keeps the WiFi radio awake between packets for the whole session, as Android Auto does: with power
     * saving the phone answers late and video and audio stall for seconds.
     */
    private fun holdLowLatencyWifi() {
        val manager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        runCatching {
            manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "BetterAuto:wireless").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onSuccess { wifiLock = it }.onFailure { EventLog.log("Wireless: no low-latency WiFi ($it)") }
    }

    /**
     * Dongles keep calling the phone on their own: when both sides connect at the same moment the
     * phone's attempt is lost (the controller is busy) and hangs. A few short tries get past it.
     */
    private fun connectBluetooth(device: BluetoothDevice, name: String): BluetoothSocket {
        var attempt = 1
        while (true) {
            EventLog.log("Wireless: Bluetooth to $name" + if (attempt > 1) " (try $attempt)" else "")
            val socket = device.createRfcommSocketToServiceRecord(ANDROID_AUTO_WIRELESS)
            bluetooth = socket
            try {
                withTimeout(BLUETOOTH_TIMEOUT_MS, "Bluetooth connection to $name") { socket.connect() }
                return socket
            } catch (e: IOException) {
                check()
                if (attempt == BLUETOOTH_ATTEMPTS) throw e
                EventLog.log("Wireless: ${e.message}, trying again")
            }
            attempt++
            Thread.sleep(BLUETOOTH_RETRY_PAUSE_MS)
            check()
        }
    }

    /** Stops a connection in progress, or tears down the links of a finished one. */
    fun cancel() {
        cancelled = true
        close()
    }

    override fun close() {
        runCatching { wifiLock?.release() }
        wifiLock = null
        wifi?.release()
        wifi = null
        runCatching { bluetooth?.close() }
        bluetooth = null
    }

    private fun check() {
        if (cancelled) {
            close()
            throw IOException("wireless connection cancelled")
        }
    }

    /** Runs [block], closing everything if it takes longer than [timeoutMs] (Bluetooth calls have no timeout). */
    private fun <T> withTimeout(timeoutMs: Long, what: String, block: () -> T): T {
        var timedOut = false
        val timer = Thread {
            try {
                Thread.sleep(timeoutMs)
                timedOut = true
                close()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        try {
            return block()
        } catch (e: IOException) {
            close()
            if (timedOut) throw IOException("$what: no answer in ${timeoutMs / 1000} s", e)
            throw IOException("$what failed: ${e.message ?: "the other side closed the connection"}", e)
        } finally {
            timer.interrupt()
        }
    }

    companion object {
        /** The RFCOMM service wireless Android Auto head units and dongles offer. */
        val ANDROID_AUTO_WIRELESS: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")

        private const val BLUETOOTH_TIMEOUT_MS = 6_000L
        private const val BLUETOOTH_ATTEMPTS = 3
        private const val BLUETOOTH_RETRY_PAUSE_MS = 1_000L
        private const val BOOTSTRAP_TIMEOUT_MS = 15_000L
        private const val WIFI_TIMEOUT_MS = 30_000L
        private const val TCP_TIMEOUT_MS = 10_000

        private val _status = MutableStateFlow<WirelessState>(WirelessState.Idle)
        val status: StateFlow<WirelessState> = _status

        /** Set by the connector while it works and by AapService when it starts, fails or ends. */
        internal fun report(state: WirelessState) {
            _status.value = state
        }

        private const val PREFS = "wireless"
        private const val KEY_DEVICE = "device"

        /** Runtime permissions wireless needs on this Android version. */
        fun requiredPermissions(): Array<String> = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES) else add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 37) add(ACCESS_LOCAL_NETWORK)
        }.toTypedArray()

        fun missingPermissions(context: Context) =
            requiredPermissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        /** Android 17's local network permission (a string, so it builds against any SDK). */
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

        /** The head unit or dongle the driver picked, by Bluetooth address. */
        fun savedDevice(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEVICE, null)

        fun saveDevice(context: Context, address: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_DEVICE, address) }
        }
    }
}
