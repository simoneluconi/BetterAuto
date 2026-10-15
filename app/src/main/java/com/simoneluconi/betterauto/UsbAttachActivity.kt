package com.simoneluconi.betterauto

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle

/**
 * Invisible entry point for USB_ACCESSORY_ATTACHED: hands the accessory to [AapService] and finishes
 * right away, so the session starts within the head unit's timeouts and the phone screen stays as it is.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accessory = intent.accessoryExtra()
        if (accessory != null) {
            EventLog.log("Accessory attached: ${accessory.manufacturer} / ${accessory.model} v${accessory.version}")
            startForegroundService(
                Intent(this, AapService::class.java).putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
            )
        }
        finish()
    }
}
