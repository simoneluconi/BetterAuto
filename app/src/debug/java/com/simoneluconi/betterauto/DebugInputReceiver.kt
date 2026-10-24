package com.simoneluconi.betterauto

import com.simoneluconi.betterauto.car.CarMotionState
import com.simoneluconi.betterauto.car.CarMotion
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import aap_protobuf.shared.InstrumentClusterInputOuterClass.InstrumentClusterInput.InstrumentClusterAction
import com.simoneluconi.betterauto.aap.ConnectionProblem
import com.simoneluconi.betterauto.aap.PhoneStatusChannel
import com.simoneluconi.betterauto.aap.VideoChannel
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.car.TemperatureUnit
import com.simoneluconi.betterauto.car.UnitSystem
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.car.VehicleField
import com.simoneluconi.betterauto.car.VehicleSource
import com.simoneluconi.betterauto.ui.vehicle.VehicleScreenState
import com.simoneluconi.betterauto.notifications.CarNotifications
import com.simoneluconi.betterauto.phone.CallPhase
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.projection.Projection
import com.simoneluconi.betterauto.settings.FormatRegion
import com.simoneluconi.betterauto.settings.HomeLayout
import com.simoneluconi.betterauto.settings.HomeSlots
import com.simoneluconi.betterauto.settings.WidgetChoice
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.MicSource
import com.simoneluconi.betterauto.voice.VoiceAssistant
import com.simoneluconi.betterauto.voice.VoiceState
import com.simoneluconi.betterauto.settings.RailLayout
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.settings.ThemeMode
import com.simoneluconi.betterauto.smarthome.SmartHome
import com.simoneluconi.betterauto.settings.SettingsCategory
import com.simoneluconi.betterauto.ui.launcher.BarEditor
import com.simoneluconi.betterauto.ui.launcher.CarSettingsNav
import com.simoneluconi.betterauto.ui.widgets.HomeEdit
import android.os.ParcelFileDescriptor
import com.simoneluconi.betterauto.audio.CarAudio
import com.simoneluconi.betterauto.audio.ShizukuAudio
import kotlin.concurrent.thread

