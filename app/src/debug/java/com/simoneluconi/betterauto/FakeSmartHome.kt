package com.simoneluconi.betterauto

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.sp
import com.simoneluconi.betterauto.smarthome.DeviceAction
import com.simoneluconi.betterauto.smarthome.DeviceCondition
import com.simoneluconi.betterauto.smarthome.DeviceKind
import com.simoneluconi.betterauto.smarthome.DeviceState
import com.simoneluconi.betterauto.smarthome.FakeRoute
import com.simoneluconi.betterauto.smarthome.LinkState
import com.simoneluconi.betterauto.smarthome.SmartArea
import com.simoneluconi.betterauto.smarthome.SmartCatalog
import com.simoneluconi.betterauto.smarthome.SmartDevice
import com.simoneluconi.betterauto.smarthome.SmartHome
import com.simoneluconi.betterauto.smarthome.SmartHomeProvider
import com.simoneluconi.betterauto.smarthome.SmartPlace
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A pretend smart-home service for the bench and screenshots (debug builds): a few devices in a few
 * areas, a home place, commands that change the states after a moment. No server, no personal data.
 */
class FakeSmartHome : SmartHomeProvider {
    override val id = ID
    override val name = "Demo home"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun icon(context: Context): ImageBitmap? = null

    override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.ONLINE)
    override val homeName: StateFlow<String?> = MutableStateFlow("Demo")

    private val all = listOf(
        device("gate", "Gate", DeviceKind.GATE, "outside", listOf(DeviceAction.OPEN, DeviceAction.STOP, DeviceAction.CLOSE)),
        device("garage", "Garage door", DeviceKind.GARAGE_DOOR, "garage", listOf(DeviceAction.OPEN, DeviceAction.STOP, DeviceAction.CLOSE)),
        device("front_door", "Front door", DeviceKind.LOCK, "entrance", listOf(DeviceAction.LOCK, DeviceAction.UNLOCK)),
        device("garden_lights", "Garden lights", DeviceKind.LIGHT, "outside", listOf(DeviceAction.TOGGLE, DeviceAction.TURN_ON, DeviceAction.TURN_OFF)),
        device("porch_light", "Porch light", DeviceKind.LIGHT, "entrance", listOf(DeviceAction.TOGGLE, DeviceAction.TURN_ON, DeviceAction.TURN_OFF)),
        device("heating", "Heating", DeviceKind.SWITCH, "living", listOf(DeviceAction.TOGGLE, DeviceAction.TURN_ON, DeviceAction.TURN_OFF)),
        device("arrive", "I'm home", DeviceKind.SCENE, "living", listOf(DeviceAction.RUN)),
        device("night", "Good night", DeviceKind.SCRIPT, "living", listOf(DeviceAction.RUN)),
        device("living_temp", "Living room", DeviceKind.SENSOR, "living", emptyList(), unit = "°C"),
        device("garage_sensor", "Garage window", DeviceKind.BINARY_SENSOR, "garage", emptyList()),
    )

    override val catalog: StateFlow<SmartCatalog?> = MutableStateFlow(
        SmartCatalog(
            areas = listOf(SmartArea("outside", "Outside"), SmartArea("garage", "Garage"), SmartArea("entrance", "Entrance"), SmartArea("living", "Living room")),
            devices = all,
            // Same spot as the pretend vehicle data (VehicleData.simulate)
            places = listOf(SmartPlace("home", "Home", 45.4642, 9.19, 100.0, home = true)),
        )
    )

    private val _states = MutableStateFlow(
        mapOf(
            "gate" to DeviceState(DeviceCondition.CLOSED),
            "garage" to DeviceState(DeviceCondition.OPEN, position = 100),
            "front_door" to DeviceState(DeviceCondition.LOCKED),
            "garden_lights" to DeviceState(DeviceCondition.OFF),
            "porch_light" to DeviceState(DeviceCondition.ON),
            "heating" to DeviceState(DeviceCondition.ON),
            "arrive" to DeviceState.Unknown,
            "night" to DeviceState(DeviceCondition.OFF),
            "living_temp" to DeviceState(DeviceCondition.VALUE, value = "21.5"),
            "garage_sensor" to DeviceState(DeviceCondition.CLOSED),
        )
    )
    override val states: StateFlow<Map<String, DeviceState>> = _states

    override fun follow(deviceIds: Set<String>) = Unit

    override suspend fun refreshCatalog() = true

    override suspend fun perform(deviceId: String, action: DeviceAction): Boolean {
        delay(400)
        fun set(state: DeviceState) {
            _states.value = _states.value + (deviceId to state)
        }
        val now = _states.value[deviceId]?.condition
        when (action) {
            DeviceAction.OPEN, DeviceAction.CLOSE -> {
                val open = action == DeviceAction.OPEN
                set(DeviceState(if (open) DeviceCondition.OPENING else DeviceCondition.CLOSING))
                scope.launch {
                    delay(MOVE_MS)
                    set(DeviceState(if (open) DeviceCondition.OPEN else DeviceCondition.CLOSED, position = if (open) 100 else 0))
                }
            }
            DeviceAction.STOP -> set(DeviceState(DeviceCondition.OPEN, position = 40))
            DeviceAction.LOCK -> set(DeviceState(DeviceCondition.LOCKED))
            DeviceAction.UNLOCK -> set(DeviceState(DeviceCondition.UNLOCKED))
            DeviceAction.TOGGLE -> set(DeviceState(if (now == DeviceCondition.ON) DeviceCondition.OFF else DeviceCondition.ON))
            DeviceAction.TURN_ON -> set(DeviceState(DeviceCondition.ON))
            DeviceAction.TURN_OFF -> set(DeviceState(DeviceCondition.OFF))
            DeviceAction.RUN -> if (deviceId == "night") {
                set(DeviceState(DeviceCondition.ON))
                scope.launch {
                    delay(MOVE_MS)
                    set(DeviceState(DeviceCondition.OFF))
                }
            }
        }
        return true
    }

    override fun unlink() {
        SmartHome.unregister(ID)
        SmartHome.forget(ID)
    }

    @Composable
    override fun LinkCard() {
        CarLabel("Demo home (debug): pretend devices, no server", 15.sp, LocalCarColors.current.text2, maxLines = 2)
    }

    private fun device(id: String, name: String, kind: DeviceKind, area: String, actions: List<DeviceAction>, unit: String? = null) =
        SmartDevice(ID, id, name, kind, area, actions, suggested = kind != DeviceKind.SWITCH, unit = unit)

    companion object {
        const val ID = "demo"
        private const val MOVE_MS = 4000L

        /** Registers the demo home with its choices made: the pre-selection, gate and garage on arrival. */
        fun install(context: Context) {
            SmartHome.init(context)
            val fake = FakeSmartHome()
            SmartHome.register(fake)
            fake.catalog.value?.let { SmartHome.applyCatalog(ID, it) }
            SmartHome.update(ID) { c -> c.copy(places = c.places.mapValues { (_, p) -> p.copy(on = true) }) }
            SmartHome.screenProvider.value = ID
            EventLog.log("Smart home: demo home installed")
        }

        /** "inside", "outside", "real", or "distance <m>[,<bearing>]" from the home place. */
        fun arrival(spec: String, asCarGps: Boolean) {
            val watcher = SmartHome.arrival ?: return
            val parts = spec.trim().split(' ', ',').filter { it.isNotEmpty() }
            when (parts.firstOrNull()) {
                "inside" -> watcher.simulate(FakeRoute.FromPlace(0.0), asCarGps)
                "outside" -> watcher.simulate(FakeRoute.FromPlace(OUTSIDE_M), asCarGps)
                "real", "off" -> watcher.simulate(null)
                "panel" -> watcher.openPanel()
                "distance" -> parts.getOrNull(1)?.toDoubleOrNull()?.let { d ->
                    watcher.simulate(FakeRoute.FromPlace(d, parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0), asCarGps)
                }
                else -> EventLog.log("Smart home: arrival \"$spec\" not understood")
            }
        }

        /** "lat,lon[,km/h]" replaces the position until "off". */
        fun location(spec: String, asCarGps: Boolean) {
            val watcher = SmartHome.arrival ?: return
            if (spec == "off") return watcher.simulate(null)
            val p = spec.split(',').mapNotNull { it.trim().toDoubleOrNull() }
            if (p.size < 2) return EventLog.log("Smart home: location \"$spec\" not understood")
            watcher.simulate(FakeRoute.Fixed(p[0], p[1], p.getOrNull(2)?.div(3.6)), asCarGps)
        }

        /** "<start m>,<km/h>[,<bearing>]": a drive through the home place, in and out again. */
        fun drive(spec: String, asCarGps: Boolean) {
            val watcher = SmartHome.arrival ?: return
            val p = spec.split(',').mapNotNull { it.trim().toDoubleOrNull() }
            if (p.size < 2) return EventLog.log("Smart home: drive \"$spec\" not understood")
            watcher.simulate(FakeRoute.Drive(p[0], p[1] / 3.6, System.currentTimeMillis(), p.getOrNull(2) ?: 0.0), asCarGps)
        }

        private const val OUTSIDE_M = 5000.0
    }
}
