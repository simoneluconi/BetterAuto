package com.simoneluconi.betterauto

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.simoneluconi.betterauto.audio.CarAudio
import com.simoneluconi.betterauto.audio.ShizukuAudio

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
            // We are in the foreground only now: ask for car audio consent while we can show it,
            // unless Shizuku moves the audio to the car without one
            ShizukuAudio.init()
            val state = ShizukuAudio.state(this)
            EventLog.log("Shizuku at attach: $state")
            val shizuku = state == ShizukuAudio.State.READY
            if (!CarAudio.hasProjection && !shizuku) startActivity(Intent(this, AudioConsentActivity::class.java))
        }
        finish()
    }
}
