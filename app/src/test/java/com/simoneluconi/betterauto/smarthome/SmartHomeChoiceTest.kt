package com.simoneluconi.betterauto.smarthome

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generic layer on a pretend provider: pre-selection, arrival candidates, persistence, registry. */
class SmartHomeChoiceTest {
    private fun d(id: String, name: String, kind: DeviceKind, area: String? = null, suggested: Boolean = false) = SmartDevice(
        "fake", id, name, kind, area,
        actions = if (kind.readOnly) emptyList() else listOf(DeviceAction.RUN),
        suggested = suggested,
    )

    private val areas = listOf(SmartArea("out", "Esterno"), SmartArea("kitchen", "Cucina"), SmartArea("garage", "Garage"))

    @Test
    fun onlyExposedDevicesUsefulWhileDrivingArePreselected() {
        val catalog = SmartCatalog(
            areas,
            listOf(
                d("a", "Cancello", DeviceKind.GATE, suggested = true),
                d("b", "Luce cucina", DeviceKind.LIGHT, "kitchen", suggested = true),
                d("c", "Temperatura", DeviceKind.SENSOR, suggested = true),
                d("e", "Luce giardino", DeviceKind.LIGHT, "out", suggested = true),
                d("f", "Buonanotte", DeviceKind.SCENE, suggested = true),
                d("h", "Relè cancelletto", DeviceKind.SWITCH, suggested = true),
                d("i", "Serratura", DeviceKind.LOCK),
            ),
        )
        assertEquals(setOf("a", "e", "f", "h"), Preselection.pick(catalog))
    }

    @Test
    fun withNothingExposedTheSameChoiceAmongAllCapped() {
        val devices = (1..40).map { d("s$it", "Scene $it", DeviceKind.SCENE) } +
            d("g", "Gate", DeviceKind.GATE) + d("t", "Temp", DeviceKind.SENSOR) + d("l", "Lamp", DeviceKind.LIGHT, "kitchen")
        val picked = Preselection.pick(SmartCatalog(areas, devices))
        assertEquals(Preselection.FALLBACK_CAP, picked.size)
        assertTrue("g" in picked)
        assertFalse("t" in picked)
        assertFalse("l" in picked)
    }

    @Test
    fun arrivalPicksGatesAndGaragesByKind() {
        val catalog = SmartCatalog(
            areas,
            listOf(d("g", "Front", DeviceKind.GATE), d("gd", "Box", DeviceKind.GARAGE_DOOR), d("l", "Lamp", DeviceKind.LIGHT)),
        )
        val p = ArrivalCandidates.propose(catalog)
        assertEquals(setOf("g", "gd"), p.ticked)
        assertTrue(p.recognised)
    }

    @Test
    fun arrivalTakesDoorsOnlyOutside() {
        val catalog = SmartCatalog(
            areas,
            listOf(d("in", "Porta camera", DeviceKind.DOOR, "kitchen"), d("out", "Portoncino", DeviceKind.DOOR, "out")),
        )
        assertEquals(setOf("out"), ArrivalCandidates.propose(catalog).ticked)
    }

    @Test
    fun arrivalFindsRelaysByNameInItalianAndEnglish() {
        val catalog = SmartCatalog(
            areas,
            listOf(
                d("s1", "Relè Cancello", DeviceKind.SWITCH),
                d("s2", "Basculante box", DeviceKind.BUTTON),
                d("s3", "Driveway gate", DeviceKind.SCRIPT),
                d("s4", "Xbox", DeviceKind.SWITCH),
                d("s5", "Luce garage", DeviceKind.LIGHT, "garage"),
            ),
        )
        assertEquals(setOf("s1", "s2", "s3"), ArrivalCandidates.propose(catalog).ticked)
    }

    @Test
    fun withNothingRecognisedItAsksWithThingsThatOpenFirst() {
        val catalog = SmartCatalog(
            areas,
            listOf(d("sw", "Pompa", DeviceKind.SWITCH), d("cv", "Tapparella", DeviceKind.COVER, "kitchen"), d("t", "Temp", DeviceKind.SENSOR)),
        )
        val p = ArrivalCandidates.propose(catalog)
        assertFalse(p.recognised)
        assertTrue(p.ticked.isEmpty())
        assertEquals(listOf("cv", "sw"), p.devices.map { it.id })
    }

    @Test
    fun wordsMatchWholeAndWithoutAccents() {
        assertTrue(ArrivalCandidates.matches("Apri CANCELLO", listOf("cancello")))
        assertTrue(ArrivalCandidates.matches("Autorimessa", listOf("autorimessa")))
        assertFalse(ArrivalCandidates.matches("Xbox", listOf("box")))
        assertTrue(ArrivalCandidates.matches("Relè", listOf("rele")))
    }

