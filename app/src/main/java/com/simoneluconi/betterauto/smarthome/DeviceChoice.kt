package com.simoneluconi.betterauto.smarthome

import java.text.Normalizer
import java.util.Locale

/**
 * The arrival buttons of one place: which devices show, from how far beyond the place's own radius,
 * and whether they are on. Switching off keeps the rest, so switching on again restores it.
 */
data class PlaceArrival(
    val devices: Set<String> = emptySet(),
    val offsetM: Int = ProviderChoice.DEFAULT_OFFSET_M,
    val on: Boolean = false,
    /** The order the driver dragged them into on the phone; devices not in it come after. */
    val order: List<String> = emptyList(),
) {
    /** [items] in the driver's order; the others keep theirs, after the ordered ones. */
    fun <T> sorted(items: List<T>, id: (T) -> String): List<T> {
        val rank = order.withIndex().associate { it.value to it.index }
        return items.sortedBy { rank[id(it)] ?: Int.MAX_VALUE }
    }
}

/** What the driver chose for one provider: devices for the car screen, and the arrival buttons per place. */
data class ProviderChoice(
    /** Devices on the car screen; null until the driver (or the pre-selection) chose. */
    val selected: Set<String>? = null,
    /**
     * Arrival buttons by place id; [HOME_PLACE] stands for the provider's home until its id is known
     * (choices saved before places had their own buttons). A place not in it has none yet.
     */
    val places: Map<String, PlaceArrival> = emptyMap(),
) {
    /** The places whose buttons are on and hold devices: what the car may show. */
    val activePlaces: Map<String, PlaceArrival> get() = places.filterValues { it.on && it.devices.isNotEmpty() }

    /** Devices of the places that are on, to follow live during a session. */
    val arrivalDevices: Set<String> get() = activePlaces.values.flatMap { it.devices }.toSet()

    /** [place]'s buttons, also when saved under [HOME_PLACE]. */
    fun arrivalAt(place: SmartPlace): PlaceArrival? = places[place.id] ?: places[HOME_PLACE]?.takeIf { place.home }

    companion object {
        const val DEFAULT_OFFSET_M = 300
        val OFFSETS_M = listOf(100, 300, 500, 1000)
        const val HOME_PLACE = ""
    }
}

/**
 * Pre-selection: what the driver will most likely want in the car, so linking ends with ticks already
 * set. Pure logic on the canonical model, whatever the provider.
 */
object Preselection {
    /** Kinds worth a button in the car, the ones that matter most first. */
    private val CarUseful = listOf(
        DeviceKind.GATE, DeviceKind.GARAGE_DOOR, DeviceKind.DOOR, DeviceKind.LOCK, DeviceKind.COVER,
        DeviceKind.SCENE, DeviceKind.SCRIPT, DeviceKind.BUTTON, DeviceKind.LIGHT, DeviceKind.SWITCH,
        DeviceKind.BINARY_SENSOR, DeviceKind.SENSOR,
    )

    /** Wanted while driving wherever they are: what lets the car in, and one-tap routines. */
    private val Driving = setOf(
        DeviceKind.GATE, DeviceKind.GARAGE_DOOR, DeviceKind.DOOR, DeviceKind.LOCK,
        DeviceKind.SCENE, DeviceKind.SCRIPT, DeviceKind.BUTTON,
    )

    /** Wanted only outside or at the entrance: a garden light yes, the kitchen one no. */
    private val OutsideOnly = setOf(DeviceKind.LIGHT, DeviceKind.SWITCH, DeviceKind.COVER)

    /** With nothing suggested, the same choice among all devices, kept short. */
    const val FALLBACK_CAP = 24

    /**
     * The devices ticked by default: only what makes sense from the car (no sensors, no indoor lights),
     * among the suggested ones, or among all of them, capped, when the provider suggests none.
     */
    fun pick(catalog: SmartCatalog): Set<String> {
        fun useful(d: SmartDevice) = d.kind in Driving ||
            (d.kind in OutsideOnly && (ArrivalCandidates.outside(catalog, d) || ArrivalCandidates.namedForArrival(d)))
        val suggested = catalog.devices.filter { it.suggested && useful(it) }
        if (suggested.isNotEmpty()) return suggested.map { it.id }.toSet()
        return order(catalog.devices.filter(::useful))
            .take(FALLBACK_CAP)
            .map { it.id }
            .toSet()
    }

