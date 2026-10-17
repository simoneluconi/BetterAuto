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
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import com.simoneluconi.betterauto.EventLog
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
 * nothing happens.
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
            val phoneAddress = ShizukuAudio.bluetoothAddress(context)
                ?: runCatching { adapter?.address }.getOrNull()
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
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(listener, filter, Context.RECEIVER_EXPORTED) else context.registerReceiver(listener, filter)
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

    override fun close() = stopListening()

    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        /** Codes shown on both screens first, then typed ones. */
        val PREFERRED = listOf(
            BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON,
            BluetoothPairingMethod.BLUETOOTH_PAIRING_PASSKEY_ENTRY,
            BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN,
        )
        const val PREFS = "car_bluetooth"
        const val KEY_FAILED_CAR = "failed_car"
        const val KEY_FAILED_AT = "failed_at"
        const val RETRY_AFTER_MS = 60 * 60 * 1000L

        /** What Android gives apps instead of the real address. */
        const val PLACEHOLDER_ADDRESS = "02:00:00:00:00:00"

        /** Only the last two bytes in the log: it is shared when debugging. */
        fun masked(address: String) = "XX:XX:XX:XX:" + address.takeLast(5)
    }
}
