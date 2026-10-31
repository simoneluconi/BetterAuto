package com.simoneluconi.betterauto.aap

import aap_protobuf.service.navigationstatus.message.NavigationDistanceOuterClass.NavigationDistance.DistanceUnits
import aap_protobuf.service.navigationstatus.message.NavigationManeuverOuterClass.NavigationManeuver.NavigationType
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent
import com.simoneluconi.betterauto.map.nav.ManeuverKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** What the car's cluster is told: distances as a driver reads them, maneuvers in the protocol's words. */
@Suppress("DEPRECATION")
class NavigationStatusChannelTest {
    private val italian = Locale.ITALY

    @Test
    fun metricDistances() {
        assertEquals(NavigationStatusChannel.Shown("130", 130_000, DistanceUnits.METERS), NavigationStatusChannel.distance(127.0, false, italian))
        assertEquals(NavigationStatusChannel.Shown("450", 450_000, DistanceUnits.METERS), NavigationStatusChannel.distance(462.0, false, italian))
        // Tenths of a km with the formats' decimal comma
        assertEquals(NavigationStatusChannel.Shown("1,2", 1200, DistanceUnits.KILOMETERS_P1), NavigationStatusChannel.distance(1234.0, false, italian))
        assertEquals(NavigationStatusChannel.Shown("23", 23_000, DistanceUnits.KILOMETERS), NavigationStatusChannel.distance(23_400.0, false, italian))
    }

    @Test
    fun imperialDistances() {
        assertEquals(DistanceUnits.FEET, NavigationStatusChannel.distance(100.0, true, Locale.US).unit)
        assertEquals(NavigationStatusChannel.Shown("1.2", 1200, DistanceUnits.MILES_P1), NavigationStatusChannel.distance(1931.0, true, Locale.US))
    }

    @Test
    fun maneuversInTheProtocolsWords() {
        assertEquals(NavigationNextTurnEvent.NextTurnEnum.TURN, NavigationStatusChannel.turnEvent(ManeuverKind.LEFT))
        assertEquals(NavigationNextTurnEvent.TurnSide.LEFT, NavigationStatusChannel.turnSide(ManeuverKind.LEFT))
        assertEquals(NavigationNextTurnEvent.TurnSide.UNSPECIFIED, NavigationStatusChannel.turnSide(ManeuverKind.STRAIGHT))
        assertEquals(NavigationNextTurnEvent.NextTurnEnum.DESTINATION, NavigationStatusChannel.turnEvent(ManeuverKind.ARRIVE))
        assertEquals(NavigationType.TURN_SLIGHT_RIGHT, NavigationStatusChannel.maneuverType(ManeuverKind.SLIGHT_RIGHT))
        assertEquals(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW, NavigationStatusChannel.maneuverType(ManeuverKind.ROUNDABOUT))
        // Where traffic keeps left (the steering wheel on the right), roundabouts turn clockwise
        assertEquals(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW, NavigationStatusChannel.maneuverType(ManeuverKind.ROUNDABOUT, clockwise = true))
        assertEquals(NavigationType.TURN_NORMAL_LEFT, NavigationStatusChannel.maneuverType(ManeuverKind.LEFT, clockwise = true))
    }

    @Test
    fun theDistanceThatCameTooSoonGoesOutLater() {
        val pacer = DistancePacer(1000)
        // The first one at once
        assertEquals(0L, pacer.delayFor("250", 10_000))
        pacer.sent("250", 10_000)
        // The same words: nothing to send
        assertEquals(-1L, pacer.delayFor("250", 10_500))
        // New words too soon: not dropped, sent once the interval is over
        assertEquals(600L, pacer.delayFor("240", 10_400))
        assertEquals(0L, pacer.delayFor("240", 11_000))
        pacer.sent("240", 11_000)
        // A new maneuver: its distance goes out even with the same words, as soon as the interval allows
        pacer.reset()
        assertEquals(500L, pacer.delayFor("240", 11_500))
        assertEquals(0L, pacer.delayFor("240", 12_000))
    }
}
