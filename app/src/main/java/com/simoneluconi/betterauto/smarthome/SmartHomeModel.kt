package com.simoneluconi.betterauto.smarthome

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a device is, whatever service it comes from. Providers map their own types to these; the car
 * screen, the arrival buttons and the device selection only know these.
 */
enum class DeviceKind {
    GATE, GARAGE_DOOR, DOOR, COVER, LOCK, LIGHT, SWITCH, BUTTON, SCENE, SCRIPT, SENSOR, BINARY_SENSOR;

    /** Kinds that open and close: the arrival buttons are usually among these. */
    val opens: Boolean get() = this == GATE || this == GARAGE_DOOR || this == DOOR || this == COVER

    /** Read only: shown, never acted on. */
    val readOnly: Boolean get() = this == SENSOR || this == BINARY_SENSOR
}

/** What can be asked of a device; each device lists the ones it supports. */
enum class DeviceAction { OPEN, STOP, CLOSE, LOCK, UNLOCK, TOGGLE, TURN_ON, TURN_OFF, RUN }

/** The live state of a device, as far as the car screen cares. */
enum class DeviceCondition {
    OPEN, OPENING, CLOSED, CLOSING, LOCKED, UNLOCKED, LOCKING, UNLOCKING, JAMMED, ON, OFF,
    /** A sensor: see [DeviceState.value]. */
    VALUE,
    UNAVAILABLE, UNKNOWN,
}

data class DeviceState(
    val condition: DeviceCondition,
    /** A sensor's reading, already formatted by the service (e.g. "21.5"), without its unit. */
    val value: String? = null,
    /** Covers: 0 closed .. 100 open, when the device reports it. */
    val position: Int? = null,
) {
    val busy: Boolean
        get() = condition == DeviceCondition.OPENING || condition == DeviceCondition.CLOSING ||
            condition == DeviceCondition.LOCKING || condition == DeviceCondition.UNLOCKING

    companion object {
        val Unknown = DeviceState(DeviceCondition.UNKNOWN)
    }
}

/** A room or area of the home, as the service names it. */
data class SmartArea(val id: String, val name: String)

/** A place with a radius (the home, the office...), in degrees and metres. */
data class SmartPlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Double,
    /** The service's own home place: arrival buttons use it unless told otherwise. */
    val home: Boolean,
)

/**
 * One device (or entity, scene, script...) of a provider. [id] is the provider's own id, opaque to
 * everything else; [key] is unique across providers.
 */
data class SmartDevice(
    val providerId: String,
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val areaId: String? = null,
    val actions: List<DeviceAction> = emptyList(),
    /** The provider thinks the driver wants it (HA: exposed to voice assistants): ticked by default. */
    val suggested: Boolean = false,
    /** Unit of a sensor's value ("°C", "%"). */
    val unit: String? = null,
) {
    val key: String get() = "$providerId/$id"
}

/** Everything a provider has to choose from: areas, devices and places. Cached on the phone between sessions. */
data class SmartCatalog(
    val areas: List<SmartArea> = emptyList(),
    val devices: List<SmartDevice> = emptyList(),
    val places: List<SmartPlace> = emptyList(),
) {
    val home: SmartPlace? get() = places.firstOrNull { it.home } ?: places.firstOrNull()

    fun area(id: String?): SmartArea? = id?.let { a -> areas.firstOrNull { it.id == a } }

    fun device(id: String): SmartDevice? = devices.firstOrNull { it.id == id }

    fun toJson(): String = JSONObject()
        .put("v", FORMAT)
        .put("areas", JSONArray().apply { areas.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        .put("devices", JSONArray().apply {
            devices.forEach { d ->
                put(
                    JSONObject().put("provider", d.providerId).put("id", d.id).put("name", d.name).put("kind", d.kind.name)
                        .putOpt("area", d.areaId).put("actions", JSONArray(d.actions.map { it.name }))
                        .put("suggested", d.suggested).putOpt("unit", d.unit)
                )
            }
        })
        .put("places", JSONArray().apply {
            places.forEach { p ->
                put(
                    JSONObject().put("id", p.id).put("name", p.name).put("lat", p.latitude).put("lon", p.longitude)
                        .put("radius", p.radiusM).put("home", p.home)
                )
            }
        })
        .toString()

    companion object {
        /** Bumped when what a catalog holds changes (2: devices named without their device): older caches are read again. */
        private const val FORMAT = 2

        /** A cached catalog; null when there is none, it can't be read or it is older (it is then fetched again). */
        fun fromJson(text: String?): SmartCatalog? = text?.let {
            runCatching {
                val json = JSONObject(it)
                if (json.optInt("v") != FORMAT) return@runCatching null
                SmartCatalog(
                    areas = json.optJSONArray("areas").objects().map { a -> SmartArea(a.getString("id"), a.getString("name")) },
                    devices = json.optJSONArray("devices").objects().mapNotNull { d ->
                        val kind = DeviceKind.entries.firstOrNull { k -> k.name == d.optString("kind") } ?: return@mapNotNull null
                        SmartDevice(
                            providerId = d.getString("provider"),
                            id = d.getString("id"),
                            name = d.getString("name"),
                            kind = kind,
                            areaId = d.optStringOrNull("area"),
                            actions = d.optJSONArray("actions").strings().mapNotNull { n -> DeviceAction.entries.firstOrNull { a -> a.name == n } },
                            suggested = d.optBoolean("suggested"),
                            unit = d.optStringOrNull("unit"),
                        )
                    },
                    places = json.optJSONArray("places").objects().map { p ->
                        SmartPlace(p.getString("id"), p.getString("name"), p.getDouble("lat"), p.getDouble("lon"), p.getDouble("radius"), p.optBoolean("home"))
                    },
                )
            }.getOrNull()
        }
    }
}

/** How a provider's link to its service is doing. */
enum class LinkState {
    /** Never linked, or unlinked: nothing to show in the car. */
    NOT_LINKED,
    /** Linked, not talking now (no car session): states are the last known. */
    IDLE,
    CONNECTING,
    ONLINE,
    /** The service can't be reached: retried with a growing pause. */
    OFFLINE,
    /** The service refused our credentials: only linking again on the phone helps. */
    AUTH_FAILED,
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> opt(i) as? String }

/** org.json gives "null" or "" for missing strings: both mean none here. */
internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
