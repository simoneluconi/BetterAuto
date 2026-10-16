package com.simoneluconi.betterauto.voice

import com.simoneluconi.betterauto.settings.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {
    private val parser = CommandParser(listOf("Mario Rossi", "Mario Bianchi", "Giulia Bianchi", "Officina Ferrari", "Mamma"))

    @Test
    fun calls() {
        assertEquals(VoiceCommand.Call(listOf("Mario Rossi"), null), parser.parse("Chiama Mario Rossi"))
        assertEquals(VoiceCommand.Call(listOf("Giulia Bianchi"), "mobile"), parser.parse("chiama Giulia Bianchi sul cellulare"))
        assertEquals(VoiceCommand.Call(listOf("Mamma"), "home"), parser.parse("telefona a mamma casa"))
        assertEquals(VoiceCommand.Call(listOf("Mario Rossi"), "work"), parser.parse("call Mario Rossi at work"))
    }

    @Test
    fun slipsAreFoundButMarkedAsGuesses() {
        // Found, but not exact: the driver must confirm before anything is dialed
        assertEquals(VoiceCommand.Call(listOf("Mario Rossi"), null, exact = false), parser.parse("chiama Mario Rosi"))
        assertEquals(VoiceCommand.Call(listOf("Officina Ferrari"), null, exact = false), parser.parse("chiama officina ferari"))
    }

    @Test
    fun aWrongOrMissingSurnameIsNoMatch() {
        val contacts = CommandParser(listOf("Mauro Carboni"))
        // "Mario Rossi" is not "Mauro Carboni": the surname rules it out
        assertNull(contacts.parse("chiama Mario Rossi"))
        // "Mario" alone, close to "Mauro" but saying nothing of the surname, is not enough either
        assertNull(contacts.parse("chiama Mario"))
    }

    @Test
    fun ambiguousNamesAreAllOffered() {
        val call = parser.parse("chiama Mario") as VoiceCommand.Call
        assertEquals(setOf("Mario Rossi", "Mario Bianchi"), call.candidates.toSet())
    }

    @Test
    fun unknownNamesAreNotGuessed() {
        assertNull(parser.parse("chiama Federico Verdi"))
        assertNull(parser.parse("chiama"))
    }

    @Test
    fun media() {
        assertEquals(VoiceCommand.Pause, parser.parse("Metti in pausa la musica"))
        assertEquals(VoiceCommand.Next, parser.parse("prossima canzone"))
        assertEquals(VoiceCommand.Previous, parser.parse("indietro"))
        assertEquals(VoiceCommand.Resume, parser.parse("metti la musica"))
        assertEquals(VoiceCommand.Play("vasco rossi"), parser.parse("metti Vasco Rossi"))
        assertEquals(VoiceCommand.Play("coldplay"), parser.parse("play Coldplay"))
    }

    @Test
    fun sectionsAndModes() {
        assertEquals(VoiceCommand.Open(Section.VEHICLE), parser.parse("apri veicolo"))
        assertEquals(VoiceCommand.Open(Section.MUSIC), parser.parse("musica"))
        assertEquals(VoiceCommand.Quiet(true), parser.parse("modalità silenziosa"))
        assertEquals(VoiceCommand.ReadNotifications, parser.parse("leggi i messaggi"))
        assertEquals(VoiceCommand.Cancel, parser.parse("lascia stare"))
    }

    @Test
    fun missesAreExplained() {
        assertEquals(CommandParser.Miss.ContactNotFound("federico verdi"), parser.miss("chiama Federico Verdi"))
        assertEquals(CommandParser.Miss.UnknownPlace("garage"), parser.miss("apri garage"))
        assertNull(parser.miss("che tempo fa"))
    }

    @Test
    fun otherTextIsNoCommand() {
        assertNull(parser.parse("che tempo fa domani"))
        assertNull(parser.parse(""))
    }
}
