package com.simoneluconi.betterauto.transport.wireless

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.core.content.edit
import com.simoneluconi.betterauto.EventLog

/**
 * Auto-connect, as Android Auto does it: the phone is associated once with the head unit or dongle
 * through the system's companion device manager (Android asks the user), then the system wakes
 * [WirelessCompanionService] whenever the device connects to the phone over Bluetooth, which a dongle or
 * a wireless head unit does by itself when the car is switched on. Being a companion app is also what
 * lets BetterAuto start its service from the background at that moment. Android 12 or newer.
 *
 * Every device used is watched, each set up once: the car's dongle and a bench head unit both connect by
 * themselves, whichever comes up first. A device is set up when picked, unless the user turned it off for it.
 */
object WirelessAutoConnect {
    /** The devices the system watches for us (Bluetooth addresses, upper case). */
    private const val KEY_WATCHED = "auto_connect_devices"
    /** The devices the user turned it off for (or declined Android's dialog): not set up again by themselves. */
    private const val KEY_TURNED_OFF = "auto_connect_off"
    /** Before several devices: the one watched, and whether it was on. Read once. */
    private const val KEY_OLD_DEVICE = "auto_connect_device"
    private const val KEY_OLD_AUTO = "auto_connect"

    fun supported() = Build.VERSION.SDK_INT >= 31

    fun watched(context: Context): Set<String> {
        val prefs = context.wirelessPrefs()
        prefs.getString(KEY_OLD_DEVICE, null)?.let { old ->
            val on = prefs.getBoolean(KEY_OLD_AUTO, true)
            prefs.edit {
                remove(KEY_OLD_DEVICE)
                remove(KEY_OLD_AUTO)
                if (on) putStringSet(KEY_WATCHED, setOf(old.uppercase()))
            }
        }
        return prefs.getStringSet(KEY_WATCHED, null).orEmpty()
    }

    /** Auto-connect is set up for [address]. */
    fun isWatched(context: Context, address: String): Boolean = supported() && address.uppercase() in watched(context)

    /** The user turned it off for [address]: picking it again does not turn it back on. */
    fun turnedOff(context: Context, address: String): Boolean =
        address.uppercase() in context.wirelessPrefs().getStringSet(KEY_TURNED_OFF, null).orEmpty()

    private fun setWatched(context: Context, address: String, on: Boolean) {
        val key = address.uppercase()
        val prefs = context.wirelessPrefs()
        val watched = watched(context)
        val off = prefs.getStringSet(KEY_TURNED_OFF, null).orEmpty()
        prefs.edit {
            putStringSet(KEY_WATCHED, if (on) watched + key else watched - key)
            putStringSet(KEY_TURNED_OFF, if (on) off - key else off + key)
        }
    }

    /**
     * Turns auto-connect on for [address]. If the phone is not associated with it yet, Android's dialog is
     * needed: [onDialog] gets it to launch, and [finish] must be called with its result.
     */
    fun enable(context: Context, address: String, onDialog: (IntentSender) -> Unit, onDone: (Boolean) -> Unit) {
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        if (manager == null || !supported()) return onDone(false)
        if (isAssociated(manager, address)) {
            onDone(finish(context, address))
            return
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onDialog(intentSender)

            @Deprecated("Android 12 calls this one instead of onAssociationPending")
            override fun onDeviceFound(intentSender: IntentSender) = onDialog(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                onDone(finish(context, address))
            }

            override fun onFailure(error: CharSequence?) {
                EventLog.log("Wireless: auto-connect not set up: $error")
                declined(context, address)
                onDone(false)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            manager.associate(request, context.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            manager.associate(request, callback, null)
        }
    }

    /** After Android's dialog was accepted (or the association already existed): watch for the device. */
    fun finish(context: Context, address: String): Boolean {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        val watching = runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                val id = associationId(manager, address) ?: error("not associated")
                manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else {
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(address)
            }
        }
        watching.exceptionOrNull()?.let { EventLog.log("Wireless: cannot watch for the device: $it") }
        setWatched(context, address, watching.isSuccess)
        if (watching.isSuccess) EventLog.log("Wireless: auto-connect on (${watched(context).size} device(s))")
        return watching.isSuccess
    }

    /** Android's dialog was declined: off for [address], so it is not asked again until the user turns it on. */
    fun declined(context: Context, address: String) {
        setWatched(context, address, false)
    }

    fun disable(context: Context, address: String) {
        setWatched(context, address, false)
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        if (!supported()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                val id = associationId(manager, address) ?: return@runCatching
                manager.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else {
                @Suppress("DEPRECATION")
                manager.stopObservingDevicePresence(address)
            }
        }
        EventLog.log("Wireless: auto-connect off for a device (${watched(context).size} left)")
    }

    private fun isAssociated(manager: CompanionDeviceManager, address: String): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            associationId(manager, address) != null
        } else {
            @Suppress("DEPRECATION")
            manager.associations.any { it.equals(address, ignoreCase = true) }
        }

    private fun associationId(manager: CompanionDeviceManager, address: String): Int? =
        if (Build.VERSION.SDK_INT >= 33) {
            manager.myAssociations.firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }?.id
        } else {
            null
        }

    /** The Bluetooth address of one of our associations, for presence events that only carry its id. */
    fun addressOf(context: Context, associationId: Int): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return null
        return manager.myAssociations.firstOrNull { it.id == associationId }?.deviceMacAddress?.toString()
    }
}
