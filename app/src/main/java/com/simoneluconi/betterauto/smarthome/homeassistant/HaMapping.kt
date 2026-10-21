package com.simoneluconi.betterauto.smarthome.homeassistant

import com.simoneluconi.betterauto.smarthome.DeviceAction
import com.simoneluconi.betterauto.smarthome.DeviceCondition
import com.simoneluconi.betterauto.smarthome.DeviceKind
import com.simoneluconi.betterauto.smarthome.DeviceState
import com.simoneluconi.betterauto.smarthome.SmartArea
import com.simoneluconi.betterauto.smarthome.SmartCatalog
import com.simoneluconi.betterauto.smarthome.SmartDevice
import com.simoneluconi.betterauto.smarthome.SmartPlace
import com.simoneluconi.betterauto.smarthome.objects
import com.simoneluconi.betterauto.smarthome.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A Home Assistant service call: domain, service, on one entity. */
data class HaServiceCall(val domain: String, val service: String, val entityId: String)

/**
 * Home Assistant entities to the canonical model and back. Domains, device classes and feature bits are
 * Home Assistant's own (cover: OPEN 1, CLOSE 2, STOP 8; lock: OPEN 1). [SmartDevice.id] is the entity id.
 */
object HaMapping {
    const val PROVIDER_ID = "homeassistant"

    private val GateClasses = setOf<String?>("gate")
    private val GarageClasses = setOf<String?>("garage", "garage_door")
    /** Binary sensors worth a tile: whether something is open, locked, or there. */
    private val OpeningSensorClasses = setOf<String?>("door", "garage_door", "opening", "window", "lock")
    /** Sensors that make sense on a tile even when not exposed: offered, not ticked. */
    private val UsefulSensorClasses = setOf<String?>("temperature", "humidity")

    fun domain(entityId: String) = entityId.substringBefore('.')

    fun kind(entityId: String, attributes: JSONObject?): DeviceKind? {
        val deviceClass = attributes?.optStringOrNull("device_class")
        return when (domain(entityId)) {
            "cover" -> when (deviceClass) {
                in GateClasses -> DeviceKind.GATE
                in GarageClasses -> DeviceKind.GARAGE_DOOR
                "door" -> DeviceKind.DOOR
                else -> DeviceKind.COVER
            }
            "lock" -> DeviceKind.LOCK
            "light" -> DeviceKind.LIGHT
            "switch", "input_boolean" -> DeviceKind.SWITCH
            "button", "input_button" -> DeviceKind.BUTTON
            "scene" -> DeviceKind.SCENE
            "script" -> DeviceKind.SCRIPT
            "sensor" -> DeviceKind.SENSOR
            "binary_sensor" -> DeviceKind.BINARY_SENSOR
            else -> null
        }
    }

    fun actions(entityId: String, kind: DeviceKind, attributes: JSONObject?): List<DeviceAction> {
        val features = attributes?.takeIf { it.has("supported_features") }?.optInt("supported_features")
        return when (kind) {
            DeviceKind.GATE, DeviceKind.GARAGE_DOOR, DeviceKind.DOOR, DeviceKind.COVER -> {
                // No features said: offer all three, Home Assistant refuses what the cover can't do
                if (features == null) listOf(DeviceAction.OPEN, DeviceAction.STOP, DeviceAction.CLOSE)
                else buildList {
                    if (features and 1 != 0) add(DeviceAction.OPEN)
                    if (features and 8 != 0) add(DeviceAction.STOP)
                    if (features and 2 != 0) add(DeviceAction.CLOSE)
                }
            }
            DeviceKind.LOCK -> listOf(DeviceAction.LOCK, DeviceAction.UNLOCK) + if (features != null && features and 1 != 0) listOf(DeviceAction.OPEN) else emptyList()
            DeviceKind.LIGHT, DeviceKind.SWITCH -> listOf(DeviceAction.TOGGLE, DeviceAction.TURN_ON, DeviceAction.TURN_OFF)
            DeviceKind.BUTTON, DeviceKind.SCENE, DeviceKind.SCRIPT -> listOf(DeviceAction.RUN)
            DeviceKind.SENSOR, DeviceKind.BINARY_SENSOR -> emptyList()
        }.let { if (domain(entityId) == "input_button") listOf(DeviceAction.RUN) else it }
    }

    /** The service for [action] on [entityId], or null when it makes no sense there. */
    fun serviceCall(entityId: String, action: DeviceAction): HaServiceCall? {
        val domain = domain(entityId)
        val service = when (domain) {
            "cover" -> when (action) {
                DeviceAction.OPEN -> "open_cover"
                DeviceAction.CLOSE -> "close_cover"
                DeviceAction.STOP -> "stop_cover"
                DeviceAction.TOGGLE -> "toggle"
                else -> null
            }
            "lock" -> when (action) {
                DeviceAction.LOCK -> "lock"
                DeviceAction.UNLOCK -> "unlock"
                DeviceAction.OPEN -> "open"
                else -> null
            }
            "light", "switch", "input_boolean" -> when (action) {
                DeviceAction.TOGGLE -> "toggle"
                DeviceAction.TURN_ON -> "turn_on"
                DeviceAction.TURN_OFF -> "turn_off"
                else -> null
            }
            "button", "input_button" -> "press".takeIf { action == DeviceAction.RUN }
            "scene", "script" -> "turn_on".takeIf { action == DeviceAction.RUN }
            else -> null
        } ?: return null
        return HaServiceCall(domain, service, entityId)
    }

