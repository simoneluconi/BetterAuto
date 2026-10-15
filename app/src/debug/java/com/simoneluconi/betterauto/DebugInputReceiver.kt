package com.simoneluconi.betterauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.projection.Projection
import com.simoneluconi.betterauto.settings.HomeLayout
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.settings.ThemeMode

/**
 * Simulates head unit input and car state, for testing without a car:
 *   adb shell am broadcast -p com.simoneluconi.betterauto -a com.simoneluconi.betterauto.DEBUG_INPUT --ei rotary 1
 *   ... --ei key 23          press a key (23 = center, 4 = back)
 *   ... --ez night true      the car's night-mode sensor (used when the theme is Auto)
 *   ... --es theme night     force the theme: day, night or auto
 *   ... --es screen 9:16     pretend the car screen has this shape (or a size like 1280x480) inside the
 *                            real frame, to try portrait and wide layouts on any head unit; "off" to stop
 *   ... --es section music   open a section: home, music, map, phone or settings
 *   ... --es layout trio     home layout: single, split or trio
 *   ... --ez musicPanel true music section with the library panel (false = full-screen player)
 */
class DebugInputReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("night")) CarState.nightMode.value = intent.getBooleanExtra("night", false)
        intent.getStringExtra("theme")?.let { name ->
            ThemeMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { LauncherSettings.get(context).setThemeMode(it) }
        }

        intent.getStringExtra("section")?.let { name ->
            Section.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { LauncherSettings.get(context).setSection(it) }
        }
        intent.getStringExtra("layout")?.let { name ->
            HomeLayout.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { LauncherSettings.get(context).setLayout(it) }
        }
        if (intent.hasExtra("musicPanel")) {
            LauncherSettings.get(context).setMusicPanel(intent.getBooleanExtra("musicPanel", true))
        }
        intent.getStringExtra("screen")?.let { CarState.simulatedScreen.value = it.takeUnless { s -> s == "off" } }

        val projection = Projection.active ?: return
        val rotary = intent.getIntExtra("rotary", 0)
        if (rotary != 0) projection.dispatchRotary(rotary)
        val key = intent.getIntExtra("key", 0)
        if (key != 0) {
            projection.dispatchKey(key, down = true, longPress = false)
            projection.dispatchKey(key, down = false, longPress = false)
        }
    }
}
