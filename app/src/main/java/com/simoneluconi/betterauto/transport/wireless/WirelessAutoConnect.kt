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
 */
object WirelessAutoConnect {
    private const val PREFS = "wireless"
    private const val KEY_AUTO = "auto_connect"
    /** The device the system watches for us now, to set it up once and move it when another is picked. */
    private const val KEY_WATCHED = "auto_connect_device"

    fun supported() = Build.VERSION.SDK_INT >= 31

    /** On unless the user turned it off (or declined Android's dialog). */
    fun enabled(context: Context): Boolean =
        supported() && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun watched(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WATCHED, null)

    private fun setWatched(context: Context, address: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_WATCHED, address) }
    }

    private fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_AUTO, value) }
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
                declined(context)
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
        setEnabled(context, watching.isSuccess)
        setWatched(context, if (watching.isSuccess) address else null)
        if (watching.isSuccess) EventLog.log("Wireless: auto-connect on")
        return watching.isSuccess
    }

    /** Android's dialog was declined: off, so it is not asked again until the user turns it on. */
    fun declined(context: Context) {
        setEnabled(context, false)
        setWatched(context, null)
    }

    fun disable(context: Context, address: String?) {
        setEnabled(context, false)
        setWatched(context, null)
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        if (address == null || !supported()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                val id = associationId(manager, address) ?: return@runCatching
                manager.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else {
                @Suppress("DEPRECATION")
                manager.stopObservingDevicePresence(address)
            }
        }
        EventLog.log("Wireless: auto-connect off")
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