    @Test
    fun catalogAndChoicesSurviveSaving() {
        val catalog = SmartCatalog(
            areas,
            listOf(d("a", "Gate", DeviceKind.GATE, "out", suggested = true).copy(unit = "x")),
            listOf(SmartPlace("zone.home", "Home", 45.1, 9.2, 120.0, home = true)),
        )
        assertEquals(catalog, SmartCatalog.fromJson(catalog.toJson()))
        assertEquals(null, SmartCatalog.fromJson("{not json"))
        val choice = ProviderChoice(
            setOf("a", "b"),
            mapOf("zone.home" to PlaceArrival(setOf("a"), 500, on = true), "zone.office" to PlaceArrival(setOf("b"), on = false)),
        )
        assertEquals(choice, SmartHomeSettings.decode(SmartHomeSettings.encode(choice)))
        assertEquals(ProviderChoice(), SmartHomeSettings.decode(SmartHomeSettings.encode(ProviderChoice())))
    }

    @Test
    fun choicesSavedForOnePlaceComeBackAsThatPlace() {
        // Before places had their own buttons: one set, for the home unless a place was named
        val home = SmartHomeSettings.decode("""{"selected":["a"],"arrival":["g"],"arrivalConfirmed":true,"offset":500}""")
        assertEquals(mapOf(ProviderChoice.HOME_PLACE to PlaceArrival(setOf("g"), 500, on = true)), home?.places)
        val named = SmartHomeSettings.decode("""{"arrival":["g"],"arrivalConfirmed":false,"place":"zone.work"}""")
        assertEquals(mapOf("zone.work" to PlaceArrival(setOf("g"), ProviderChoice.DEFAULT_OFFSET_M, on = false)), named?.places)
        // Nothing proposed yet: no place
        assertTrue(SmartHomeSettings.decode("""{"selected":["a"]}""")!!.places.isEmpty())
    }

    @Test
    fun aPlaceSwitchedOffKeepsItsDevicesButShowsNothing() {
        val home = SmartPlace("zone.home", "Casa", 45.0, 9.0, 100.0, home = true)
        val off = ProviderChoice(places = mapOf("zone.home" to PlaceArrival(setOf("g"), 1000, on = false)))
        assertTrue(off.arrivalDevices.isEmpty())
        assertEquals(setOf("g"), off.arrivalAt(home)?.devices)
        val on = off.copy(places = off.places.mapValues { (_, p) -> p.copy(on = true) })
        assertEquals(setOf("g"), on.arrivalDevices)
        assertEquals(1000, on.arrivalAt(home)?.offsetM)
        // Saved under the "home" stand-in: found for the home place only
        val legacy = ProviderChoice(places = mapOf(ProviderChoice.HOME_PLACE to PlaceArrival(setOf("g"), on = true)))
        assertEquals(setOf("g"), legacy.arrivalAt(home)?.devices)
        assertEquals(null, legacy.arrivalAt(home.copy(id = "zone.work", home = false)))
    }

    @Test
    fun aRegisteredProviderIsListedAsLinkedAndItsDevicesOrdered(): Unit = runBlocking {
        val fake = FakeProvider()
        SmartHome.register(fake)
        try {
            val linked = withTimeout(5000) { SmartHome.linked.first { list -> list.any { it.id == "fake" } } }
            assertNotNull(linked.firstOrNull { it.id == "fake" })
            val chosen = SmartHome.chosenDevices(fake.catalog.value, ProviderChoice(selected = setOf("l", "g")))
            assertEquals(listOf("g", "l"), chosen.map { it.id })
            assertTrue(SmartHome.chosenDevices(fake.catalog.value, ProviderChoice()).isEmpty())
            fake.link.value = LinkState.NOT_LINKED
            withTimeout(5000) { SmartHome.linked.first { list -> list.none { it.id == "fake" } } }
        } finally {
            SmartHome.unregister("fake")
        }
    }

    /** Implements the provider interface with nothing but the canonical model. */
    private inner class FakeProvider : SmartHomeProvider {
        override val id = "fake"
        override val name = "Fake"
        override fun icon(context: android.content.Context): ImageBitmap? = null
        override val link = MutableStateFlow(LinkState.ONLINE)
        override val homeName: StateFlow<String?> = MutableStateFlow(null)
        override val catalog: StateFlow<SmartCatalog?> = MutableStateFlow(
            SmartCatalog(devices = listOf(d("l", "Lamp", DeviceKind.LIGHT), d("g", "Gate", DeviceKind.GATE)))
        )
        override val states: StateFlow<Map<String, DeviceState>> = MutableStateFlow(emptyMap())
        var followed: Set<String> = emptySet()
        override fun follow(deviceIds: Set<String>) {
            followed = deviceIds
        }
        override suspend fun refreshCatalog() = true
        override suspend fun perform(deviceId: String, action: DeviceAction) = true
        override fun unlink() = Unit

        @Composable
        override fun LinkCard() = Unit
    }
}