    /** The order the car screen and the phone list show devices in: by kind importance, then name. */
    fun order(devices: List<SmartDevice>): List<SmartDevice> =
        devices.sortedWith(compareBy<SmartDevice> { CarUseful.indexOf(it.kind) }.thenBy { it.name.lowercase() })
}

/** The arrival buttons proposed after linking: [devices] to show, [ticked] already chosen. */
data class ArrivalProposal(val devices: List<SmartDevice>, val ticked: Set<String>) {
    /** False when nothing was recognised: the driver picks from a short pre-sorted list, nothing ticked. */
    val recognised: Boolean get() = ticked.isNotEmpty()
}

/**
 * Which devices to show when arriving home: gates and garage doors by kind, doors when outside,
 * otherwise relays and buttons whose name or area says gate or garage (Italian and English).
 */
object ArrivalCandidates {
    private val NameWords = listOf(
        "cancello", "cancelletto", "garage", "garages", "basculante", "box", "portone", "carraio", "carrabile",
        "sbarra", "autorimessa", "gate", "driveway", "barrier", "garage door",
    )
    private val OutsideWords = listOf(
        "garage", "esterno", "esterni", "giardino", "cortile", "vialetto", "ingresso esterno",
        "ingresso", "entrata", "portico", "veranda",
        "outside", "outdoor", "outdoors", "exterior", "garden", "yard", "driveway", "front yard",
        "entrance", "entry", "porch",
    )

    /** In an area whose name says outside or entrance. */
    internal fun outside(catalog: SmartCatalog, d: SmartDevice) =
        catalog.area(d.areaId)?.name?.let { matches(it, OutsideWords) } == true

    /** Its name says gate or garage ("Relè cancello"). */
    internal fun namedForArrival(d: SmartDevice) = matches(d.name, NameWords)

    /** At most this many devices in the "pick yourself" list. */
    const val ASK_CAP = 8

    fun propose(catalog: SmartCatalog): ArrivalProposal {
        val actable = catalog.devices.filter { !it.kind.readOnly && it.actions.isNotEmpty() }
        fun outside(d: SmartDevice) = outside(catalog, d)
        val byKind = actable.filter { it.kind == DeviceKind.GATE || it.kind == DeviceKind.GARAGE_DOOR }
        val doors = actable.filter { it.kind == DeviceKind.DOOR && (outside(it) || matches(it.name, NameWords)) }
        val byName = actable.filter {
            it.kind != DeviceKind.GATE && it.kind != DeviceKind.GARAGE_DOOR && it.kind != DeviceKind.DOOR &&
                it.kind != DeviceKind.LIGHT && it.kind != DeviceKind.SCENE && matches(it.name, NameWords)
        }
        val found = (byKind + doors + byName).distinctBy { it.id }
        if (found.isNotEmpty()) return ArrivalProposal(found, found.map { it.id }.toSet())
        // Nothing recognised: the likely ones first (things that open, in outside areas), nothing ticked
        val asked = actable
            .filter { it.kind != DeviceKind.LIGHT && it.kind != DeviceKind.SCENE }
            .sortedWith(compareBy<SmartDevice>({ !it.kind.opens }, { !outside(it) }, { it.kind != DeviceKind.LOCK }, { it.name.lowercase() }))
            .take(ASK_CAP)
        return ArrivalProposal(asked, emptySet())
    }

    /** Whole words, any case, accents ignored: "Box auto" yes, "Xbox" no. */
    internal fun matches(text: String, words: List<String>): Boolean {
        val plain = normalize(text)
        return words.any { w -> Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(normalize(w))}($|[^\\p{L}\\p{N}])").containsMatchIn(plain) }
    }

    private fun normalize(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
}
