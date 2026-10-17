package com.simoneluconi.betterauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import com.simoneluconi.betterauto.aap.ConnectionProblem
import com.simoneluconi.betterauto.aap.VideoChannel
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.car.TemperatureUnit
import com.simoneluconi.betterauto.car.UnitSystem
import com.simoneluconi.betterauto.car.Timed
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.ui.vehicle.VehicleScreenState
import com.simoneluconi.betterauto.notifications.CarNotifications
import com.simoneluconi.betterauto.phone.CallPhase
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.projection.Projection
import com.simoneluconi.betterauto.settings.HomeLayout
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.MicSource
import com.simoneluconi.betterauto.voice.VoiceAssistant
import com.simoneluconi.betterauto.voice.VoiceState
import com.simoneluconi.betterauto.exlap.ExlapSettings
import com.simoneluconi.betterauto.settings.RailLayout
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.settings.ThemeMode
import kotlin.concurrent.thread

/**
 * Simulates head unit input and car state, for testing without a car:
 *   adb shell am broadcast -p com.simoneluconi.betterauto -a com.simoneluconi.betterauto.DEBUG_INPUT --ei rotary 1
 *   ... --ei key 23          press a key (23 = center, 4 = back)
 *   ... --ez night true      the car's night-mode sensor (used when the theme is Auto)
 *   ... --es theme night     force the theme: day, night or auto
 *   ... --es touch 960,540   a tap from the car's touchscreen (video pixels); "x1,y1,x2,y2" drags
 *   ... --ez restartVideo true  rebuild the car screen pipeline, as the stall watchdog does
 *   ... --es units imperial  distances and pressures: metric, imperial or auto;
 *                            --es temperature fahrenheit: celsius, fahrenheit or auto
 *   ... --es screen 9:16     pretend the car screen has this shape (or a size like 1280x480) inside the
 *                            real frame, to try portrait and wide layouts on any head unit; "off" to stop
 *   ... --es section music   open a section: home, music, map, phone or settings
 *   ... --es layout trio     home layout: single, split or trio
 *   ... --ez musicPanel true music section with the library panel (false = full-screen player)
 *   ... --es call ringing    pretend call: ringing, dialing, active, holding, ended or off
 *   ... --es notify "Text"   pretend message (add --es from "Name" and --ez group true for a group chat)
 *   ... --ez demo true       show only pretend notifications (screenshots without personal data)
 *   ... --ez clearDemo true  remove the pretend notifications
 *   ... --es vehicle demo    pretend car data on the vehicle screen ("off" clears it, "diagnostics" opens the codes);
 *                            --es dtc "P0300,P0171" sets the fault codes ("" clears them)
 *   ... --es rail "MAP:GROUP_1,VEHICLE:GROUP_1"  arrange the app bar (missing items keep their default;
 *                            "default" resets it)
 *   ... --es exlap_user USER --es exlap_password secret
 *                            set Exlap credentials and enable the feature (omit password to keep the saved one)
 *   ... --es problem certificate_rejected  the phone notification for a car that refused the connection
 *                            (any ConnectionProblem name; --ei code -3 and --es carMaker Volkswagen fill the
 *                            details, "off" removes it)
 */
class DebugInputReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("night")) CarState.nightMode.value = intent.getBooleanExtra("night", false)
        intent.getStringExtra("problem")?.let { name ->
            if (name == "off") {
                AapService.cancelProblem(context)
            } else {
                ConnectionProblem.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let {
                    val code = if (intent.hasExtra("code")) intent.getIntExtra("code", 0) else null
                    AapService.showProblem(context, it, code, intent.getStringExtra("carMaker"))
                }
            }
        }
        intent.getStringExtra("theme")?.let { name ->
            ThemeMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { LauncherSettings.get(context).setThemeMode(it) }
        }
        // What the stall watchdog does: new encoder, virtual display and launcher (off the main thread, it waits for the encoder)
        if (intent.getBooleanExtra("restartVideo", false)) thread(name = "debug-video-restart") { VideoChannel.active?.restartStreaming() }
        intent.getStringExtra("units")?.let { name ->
            UnitSystem.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { LauncherSettings.get(context).setUnitSystem(it) }
        }
        intent.getStringExtra("temperature")?.let { name ->
            TemperatureUnit.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { LauncherSettings.get(context).setTemperatureUnit(it) }
        }

        intent.getStringExtra("exlap_user")?.let { user ->
            val exlap = ExlapSettings.get(context)
            val pw = intent.getStringExtra("exlap_password")
            if (pw != null) exlap.setCredentials(user, pw) else exlap.renameUser(user)
            exlap.setEnabled(true)
        }

        intent.getStringExtra("section")?.let { name ->
            Section.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { LauncherSettings.get(context).setSection(it) }
        }
        intent.getStringExtra("mic")?.let { name ->
            MicSource.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { LauncherSettings.get(context).setVoiceMicrophone(it) }
        }
        when (intent.getStringExtra("voice")) {
            // Previews of the voice panel: nothing is dialed
            "preview_call" -> VoiceAssistant.preview(VoiceState.Calling("Mario Rossi", "Mobile", android.os.SystemClock.elapsedRealtime() + 3000))
            "preview_confirm" -> VoiceAssistant.preview(VoiceState.Choosing(listOf("Mario Rossi"), null, confirm = true))
            "preview_listen" -> VoiceAssistant.preview(VoiceState.Listening("chiama Mario", 0.4f, carMicrophone = true))
            "preview_off" -> VoiceAssistant.preview(VoiceState.Idle)
        }
        intent.getStringExtra("voice")?.takeIf { !it.startsWith("preview") }?.let { mode ->
            VoiceProbe.run(context, mode, intent.getStringExtra("phrase") ?: "chiama Mario Rossi", intent.getStringExtra("lang") ?: "it-IT")
        }
        intent.getStringExtra("rail")?.let { spec ->
            val settings = LauncherSettings.get(context)
            if (spec == "default") {
                settings.setRailLayout(RailLayout.Default)
            } else {
                val changes = RailLayout.decode(spec).filter { e -> spec.contains(e.item.name) }
                settings.setRailLayout(RailLayout.Default.map { d -> changes.firstOrNull { it.item == d.item } ?: d })
            }
        }
        intent.getStringExtra("layout")?.let { name ->
            HomeLayout.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { LauncherSettings.get(context).setLayout(it) }
        }
        if (intent.hasExtra("musicPanel")) {
            LauncherSettings.get(context).setMusicPanel(intent.getBooleanExtra("musicPanel", true))
        }
        intent.getStringExtra("call")?.let { name ->
            Calls.simulate(CallPhase.entries.firstOrNull { it.name.equals(name, ignoreCase = true) })
        }
        when (intent.getStringExtra("vehicle")) {
            "demo" -> VehicleData.simulate()
            "off" -> VehicleData.clear()
            "diagnostics" -> VehicleScreenState.showDiagnostics.value = true
        }
        intent.getStringExtra("dtc")?.let { codes ->
            val list = codes.split(',', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
            VehicleData.update { it.copy(diagnostics = Timed(list)) }
        }
        if (intent.getBooleanExtra("clearDemo", false)) CarNotifications.clearSimulated()
        if (intent.hasExtra("demo")) CarNotifications.demoOnly = intent.getBooleanExtra("demo", false)
        intent.getStringExtra("notify")?.let { text ->
            CarNotifications.simulate(intent.getStringExtra("from") ?: "Giulia", text, intent.getBooleanExtra("group", false))
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
        // Touches as the head unit sends them, in video-frame pixels: "x,y" taps, "x1,y1,x2,y2" drags over ~0.6 s
        intent.getStringExtra("touch")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.let { p ->
            val video = CarState.video.value
            if (video.width == 0 || (p.size != 2 && p.size != 4)) return@let
            val (x1, y1) = p[0] to p[1]
            val (x2, y2) = if (p.size == 4) p[2] to p[3] else x1 to y1
            thread(name = "debug-touch") {
                fun send(action: PointerAction, x: Int, y: Int) = projection.dispatchTouch(
                    TouchEvent.newBuilder().setAction(action).setActionIndex(0)
                        .addPointerData(TouchEvent.Pointer.newBuilder().setX(x).setY(y).setPointerId(0)).build(),
                    video.width, video.height,
                )
                send(PointerAction.ACTION_DOWN, x1, y1)
                if (p.size == 4) {
                    for (i in 1..TOUCH_DRAG_STEPS) {
                        Thread.sleep(30)
                        send(PointerAction.ACTION_MOVED, x1 + (x2 - x1) * i / TOUCH_DRAG_STEPS, y1 + (y2 - y1) * i / TOUCH_DRAG_STEPS)
                    }
                }
                Thread.sleep(30)
                send(PointerAction.ACTION_UP, x2, y2)
            }
        }
    }

    private companion object {
        const val TOUCH_DRAG_STEPS = 20
    }
}
