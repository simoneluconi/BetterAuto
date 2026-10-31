package com.simoneluconi.betterauto.voice

import com.simoneluconi.betterauto.settings.Section
import org.junit.Assert.assertEquals
import org.junit.Test

/** The many ways a driver asks to be driven somewhere, and what is not such a request. */
class NavigateCommandTest {
    private val parser = CommandParser(listOf("Mario Rossi"))

    private fun place(text: String) = (parser.parse(text) as? VoiceCommand.Navigate)?.place

    @Test
    fun italianWays() {
        assertEquals("roadhouse", place("Portami al Roadhouse"))
        assertEquals("piazza del campo siena", place("portami a Piazza del Campo, Siena"))
        assertEquals("ospedale di careggi", place("portami all'ospedale di Careggi"))
        assertEquals("cinema giometti", place("naviga verso il cinema Giometti"))
        assertEquals("firenze", place("naviga fino a Firenze"))
        assertEquals("stazione", place("andiamo alla stazione"))
        assertEquals("aeroporto", place("voglio andare all'aeroporto"))
        assertEquals("via roma 12 prato", place("indicazioni per via Roma 12 Prato"))
        assertEquals("esselunga", place("come arrivo all'Esselunga"))
        assertEquals("bologna", place("imposta la destinazione Bologna"))
        assertEquals("casa", place("portami a casa"))
        assertEquals("supermercato", place("vai al supermercato"))
        assertEquals("mamma", place("accompagnami da mamma"))
    }

    @Test
    fun englishWays() {
        assertEquals("station", place("take me to the station"))
        assertEquals("roadhouse", place("navigate to Roadhouse"))
        assertEquals("airport", place("drive to the airport"))
        assertEquals("piazza del campo", place("directions to Piazza del Campo"))
    }

    @Test
    fun stopsOnTheWay() {
        fun stop(text: String) = (parser.parse(text) as? VoiceCommand.AddStop)?.place
        assertEquals("benzinaio", stop("fermati al benzinaio"))
        assertEquals("autogrill", stop("facciamo tappa all'Autogrill"))
        assertEquals("mamma", stop("passa da mamma"))
        assertEquals("farmacia", stop("aggiungi una tappa alla farmacia"))
        assertEquals("gas station", stop("stop at the gas station"))
        assertEquals("bakery", stop("add a stop at the bakery"))
        assertEquals(VoiceCommand.SkipStop, parser.parse("salta la tappa"))
        assertEquals(VoiceCommand.EndNavigation, parser.parse("termina la navigazione"))
        assertEquals(VoiceCommand.EndNavigation, parser.parse("stop navigation"))
        // Still what they were before
        assertEquals(VoiceCommand.Pause, parser.parse("stop"))
        assertEquals(VoiceCommand.Next, parser.parse("salta"))
        assertEquals(VoiceCommand.Open(Section.MUSIC), parser.parse("passa alla musica"))
        assertEquals("via roma 12 prato", place("portami in via Roma 12 Prato"))
    }

    @Test
    fun theMusicIsNeverAPlace() {
        fun stop(text: String) = (parser.parse(text) as? VoiceCommand.AddStop)?.place
        // Ways to change the song that look like "go to" / "pass by"
        assertEquals(VoiceCommand.Next, parser.parse("vai alla prossima"))
        assertEquals(VoiceCommand.Next, parser.parse("vai al brano successivo"))
        assertEquals(VoiceCommand.Next, parser.parse("passa al prossimo brano"))
        assertEquals(VoiceCommand.Next, parser.parse("passa alla prossima canzone"))
        assertEquals(VoiceCommand.Previous, parser.parse("passa alla canzone precedente"))
        assertEquals(VoiceCommand.Previous, parser.parse("vai al brano precedente"))
        // Other music words after a verb of driving: no trip to some place called that
        assertEquals(null, place("portami alla prossima canzone"))
        assertEquals(null, place("vai alla traccia 3"))
        assertEquals(null, stop("fermati alla radio"))
        assertEquals(null, place("go to the next song"))
        // Stopping the music still pauses it
        assertEquals(VoiceCommand.Pause, parser.parse("stop music"))
        assertEquals(VoiceCommand.Pause, parser.parse("stop the music"))
        assertEquals(VoiceCommand.Pause, parser.parse("ferma la musica"))
    }

    @Test
    fun passingByIsAStopOnlyWithDaOrPer() {
        fun stop(text: String) = (parser.parse(text) as? VoiceCommand.AddStop)?.place
        assertEquals("mamma", stop("passa da mamma"))
        assertEquals("benzinaio", stop("passa dal benzinaio"))
        assertEquals("firenze", stop("passiamo per Firenze"))
        assertEquals(null, stop("passa al supermercato"))
        assertEquals(VoiceCommand.Open(Section.MUSIC), parser.parse("passa alla musica"))
    }

    @Test
    fun sectionsAndDirectionsAreNotPlaces() {
        // "vai a" a section still opens it
        assertEquals(VoiceCommand.Open(Section.MUSIC), parser.parse("vai alla musica"))
        assertEquals(VoiceCommand.Open(Section.SETTINGS), parser.parse("go to settings"))
        assertEquals(null, place("vai avanti"))
        // Calls stay calls
        assertEquals(VoiceCommand.Call(listOf("Mario Rossi"), null), parser.parse("chiama Mario Rossi"))
    }
}
