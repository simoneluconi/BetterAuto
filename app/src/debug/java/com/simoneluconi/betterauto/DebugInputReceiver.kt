package com.simoneluconi.betterauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simoneluconi.betterauto.projection.Projection

/**
 * Injects head unit input into the running projection, for testing without a car:
 *   adb shell am broadcast -p com.simoneluconi.betterauto -a com.simoneluconi.betterauto.DEBUG_INPUT --ei rotary 1
 *   adb shell am broadcast -p com.simoneluconi.betterauto -a com.simoneluconi.betterauto.DEBUG_INPUT --ei key 23   (KEYCODE_DPAD_CENTER)
 */
class DebugInputReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val projection = Projection.active ?: return EventLog.log("Debug input: no active projection")
        val rotary = intent.getIntExtra("rotary", 0)
        if (rotary != 0) projection.dispatchRotary(rotary)
        val key = intent.getIntExtra("key", 0)
        if (key != 0) {
            projection.dispatchKey(key, down = true, longPress = false)
            projection.dispatchKey(key, down = false, longPress = false)
        }
    }
}