    fun state(entityId: String, raw: HaEntityState): DeviceState {
        val s = raw.state
        if (s == "unavailable") return DeviceState(DeviceCondition.UNAVAILABLE)
        if (s == "unknown" || s.isEmpty()) return DeviceState.Unknown
        val position = raw.attributes.takeIf { it.has("current_position") }?.optInt("current_position")
        return when (domain(entityId)) {
            "cover" -> DeviceState(
                when (s) {
                    "open" -> DeviceCondition.OPEN
                    "opening" -> DeviceCondition.OPENING
                    "closing" -> DeviceCondition.CLOSING
                    "closed" -> DeviceCondition.CLOSED
                    else -> DeviceCondition.UNKNOWN
                },
                position = position,
            )
            "lock" -> DeviceState(
                when (s) {
                    "locked" -> DeviceCondition.LOCKED
                    "unlocked", "open" -> DeviceCondition.UNLOCKED
                    "locking" -> DeviceCondition.LOCKING
                    "unlocking", "opening" -> DeviceCondition.UNLOCKING
                    "jammed" -> DeviceCondition.JAMMED
                    else -> DeviceCondition.UNKNOWN
                }
            )
            "light", "switch", "input_boolean", "script" -> DeviceState(if (s == "on") DeviceCondition.ON else DeviceCondition.OFF)
            "binary_sensor" -> {
                val opening = raw.attributes.optStringOrNull("device_class") in OpeningSensorClasses
                val on = s == "on"
                DeviceState(
                    when {
                        // A lock sensor is "on" when unlocked
                        raw.attributes.optStringOrNull("device_class") == "lock" -> if (on) DeviceCondition.UNLOCKED else DeviceCondition.LOCKED
                        opening -> if (on) DeviceCondition.OPEN else DeviceCondition.CLOSED
                        else -> if (on) DeviceCondition.ON else DeviceCondition.OFF
                    }
                )
            }
            "sensor" -> DeviceState(DeviceCondition.VALUE, value = s)
            // Buttons and scenes hold the time they last ran: nothing to show
            else -> DeviceState.Unknown
        }
    }

    /**
     * The catalog from Home Assistant's registries and states. [exposed] is the voice-assistant expose
     * list (entity ids exposed to at least one assistant), null when it could not be read. Entities that
     * are disabled, hidden, or configuration/diagnostic are left out; sensors only when exposed or of a
     * useful class.
     */
    fun catalog(
        areas: JSONArray?,
        devices: JSONArray?,
        entities: JSONArray?,
        states: JSONArray?,
        exposed: Set<String>?,
        config: JSONObject?,
    ): SmartCatalog {
        val areaList = areas.objects().mapNotNull { a ->
            val id = a.optStringOrNull("area_id") ?: return@mapNotNull null
            SmartArea(id, a.optStringOrNull("name") ?: id)
        }
        val deviceArea = devices.objects().associate { d -> d.optString("id") to d.optStringOrNull("area_id") }
        val registry = entities.objects().associateBy { it.optString("entity_id") }
        val out = ArrayList<SmartDevice>()
        val places = ArrayList<SmartPlace>()
        for (s in states.objects()) {
            val entityId = s.optStringOrNull("entity_id") ?: continue
            val attrs = s.optJSONObject("attributes") ?: JSONObject()
            if (domain(entityId) == "zone") {
                zone(entityId, attrs)?.let { places += it }
                continue
            }
            val reg = registry[entityId]
            if (reg != null && (!reg.isNull("disabled_by") || !reg.isNull("hidden_by") || !reg.isNull("entity_category"))) continue
            val kind = kind(entityId, attrs) ?: continue
            val suggested = exposed?.contains(entityId) == true
            val deviceClass = attrs.optStringOrNull("device_class")
            if (kind == DeviceKind.SENSOR && !suggested && deviceClass !in UsefulSensorClasses) continue
            if (kind == DeviceKind.BINARY_SENSOR && !suggested && deviceClass !in OpeningSensorClasses) continue
            val name = attrs.optStringOrNull("friendly_name") ?: reg?.optStringOrNull("name") ?: reg?.optStringOrNull("original_name") ?: entityId
            val area = reg?.optStringOrNull("area_id") ?: reg?.optStringOrNull("device_id")?.let { deviceArea[it] }
            out += SmartDevice(
                providerId = PROVIDER_ID,
                id = entityId,
                name = name,
                kind = kind,
                areaId = area?.takeIf { a -> areaList.any { it.id == a } },
                actions = actions(entityId, kind, attrs),
                suggested = suggested,
                unit = attrs.optStringOrNull("unit_of_measurement"),
            )
        }
        // No home zone in the states (unusual): the configured home location with a small radius
        if (places.none { it.home } && config != null && config.has("latitude") && config.has("longitude")) {
            places += SmartPlace("zone.home", config.optStringOrNull("location_name") ?: "Home", config.getDouble("latitude"), config.getDouble("longitude"), 100.0, home = true)
        }
        return SmartCatalog(areaList, out, places)
    }

    private fun zone(entityId: String, attrs: JSONObject): SmartPlace? {
        if (!attrs.has("latitude") || !attrs.has("longitude")) return null
        if (attrs.optBoolean("passive")) return null
        return SmartPlace(
            id = entityId,
            name = attrs.optStringOrNull("friendly_name") ?: entityId,
            latitude = attrs.getDouble("latitude"),
            longitude = attrs.getDouble("longitude"),
            radiusM = attrs.optDouble("radius", 100.0),
            home = entityId == "zone.home",
        )
    }

    /** The expose list's answer: {"exposed_entities": {"light.x": {"conversation": true, ...}}}. */
    fun exposed(result: Any?): Set<String>? {
        val map = (result as? JSONObject)?.optJSONObject("exposed_entities") ?: return null
        return map.keys().asSequence().filter { id -> map.optJSONObject(id)?.let { o -> o.keys().asSequence().any { o.optBoolean(it) } } == true }.toSet()
    }
}
