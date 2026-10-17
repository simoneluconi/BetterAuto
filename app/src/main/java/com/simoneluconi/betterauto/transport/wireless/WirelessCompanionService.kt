package com.simoneluconi.betterauto.transport.wireless

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.Intent
import androidx.annotation.RequiresApi
import com.simoneluconi.betterauto.AapService
import com.simoneluconi.betterauto.EventLog

/**
 * Woken by the system when the associated head unit or dongle connects to the phone over Bluetooth (see
 * [WirelessAutoConnect]): starts the wireless connection. Each Android version calls a different method;
 * a repeated call is harmless, the service ignores an automatic start while it is already connected.
 */
@RequiresApi(31)
class WirelessCompanionService : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BT_CONNECTED || event.event == DevicePresenceEvent.EVENT_BLE_APPEARED) {
            appeared(WirelessAutoConnect.addressOf(this, event.associationId))
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        appeared(associationInfo.deviceMacAddress?.toString())
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        appeared(address)
    }

    private fun appeared(address: String?) {
        // Only the device picked on the Wireless page, by its address as Bluetooth spells it
        val saved = WirelessConnector.savedDevice(this) ?: return
        if (!WirelessAutoConnect.enabled(this)) return
        if (address != null && !address.equals(saved, ignoreCase = true)) return
        EventLog.log("Wireless: the head unit is in range, connecting automatically")
        runCatching {
            startForegroundService(
                Intent(this, AapService::class.java).setAction(AapService.ACTION_CONNECT_WIRELESS)
                    .putExtra(AapService.EXTRA_DEVICE, saved)
                    .putExtra(AapService.EXTRA_AUTO, true)
            )
        }.onFailure { EventLog.log("Wireless: could not start the automatic connection: $it") }
    }
}
