package com.simoneluconi.betterauto.smarthome.homeassistant

import com.simoneluconi.betterauto.smarthome.DeviceAction
import com.simoneluconi.betterauto.smarthome.DeviceCondition
import com.simoneluconi.betterauto.smarthome.DeviceKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaMappingTest {
    private fun attrs(json: String) = JSONObject(json)

    @Test
    fun coversByDeviceClass() {
        assertEquals(DeviceKind.GATE, HaMapping.kind("cover.front", attrs("""{"device_class":"gate"}""")))
        assertEquals(DeviceKind.GARAGE_DOOR, HaMapping.kind("cover.box", attrs("""{"device_class":"garage"}""")))
        assertEquals(DeviceKind.DOOR, HaMapping.kind("cover.d", attrs("""{"device_class":"door"}""")))
        assertEquals(DeviceKind.COVER, HaMapping.kind("cover.blind", attrs("""{"device_class":"shutter"}""")))
        assertEquals(DeviceKind.COVER, HaMapping.kind("cover.x", null))
        assertEquals(DeviceKind.SWITCH, HaMapping.kind("input_boolean.guest", null))
        assertEquals(DeviceKind.BUTTON, HaMapping.kind("input_button.ring", null))
        assertNull(HaMapping.kind("person.me", null))
        assertNull(HaMapping.kind("automation.x", null))
    }

    @Test
    fun actionsFollowTheFeatureBits() {
        // OPEN 1 | CLOSE 2 (no STOP)
        assertEquals(listOf(DeviceAction.OPEN, DeviceAction.CLOSE), HaMapping.actions("cover.g", DeviceKind.GATE, attrs("""{"supported_features":3}""")))
        assertEquals(listOf(DeviceAction.OPEN, DeviceAction.STOP, DeviceAction.CLOSE), HaMapping.actions("cover.g", DeviceKind.GATE, attrs("{}")))
        assertEquals(listOf(DeviceAction.LOCK, DeviceAction.UNLOCK, DeviceAction.OPEN), HaMapping.actions("lock.f", DeviceKind.LOCK, attrs("""{"supported_features":1}""")))
        assertEquals(listOf(DeviceAction.RUN), HaMapping.actions("input_button.b", DeviceKind.BUTTON, null))
        assertTrue(HaMapping.actions("sensor.t", DeviceKind.SENSOR, null).isEmpty())
    }

    @Test
    fun actionsBecomeServices() {
        assertEquals(HaServiceCall("cover", "open_cover", "cover.g"), HaMapping.serviceCall("cover.g", DeviceAction.OPEN))
        assertEquals(HaServiceCall("cover", "stop_cover", "cover.g"), HaMapping.serviceCall("cover.g", DeviceAction.STOP))
        assertEquals(HaServiceCall("lock", "unlock", "lock.f"), HaMapping.serviceCall("lock.f", DeviceAction.UNLOCK))
        assertEquals(HaServiceCall("light", "toggle", "light.p"), HaMapping.serviceCall("light.p", DeviceAction.TOGGLE))
        assertEquals(HaServiceCall("button", "press", "button.b"), HaMapping.serviceCall("button.b", DeviceAction.RUN))
        assertEquals(HaServiceCall("scene", "turn_on", "scene.s"), HaMapping.serviceCall("scene.s", DeviceAction.RUN))
        assertEquals(HaServiceCall("script", "turn_on", "script.s"), HaMapping.serviceCall("script.s", DeviceAction.RUN))
        assertNull(HaMapping.serviceCall("sensor.t", DeviceAction.RUN))
        assertNull(HaMapping.serviceCall("light.p", DeviceAction.LOCK))
    }

    @Test
    fun statesBecomeConditions() {
        fun s(id: String, state: String, a: String = "{}") = HaMapping.state(id, HaEntityState(state, attrs(a)))
        assertEquals(DeviceCondition.OPENING, s("cover.g", "opening").condition)
        assertEquals(40, s("cover.g", "open", """{"current_position":40}""").position)
        assertEquals(DeviceCondition.LOCKED, s("lock.f", "locked").condition)
        assertEquals(DeviceCondition.JAMMED, s("lock.f", "jammed").condition)
        assertEquals(DeviceCondition.ON, s("switch.p", "on").condition)
        assertEquals(DeviceCondition.UNAVAILABLE, s("light.p", "unavailable").condition)
        assertEquals(DeviceCondition.OPEN, s("binary_sensor.w", "on", """{"device_class":"window"}""").condition)
        assertEquals(DeviceCondition.UNLOCKED, s("binary_sensor.l", "on", """{"device_class":"lock"}""").condition)
        assertEquals(DeviceCondition.OFF, s("binary_sensor.m", "off", """{"device_class":"motion"}""").condition)
        assertEquals("21.5", s("sensor.t", "21.5").value)
        assertEquals(DeviceCondition.UNKNOWN, s("scene.s", "2026-10-21T10:00:00").condition)
    }

    @Test
    fun catalogLeavesOutHiddenDisabledAndConfigEntities() {
        val states = JSONArray(
            """[
            {"entity_id":"cover.gate","state":"closed","attributes":{"friendly_name":"Cancello","device_class":"gate","supported_features":11}},
            {"entity_id":"light.hidden","state":"off","attributes":{"friendly_name":"Hidden"}},
            {"entity_id":"switch.disabled","state":"off","attributes":{}},
            {"entity_id":"switch.config","state":"off","attributes":{}},
            {"entity_id":"light.kitchen","state":"on","attributes":{"friendly_name":"Cucina"}},
            {"entity_id":"sensor.battery","state":"80","attributes":{"device_class":"battery","unit_of_measurement":"%"}},
            {"entity_id":"sensor.outside","state":"12","attributes":{"device_class":"temperature","unit_of_measurement":"°C","friendly_name":"Fuori"}},
            {"entity_id":"binary_sensor.motion","state":"off","attributes":{"device_class":"motion"}},
            {"entity_id":"script.yaml_only","state":"off","attributes":{"friendly_name":"Script"}},
            {"entity_id":"zone.home","state":"0","attributes":{"latitude":45.1,"longitude":9.2,"radius":150,"friendly_name":"Casa"}},
            {"entity_id":"zone.work","state":"0","attributes":{"latitude":45.2,"longitude":9.3,"radius":80,"friendly_name":"Work"}},
            {"entity_id":"person.me","state":"home","attributes":{}}
            ]"""
        )
        val entities = JSONArray(
            """[
            {"entity_id":"cover.gate","device_id":"dev1","area_id":null,"hidden_by":null,"disabled_by":null,"entity_category":null},
            {"entity_id":"light.hidden","hidden_by":"user","disabled_by":null,"entity_category":null},
            {"entity_id":"switch.disabled","hidden_by":null,"disabled_by":"integration","entity_category":null},
            {"entity_id":"switch.config","hidden_by":null,"disabled_by":null,"entity_category":"config"},
            {"entity_id":"light.kitchen","area_id":"kitchen","hidden_by":null,"disabled_by":null,"entity_category":null}
            ]"""
        )
        val devices = JSONArray("""[{"id":"dev1","area_id":"outside"}]""")
        val areas = JSONArray("""[{"area_id":"outside","name":"Esterno"},{"area_id":"kitchen","name":"Cucina"}]""")
        val catalog = HaMapping.catalog(areas, devices, entities, states, exposed = setOf("cover.gate"), config = null)

        assertEquals(listOf("cover.gate", "light.kitchen", "sensor.outside", "script.yaml_only"), catalog.devices.map { it.id })
        val gate = catalog.device("cover.gate")!!
        assertEquals(DeviceKind.GATE, gate.kind)
        assertEquals("outside", gate.areaId)
        assertTrue(gate.suggested)
        assertEquals("Cancello", gate.name)
        assertEquals(listOf(DeviceAction.OPEN, DeviceAction.STOP, DeviceAction.CLOSE), gate.actions)
        assertFalse(catalog.device("light.kitchen")!!.suggested)
        assertEquals("°C", catalog.device("sensor.outside")!!.unit)
        assertEquals(2, catalog.places.size)
        assertEquals("zone.home", catalog.home!!.id)
        assertEquals(150.0, catalog.home!!.radiusM, 0.0)
    }

    @Test
    fun withoutAHomeZoneTheConfiguredLocationIsUsed() {
        val catalog = HaMapping.catalog(null, null, null, JSONArray("[]"), null, JSONObject("""{"latitude":45.0,"longitude":9.0,"location_name":"Casa"}"""))
        assertEquals("Casa", catalog.home!!.name)
        assertEquals(100.0, catalog.home!!.radiusM, 0.0)
    }

    @Test
    fun exposeListGivesTheExposedEntities() {
        val result = JSONObject("""{"exposed_entities":{"light.a":{"conversation":true},"cover.b":{"cloud.alexa":true,"conversation":false},"switch.c":{}}}""")
        assertEquals(setOf("light.a", "cover.b"), HaMapping.exposed(result))
        assertNull(HaMapping.exposed(null))
    }

    @Test
    fun entitiesAreNamedWithoutTheirDevice() {
        val friendly = JSONObject().put("friendly_name", "Esp-Garage-V2 Porta Garage")
        val modern = JSONObject().put("has_entity_name", true).put("original_name", "Porta Garage")
        assertEquals("Porta Garage", HaMapping.shortName("cover.porta", friendly, modern, "Esp-Garage-V2"))
        // Older integrations: the device name is cut from the front of the friendly name
        assertEquals("Porta Garage", HaMapping.shortName("cover.porta", friendly, JSONObject(), "Esp-Garage-V2"))
        // A name the user gave the entity stays as it is
        assertEquals("Garage", HaMapping.shortName("cover.porta", friendly, JSONObject().put("name", "Garage"), "Esp-Garage-V2"))
        // The device's main entity has no name of its own: the device's
        val main = JSONObject().put("has_entity_name", true)
        assertEquals("Esp-Garage-V2", HaMapping.shortName("switch.esp", JSONObject().put("friendly_name", "Esp-Garage-V2"), main, "Esp-Garage-V2"))
    }
}
