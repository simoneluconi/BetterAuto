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
    fun callToleratesSlips() {
        assertEquals(VoiceCommand.Call(listOf("Mario Rossi"), null), parser.parse("chiama Mario Rosi"))
        assertEquals(VoiceCommand.Call(listOf("Officina Ferrari"), null), parser.parse("chiama officina ferari"))
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
    fun otherTextIsNoCommand() {
        assertNull(parser.parse("che tempo fa domani"))
        assertNull(parser.parse(""))
    }
}