/**
 * Simulates head unit input and car state, for testing without a car:
 *   adb shell am broadcast -p com.simoneluconi.betterauto -a com.simoneluconi.betterauto.DEBUG_INPUT --ei rotary 1
 *   ... --ei key 23          press a key (23 = center, 4 = back)
 *   ... --ei holdKey 87 --ei holdMs 4000   hold a key (87 next, 88 previous: runs through the track)
 *   ... --ei longKey 23      hold a key on the car screen for 800 ms (--ei holdMs to change it): the knob's long
 *                            press (23 = center: on a home widget it edits the home, on a bar button it arranges the bar)
 *   ... --es longTouch 400,300  a finger held for 900 ms (video pixels)
 *   ... --es editHome on     the home screen's edit mode ("off" leaves it, "pick 1" opens slot 1's widget list)
 *   ... --es arrangeBar on   the app bar's editor ("off" closes it)
 *   ... --es settings display  the car's settings on a category (home_screen, display, music, notifications,
 *                            voice, vehicle, smart_home, advanced): on narrow screens its page is opened
 *   ... --es arrivalButtons off  the smart-home arrival buttons' main switch (on/off)
 *   ... --ez night true      the car's night-mode sensor (used when the theme is Auto)
 *   ... --es theme night     force the theme: day, night or auto
 *   ... --es touch 960,540   a tap from the car's touchscreen (video pixels); "x1,y1,x2,y2" drags
 *   ... --es tcp 192.0.2.10:5277  start a session with a head unit listening on the network (port 5277 if left out)
 *   ... --es shizuku probe   move media audio through Shizuku for 3 s and log what arrives ("allow" asks permission)
 *   ... --es shizuku bluetooth  answer a pairing that is not happening through Shizuku: logs whether the shell
 *                            side reaches Bluetooth ("false" is right, an exception is not)
 *   ... --ez restartVideo true  rebuild the car screen pipeline, as the stall watchdog does
 *   ... --es units imperial  distances and pressures: metric, imperial or auto;
 *                            --es temperature fahrenheit: celsius, fahrenheit or auto;
 *                            --es formats uk: dates and numbers as in italy, us, uk or auto
 *   ... --es screen 9:16     pretend the car screen has this shape (or a size like 1280x480) inside the
 *                            real frame, to try portrait and wide layouts on any head unit; "off" to stop
 *   ... --es section music   open a section: home, music, map, phone or settings
 *   ... --es layout trio     home layout: single, split or trio
 *   ... --es widgets "clock,calendar,weather"  what the home slots hold, the big one first (music, notifications,
 *                            favorites, clock, calendar, weather, map; "TYPE:param" for a type with a parameter;
 *                            "default" resets them), and opens the home screen
 *   ... --ez widgetDemo true pretend calendar events and favorites in the home widgets (false = the phone's own)
 *   ... --ez musicPanel true music section with the library panel (false = full-screen player)
 *   ... --es call ringing    pretend call: ringing, dialing, active, holding, ended or off; it also goes to the
 *                            car's phone status channel (cluster), and the call screen's mute and hold change it
 *   ... --es cluster call    a button pressed on the car's display, as the phone status channel gets it:
 *                            call or enter answers a ringing call (any InstrumentClusterAction name)
 *   ... --es notify "Text"   pretend message (add --es from "Name" and --ez group true for a group chat)
 *   ... --ez demo true       show only pretend notifications (screenshots without personal data)
 *   ... --ez clearDemo true  remove the pretend notifications
 *   ... --es motion moving   pretend the car moves ("standing" stands, "off" back to what the car says): the
 *                            car settings and the edit modes are only there while it stands
 *   ... --es vehicle demo    pretend car data on the vehicle screen ("off" clears it, "diagnostics" opens the codes);
 *                            --es dtc "P0300,P0171" sets the fault codes ("" clears them);
 *                            --es passenger on: someone in the front passenger seat ("rear": only in the back,
 *                            "off": the driver alone, "none": no occupancy reported), for the notification privacy
 *   ... --es rail "MAP:GROUP_1,VEHICLE:GROUP_1"  arrange the app bar (missing items keep their default;
 *                            "default" resets it)
 *   ... --es problem certificate_rejected  the phone notification for a car that refused the connection
 *                            (any ConnectionProblem name; --ei code -3 and --es carMaker Volkswagen fill the
 *                            details, "off" removes it)
 *   ... --es smarthome demo  a pretend smart-home service with devices, areas and a home place (no server):
 *                            in the app bar, choices made, gate and garage as arrival buttons ("off" removes it,
 *                            "open" opens the smart-home screen)
 *   ... --es arrival inside  pretend position for the arrival buttons, with the real or the demo devices:
 *                            "inside", "outside", "distance 2000[,90]" (metres from the home place, bearing),
 *                            "panel" reopens the panel, "real" goes back to the real position. Also works
 *                            without a car session. Each check is logged ("Smart home: <id> 812 m, outside,
 *                            next check in 20 s", "entered ... at 380 m", "left ...")
 *   ... --es navPrompt "In 300 metres, turn right"  speak it like a navigation app (navigation guidance usage):
 *                            with Shizuku it goes to the car's guidance stream, otherwise the phone speaks it and
 *                            the music in the car is lowered meanwhile
 *   ... --ez copyAudio true  copy the car audio as without Shizuku for this connection (asks the capture consent
 *                            if needed; false goes back to Shizuku), to try the no-Shizuku paths
 *   ... --es location 45.46,9.19[,50]  a pretend position (and km/h) until --es location off
 *   ... --es drive 2000,50[,90]  a pretend drive through the home place: starts 2000 m away, 50 km/h, goes past
 *                            and away, so the adaptive interval, arriving, the hysteresis and leaving show in the
 *                            log. Add --ez carGps true to any of these to feed it like the car's GPS (every 2 s)
 *                            instead of the phone's polled location
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
        // A head unit listening on the network, e.g. the bench Pi in wireless mode: "host" or "host:port"
        intent.getStringExtra("tcp")?.let { target ->
            val host = target.substringBefore(':')
            val port = target.substringAfter(':', "").toIntOrNull() ?: AapService.DEFAULT_TCP_PORT
            runCatching {
                context.startForegroundService(
                    Intent(context, AapService::class.java).setAction(AapService.ACTION_CONNECT_TCP)
                        .putExtra(AapService.EXTRA_HOST, host).putExtra(AapService.EXTRA_PORT, port)
                )
            }.onFailure { EventLog.log("TCP connect: cannot start the service ($it); open the app first") }
        }
        when (intent.getStringExtra("shizuku")) {
            "allow" -> ShizukuAudio.requestPermission()
            "probe" -> thread(name = "debug-shizuku") { probeShizuku(context) }
            "bluetooth" -> thread(name = "debug-shizuku") {
                EventLog.log("Debug: Shizuku Bluetooth answered ${ShizukuAudio.confirmPairing(context, "00:11:22:33:44:55")}")
            }
        }
        intent.getStringExtra("units")?.let { name ->
            UnitSystem.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { LauncherSettings.get(context).setUnitSystem(it) }
        }
        intent.getStringExtra("formats")?.let { name ->
            FormatRegion.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { LauncherSettings.get(context).setFormatRegion(it) }
        }
        intent.getStringExtra("temperature")?.let { name ->
            TemperatureUnit.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { LauncherSettings.get(context).setTemperatureUnit(it) }
        }

        when (intent.getStringExtra("editHome")) {
            "on" -> HomeEdit.start(LauncherSettings.get(context))
            "off" -> HomeEdit.stop()
            null -> Unit
            else -> intent.getStringExtra("editHome")?.removePrefix("pick")?.trim()?.toIntOrNull()?.let {
                HomeEdit.start(LauncherSettings.get(context))
                HomeEdit.pick(it - 1)
            }
        }
        when (intent.getStringExtra("arrangeBar")) {
            "on" -> BarEditor.open(null)
            "off" -> BarEditor.close()
        }
        intent.getStringExtra("settings")?.let { name ->
            SettingsCategory.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let {
                CarSettingsNav.category.value = it
                CarSettingsNav.pageOpen.value = true
                LauncherSettings.get(context).setSection(Section.SETTINGS)
            }
        }
        when (intent.getStringExtra("arrivalButtons")) {
            "on" -> SmartHome.run { init(context); setArrivalEnabled(true) }
            "off" -> SmartHome.run { init(context); setArrivalEnabled(false) }
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
        intent.getStringExtra("navPrompt")?.let { FakeNavPrompt.speak(context, it) }
        if (intent.hasExtra("copyAudio")) {
            val copy = intent.getBooleanExtra("copyAudio", false)
            CarAudio.copyInstead(copy)
            if (copy && !CarAudio.hasProjection) {
                context.startActivity(Intent(context, AudioConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
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
        intent.getStringExtra("widgets")?.let { spec ->
            val settings = LauncherSettings.get(context)
            // Type names in any case; a parameter, if any, as it is
            val choices = spec.split(",").map { it.trim() }.mapNotNull { WidgetChoice.decode(it.substringBefore(':').uppercase() + it.drop(it.substringBefore(':').length)) }
            settings.setWidgets(if (spec == "default") HomeSlots.Default else choices)
            settings.setSection(Section.HOME)
        }
        if (intent.hasExtra("widgetDemo")) {
            if (intent.getBooleanExtra("widgetDemo", true)) WidgetDemo.show() else WidgetDemo.clear()
        }
        if (intent.hasExtra("musicPanel")) {
            LauncherSettings.get(context).setMusicPanel(intent.getBooleanExtra("musicPanel", true))
        }
        intent.getStringExtra("call")?.let { name ->
            Calls.simulate(CallPhase.entries.firstOrNull { it.name.equals(name, ignoreCase = true) })
        }
        intent.getStringExtra("cluster")?.let { name ->
            InstrumentClusterAction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let {
                EventLog.log("Phone status: input $it (debug)")
                PhoneStatusChannel.onClusterAction(it)
            }
        }
        // Standing or moving, for what only works while the car stands (the car settings, editing the screen)
        when (intent.getStringExtra("motion")) {
            "moving" -> CarMotionState.pretend.value = CarMotion.MOVING
            "standing" -> CarMotionState.pretend.value = CarMotion.STANDING
            "off" -> CarMotionState.pretend.value = null
        }
        when (intent.getStringExtra("vehicle")) {
            "demo" -> VehicleData.simulate()
            "off" -> VehicleData.clear()
            "diagnostics" -> VehicleScreenState.showDiagnostics.value = true
        }
        intent.getStringExtra("dtc")?.let { codes ->
            val list = codes.split(',', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
            VehicleData.publish(VehicleSource.DEMO, VehicleField.FaultCodes of list)
        }
        // Pretend occupancy, as a car source would report it: drives the notification privacy on Auto
        intent.getStringExtra("passenger")?.let { who ->
            val (front, rear) = when (who) {
                "on" -> true to false
                "rear" -> false to true
                "off" -> false to false
                else -> null to null
            }
            VehicleData.publish(VehicleSource.DEMO, VehicleField.PassengerPresent of front, VehicleField.RearOccupied of rear)
        }
        if (intent.getBooleanExtra("clearDemo", false)) CarNotifications.clearSimulated()
        if (intent.hasExtra("demo")) CarNotifications.demoOnly = intent.getBooleanExtra("demo", false)
        intent.getStringExtra("notify")?.let { text ->
            CarNotifications.simulate(intent.getStringExtra("from") ?: "Giulia", text, intent.getBooleanExtra("group", false))
        }
        intent.getStringExtra("screen")?.let { CarState.simulatedScreen.value = it.takeUnless { s -> s == "off" } }
        smartHome(context, intent)

        // Next/previous held: straight to the scrubbing, which needs no car screen (the music player is enough)
        val holdKey = intent.getIntExtra("holdKey", 0)
        if (holdKey == android.view.KeyEvent.KEYCODE_MEDIA_NEXT || holdKey == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            com.simoneluconi.betterauto.media.KeyScrub.press(forward = holdKey == android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { com.simoneluconi.betterauto.media.KeyScrub.release() },
                intent.getIntExtra("holdMs", 3000).toLong(),
            )
        }

        val projection = Projection.active ?: return
        val rotary = intent.getIntExtra("rotary", 0)
        if (rotary != 0) projection.dispatchRotary(rotary)
        val key = intent.getIntExtra("key", 0)
        if (key != 0) {
            projection.dispatchKey(key, down = true, longPress = false)
            projection.dispatchKey(key, down = false, longPress = false)
        }
        // Held like a knob center: down now, up later (no long-press flag, as most cars send it)
        val longKey = intent.getIntExtra("longKey", 0)
        if (longKey != 0) {
            projection.dispatchKey(longKey, down = true, longPress = false)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { projection.dispatchKey(longKey, down = false, longPress = false) },
                intent.getIntExtra("holdMs", LONG_KEY_MS).toLong(),
            )
        }
        intent.getStringExtra("longTouch")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }?.let { (x, y) ->
            val video = CarState.video.value
            if (video.width == 0) return@let
            thread(name = "debug-long-touch") {
                fun send(action: PointerAction) = projection.dispatchTouch(
                    TouchEvent.newBuilder().setAction(action).setActionIndex(0)
                        .addPointerData(TouchEvent.Pointer.newBuilder().setX(x).setY(y).setPointerId(0)).build(),
                    video.width, video.height,
                )
                send(PointerAction.ACTION_DOWN)
                Thread.sleep(LONG_TOUCH_MS)
                send(PointerAction.ACTION_UP)
            }
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

    /** The smart-home extras: the demo service, and pretend positions for the arrival buttons. */
    private fun smartHome(context: Context, intent: Intent) {
        when (intent.getStringExtra("smarthome")) {
            "demo" -> FakeSmartHome.install(context)
            "off" -> SmartHome.unregister(FakeSmartHome.ID)
            "open" -> LauncherSettings.get(context).setSection(Section.SMART_HOME)
        }
        if (intent.hasExtra("arrival") || intent.hasExtra("location") || intent.hasExtra("drive")) SmartHome.init(context)
        val carGps = intent.getBooleanExtra("carGps", false)
        intent.getStringExtra("arrival")?.let { FakeSmartHome.arrival(it, carGps) }
        intent.getStringExtra("location")?.let { FakeSmartHome.location(it, carGps) }
        intent.getStringExtra("drive")?.let { FakeSmartHome.drive(it, carGps) }
    }

    /** Routes media audio through the Shizuku service for a few seconds: how much arrives, and how loud. */
    private fun probeShizuku(context: Context) {
        EventLog.log("Shizuku probe: ${ShizukuAudio.state(context)}")
        // A route of its own, so a car session's routes keep running
        val connection = ShizukuAudio.connect(context, PROBE_ROUTE) ?: return
        try {
            val pipe = connection.router.start(PROBE_ROUTE, 48000, 2, android.media.AudioFormat.ENCODING_PCM_16BIT, intArrayOf(), intArrayOf())
            EventLog.log("Shizuku probe: ${connection.router.status()}")
            if (pipe == null) return
            val input = ParcelFileDescriptor.AutoCloseInputStream(pipe)
            // Nothing playing means nothing to read: close the pipe after the time is up to unblock the read
            thread(name = "debug-shizuku-timer") {
                Thread.sleep(PROBE_MS)
                runCatching { input.close() }
            }
            val buffer = ByteArray(4096)
            var bytes = 0L
            var peak = 0
            runCatching {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes += n
                    for (i in 0 until n - 1 step 2) {
                        val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort().toInt()
                        peak = maxOf(peak, kotlin.math.abs(sample))
                    }
                }
            }
            EventLog.log("Shizuku probe: $bytes bytes in ${PROBE_MS / 1000} s (${bytes * 1000 / PROBE_MS / 4} frames/s), peak $peak of 32767")
        } finally {
            connection.close()
            EventLog.log("Shizuku probe: done, ${connection.router.runCatching { status() }.getOrDefault("service gone")}")
        }
    }

    private companion object {
        const val LONG_KEY_MS = 800
        const val LONG_TOUCH_MS = 900L
        const val TOUCH_DRAG_STEPS = 20
        const val PROBE_MS = 3000L
        const val PROBE_ROUTE = 9
    }
}
