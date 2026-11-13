package com.simoneluconi.betterauto.aap

import aap_protobuf.service.bluetooth.BluetoothMessageIdOuterClass.BluetoothMessageId
import aap_protobuf.service.bluetooth.BluetoothServiceOuterClass.BluetoothService
import aap_protobuf.service.bluetooth.message.BluetoothAuthenticationDataOuterClass.BluetoothAuthenticationData
import aap_protobuf.service.bluetooth.message.BluetoothAuthenticationResultOuterClass.BluetoothAuthenticationResult
import aap_protobuf.service.bluetooth.message.BluetoothPairingMethodOuterClass.BluetoothPairingMethod
import aap_protobuf.service.bluetooth.message.BluetoothPairingRequestOuterClass.BluetoothPairingRequest
import aap_protobuf.service.bluetooth.message.BluetoothPairingResponseOuterClass.BluetoothPairingResponse
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.audio.CarAudio
import com.simoneluconi.betterauto.audio.ShizukuAudio
import kotlin.concurrent.thread

/**
 * The car's Bluetooth, for calls: like Android Auto, the phone pairs itself with the head unit so that
 * calls and contacts work without going through the car's menus.
 *
 *   phone -> PAIRING_REQUEST (its address, a method the car offers)
 *   HU    -> PAIRING_RESPONSE (already paired or not)
 *   not paired: the phone starts the pairing with the car's address, and the car sends
 *   HU    -> AUTHENTICATION_DATA (the code it shows), which the phone confirms (by itself through Shizuku,
 *            otherwise the driver taps Android's dialog)
 *   phone -> AUTHENTICATION_RESULT once paired
 *
 * Already paired (the usual case, and always with a wireless dongle, which is paired to reach the phone):
 * the phone only sees that the car stays connected for calls, connecting it again when it does not.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT (asked for on the Wireless page) is checked when the channel opens
class BluetoothChannel(
    session: AapSession,
    channelId: Int,
    private val service: BluetoothService,
    private val context: Context,
) : ServiceChannel(session, channelId) {
    override val name = "bluetooth"

    private val carAddress = service.carAddress.uppercase()
    @Volatile
    private var receiver: BroadcastReceiver? = null
    /** The code the car shows, once it sent it. */
    @Volatile
    private var carCode: String? = null
    /** Android asked to confirm the pairing (with the code it shows, -1 if it did not say). */
    @Volatile
    private var phoneCode: Int? = null
    @Volatile
    private var phoneVariant = -1
    /** The pairing is under way: enough to answer when Android does not tell apps its request. */
    @Volatile
    private var bonding = false
    @Volatile
    private var confirmed = false

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override fun onOpened() {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            EventLog.log("Bluetooth: no permission to see the phone's pairings, the car's pairing is left as it is")
            return
        }
        // The phone's address comes from the shell service when there is one: a binder call, off this thread
        thread(name = "aap-bluetooth") {
            val pairedHere = isPaired()
            EventLog.log("Bluetooth: car at ${masked(carAddress)}, ${if (pairedHere) "already paired" else "not paired yet"}")
            val method = service.supportedPairingMethodsList.let { offered ->
                PREFERRED.firstOrNull { it in offered } ?: offered.firstOrNull() ?: BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN
            }
            // Remembered from the last time Shizuku told it: with the placeholder the car does not find the
            // phone among its pairings, says "not paired" and leaves it off its calls
            val phoneAddress = ShizukuAudio.bluetoothAddress(context)
                ?.also { address -> prefs().edit { putString(KEY_PHONE_ADDRESS, address) } }
                ?: runCatching { adapter?.address }.getOrNull()?.takeIf { it != PLACEHOLDER_ADDRESS }
                ?: prefs().getString(KEY_PHONE_ADDRESS, null)
                ?: PLACEHOLDER_ADDRESS
            send(
                BluetoothMessageId.BLUETOOTH_MESSAGE_PAIRING_REQUEST_VALUE,
                BluetoothPairingRequest.newBuilder().setPhoneAddress(phoneAddress).setPairingMethod(method).build(),
            )
        }
    }

    override fun onChannelMessage(msg: AapMessage) {
        when (msg.id) {
            BluetoothMessageId.BLUETOOTH_MESSAGE_PAIRING_RESPONSE_VALUE -> {
                val response = BluetoothPairingResponse.parseFrom(msg.payload)
                EventLog.log("Bluetooth: pairing response ${response.status}, car says ${if (response.alreadyPaired) "already paired" else "not paired"}")
                // Paired on both sides, the car connects the phone for calls by itself; after a drop it sometimes
                // does not, and the phone then stays off the car's calls: connected from here if still not
                if (response.status == MessageStatus.STATUS_SUCCESS && response.alreadyPaired && isPaired()) {
                    watchCalls()
                    connectIfIdle()
                }
                if (response.status == MessageStatus.STATUS_SUCCESS && !response.alreadyPaired && !isPaired()) {
                    if (failedRecently()) {
                        EventLog.log("Bluetooth: pairing with this car failed or was declined lately, not asking again yet")
                    } else {
                        startPairing()
                    }
                }
            }
            BluetoothMessageId.BLUETOOTH_MESSAGE_AUTHENTICATION_DATA_VALUE -> {
                val data = BluetoothAuthenticationData.parseFrom(msg.payload)
                EventLog.log("Bluetooth: the car sent its pairing code (${data.pairingMethod})")
                carCode = data.authData
                answer()
            }
            else -> EventLog.log("Bluetooth: unhandled message ${msg.id} (${msg.payload.size} bytes)")
        }
    }

    /**
     * After a moment for the car to do it, connects the phone for calls if it is still not (through Shizuku).
     * An Audi MMX2 does not answer for the first minute or two after starting (each try times out after ~6 s,
     * a manual one 90 s in worked): tried again until it does, for a few minutes.
     */
    private fun connectIfIdle() {
        // One run at a time: a drop while the first one still tries only keeps it going
        if (!connecting.compareAndSet(false, true)) return
        thread(name = "aap-bluetooth-connect") {
            try {
                connectLoop()
            } finally {
                connecting.set(false)
            }
        }
    }

    private val connecting = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun connectLoop() {
        var wait = CONNECT_WAIT_MS
        for (attempt in 1..CONNECT_TRIES) {
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                return
            }
            wait = CONNECT_RETRY_MS
            if (closed) return
            val state = headsetState()
            if (state == null) {
                EventLog.log("Bluetooth: Android does not say whether the car is connected for calls, leaving it")
                return
            }
            // Only a done connection counts: one the car started may still fail, and is looked at again next time
            if (state == BluetoothProfile.STATE_CONNECTED) return
            if (state == BluetoothProfile.STATE_CONNECTING) continue
            if (attempt == 1) EventLog.log("Bluetooth: the car did not connect the phone for calls, connecting it")
            if (!ShizukuAudio.connectBluetooth(context, carAddress)) {
                EventLog.log("Bluetooth: the phone cannot connect the car by itself without Shizuku")
                return
            }
            EventLog.log("Bluetooth: connection to the car asked (try $attempt)")
        }
        EventLog.log("Bluetooth: the car never answered, the phone stays off its calls")
    }

    /**
     * Follows the car's calls connection for the whole session: a car switched off and on again quickly keeps the
     * wireless session but drops the phone's calls, and does not always bring them back by itself.
     */
    private fun watchCalls() {
        if (callsWatcher != null) return
        val watcher = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val about = deviceOf(intent) ?: return
                if (!about.address.equals(carAddress, ignoreCase = true) || closed) return
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                val previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                when {
                    state == BluetoothProfile.STATE_CONNECTED -> {
                        EventLog.log("Bluetooth: connected to the car for calls")
                        CarAudio.onCarCallsBack()
                    }
                    state == BluetoothProfile.STATE_DISCONNECTED && previous != BluetoothProfile.STATE_CONNECTING -> {
                        EventLog.log("Bluetooth: the car dropped the phone's calls connection")
                        CarAudio.onCarCallsDropped()
                        connectIfIdle()
                    }
                }
            }
        }
        // A system broadcast, protected: exporting the receiver lets nobody else in
        ContextCompat.registerReceiver(context, watcher, IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        callsWatcher = watcher
    }

    @Volatile
    private var callsWatcher: BroadcastReceiver? = null

    /** The car's calls connection (hands-free profile), a BluetoothProfile.STATE_*; null when Android does not say. */
    private fun headsetState(): Int? {
        val adapter = adapter ?: return null
        val device = runCatching { adapter.getRemoteDevice(carAddress) }.getOrNull() ?: return null
        val ready = java.util.concurrent.CountDownLatch(1)
        var state: Int? = null
        val asked = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                state = runCatching { proxy.getConnectionState(device) }.getOrNull()
                adapter.closeProfileProxy(profile, proxy)
                ready.countDown()
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }, BluetoothProfile.HEADSET)
        if (!asked || !ready.await(3, java.util.concurrent.TimeUnit.SECONDS)) return null
        return state
    }

    @Volatile
    private var closed = false

    /** A failed or declined pairing is not asked again at every reconnection, only after a while. */
    private fun failedRecently(): Boolean {
        val prefs = prefs()
        return prefs.getString(KEY_FAILED_CAR, null) == carAddress &&
            System.currentTimeMillis() - prefs.getLong(KEY_FAILED_AT, 0L) < RETRY_AFTER_MS
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isPaired() = runCatching {
        adapter?.bondedDevices.orEmpty().any { it.address.equals(carAddress, ignoreCase = true) }
    }.getOrDefault(false)

    /** Pairs with the car and follows Android's pairing request and the bond as it changes. */
    private fun startPairing() {
        val device = runCatching { adapter?.getRemoteDevice(carAddress) }.getOrNull() ?: return
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val about = deviceOf(intent) ?: return
                if (!about.address.equals(carAddress, ignoreCase = true)) return
                when (intent.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        phoneCode = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
                        phoneVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                        answer()
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondState(
                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
                        intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE),
                    )
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // System broadcasts, protected: exporting the receiver lets nobody else in
        ContextCompat.registerReceiver(context, listener, filter, ContextCompat.RECEIVER_EXPORTED)
        receiver = listener
        val started = runCatching { device.createBond() }.getOrDefault(false)
        EventLog.log("Bluetooth: pairing with the car ${if (started) "started" else "could not start"}")
        if (!started) sendResult(MessageStatus.STATUS_INTERNAL_ERROR)
    }

    /**
     * Confirms the pairing once both sides spoke: the car sent its code and Android is asking. The codes must
     * match when Android says its own; with Shizuku the answer goes in by itself, without it the driver
     * confirms in Android's dialog, where the same code is shown.
     */
    @Synchronized
    private fun answer() {
        val code = carCode ?: return
        if (confirmed || (phoneCode == null && !bonding)) return
        val shown = phoneCode ?: -1
        if (shown >= 0 && code.toIntOrNull() != shown) {
            EventLog.log("Bluetooth: the car's code does not match the phone's, leaving it to the driver")
            return
        }
        confirmed = true
        // A PIN to type in: any app paired with the device may give it; a code to confirm needs the shell user
        if (phoneVariant == BluetoothDevice.PAIRING_VARIANT_PIN) {
            val device = runCatching { adapter?.getRemoteDevice(carAddress) }.getOrNull()
            @Suppress("DEPRECATION") // still the way to answer a PIN request without the dialog
            val done = runCatching { device?.setPin(code.toByteArray()) == true }.getOrDefault(false)
            EventLog.log(if (done) "Bluetooth: PIN given to the car" else "Bluetooth: type the car's PIN on the phone")
            return
        }
        thread(name = "aap-bluetooth-confirm") {
            val done = ShizukuAudio.confirmPairing(context, carAddress)
            EventLog.log(if (done) "Bluetooth: pairing confirmed through Shizuku" else "Bluetooth: confirm the pairing on the phone")
            // The car asks for contacts and messages right after: answered before it does, so no dialogs either
            if (done && ShizukuAudio.allowCarAccess(context, carAddress)) EventLog.log("Bluetooth: contacts and messages allowed for the car")
        }
    }

    private fun onBondState(state: Int, previous: Int) {
        when {
            state == BluetoothDevice.BOND_BONDING -> {
                bonding = true
                answer()
            }
            state == BluetoothDevice.BOND_BONDED -> {
                EventLog.log("Bluetooth: paired with the car")
                sendResult(MessageStatus.STATUS_SUCCESS)
            }
            state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING -> {
                EventLog.log("Bluetooth: pairing with the car failed or was declined")
                prefs().edit { putString(KEY_FAILED_CAR, carAddress).putLong(KEY_FAILED_AT, System.currentTimeMillis()) }
                sendResult(MessageStatus.STATUS_AUTHENTICATION_FAILURE)
            }
        }
    }

    private fun sendResult(status: MessageStatus) {
        send(
            BluetoothMessageId.BLUETOOTH_MESSAGE_AUTHENTICATION_RESULT_VALUE,
            BluetoothAuthenticationResult.newBuilder().setStatus(status).build(),
        )
        stopListening()
    }

    private fun stopListening() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    override fun close() {
        closed = true
        stopListening()
        callsWatcher?.let { runCatching { context.unregisterReceiver(it) } }
        callsWatcher = null
    }

    private fun deviceOf(intent: Intent): BluetoothDevice? =
        IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    private companion object {
        /** Codes shown on both screens first, then typed ones. */
        val PREFERRED = listOf(
            BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON,
            BluetoothPairingMethod.BLUETOOTH_PAIRING_PASSKEY_ENTRY,
            BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN,
        )
        const val PREFS = "car_bluetooth"
        const val KEY_FAILED_CAR = "failed_car"
        /** The phone's real Bluetooth address, which only the shell service can read. */
        const val KEY_PHONE_ADDRESS = "phone_address"
        const val KEY_FAILED_AT = "failed_at"
        const val RETRY_AFTER_MS = 60 * 60 * 1000L
        /** How long the car gets to connect the phone for calls by itself. */
        const val CONNECT_WAIT_MS = 10_000L
        /** Between tries while the car does not answer: longer than a try's own timeout (~6 s). */
        const val CONNECT_RETRY_MS = 20_000L
        /** About four minutes of tries in all. */
        const val CONNECT_TRIES = 12

        /** What Android gives apps instead of the real address. */
        const val PLACEHOLDER_ADDRESS = "02:00:00:00:00:00"

        /** Only the last two bytes in the log: it is shared when debugging. */
        fun masked(address: String) = "XX:XX:XX:XX:" + address.takeLast(5)
    }
}
