package com.simoneluconi.betterauto.transport.wireless

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.Intent
import androidx.annotation.RequiresApi
import com.simoneluconi.betterauto.AapService
import com.simoneluconi.betterauto.EventLog

/**
 * Woken by the system when an associated head unit or dongle connects to the phone over Bluetooth (see
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
        // The device that came up, if auto-connect is on for it (the car's dongle, a bench head unit...); with no
        // address (older Android) the one picked on the Wireless page. Upper case, as Bluetooth spells it
        val device = (address ?: WirelessConnector.savedDevice(this))?.uppercase() ?: return
        if (!WirelessAutoConnect.isWatched(this, device)) return
        EventLog.log("Wireless: the head unit is in range, connecting automatically")
        runCatching {
            startForegroundService(
                Intent(this, AapService::class.java).setAction(AapService.ACTION_CONNECT_WIRELESS)
                    .putExtra(AapService.EXTRA_DEVICE, device)
                    .putExtra(AapService.EXTRA_AUTO, true)
            )
        }.onFailure { EventLog.log("Wireless: could not start the automatic connection: $it") }
    }
}
