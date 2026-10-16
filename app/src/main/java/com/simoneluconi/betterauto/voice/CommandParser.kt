package com.simoneluconi.betterauto.voice

import com.simoneluconi.betterauto.settings.Section
import java.text.Normalizer

/** What a spoken phrase asks for. */
sealed interface VoiceCommand {
    /**
     * Call one of [candidates]; [numberType] is "mobile", "home", "work" or null for the main number.
     * [exact] when every spoken word is a word of the name: otherwise it's a guess to confirm.
     */
    data class Call(val candidates: List<String>, val numberType: String?, val exact: Boolean = true) : VoiceCommand
    data class Play(val query: String) : VoiceCommand
    data object Pause : VoiceCommand
    data object Resume : VoiceCommand
    data object Next : VoiceCommand
    data object Previous : VoiceCommand
    data class Open(val section: Section) : VoiceCommand
    data class Quiet(val on: Boolean) : VoiceCommand
    data object ReadNotifications : VoiceCommand
    data object Cancel : VoiceCommand
}

/**
 * Turns recognized text into a command. The words are fixed per language (Italian and English), the
 * names come from the phone (contacts): unknown text gives no command instead of a guess. Pure logic,
 * so it can be tested; tolerant to recognition slips ("Mario Rosi" finds "Mario Rossi").
 */
class CommandParser(private val contacts: List<String>) {
    private val index = contacts.map { it to normalize(it) }

    /** Why [text] gave no command, when that can be told better than "not understood". */
    sealed interface Miss {
        data class ContactNotFound(val name: String) : Miss
        data class UnknownPlace(val name: String) : Miss
    }

    fun miss(text: String): Miss? {
        val t = normalize(text)
        startAfter(t, CallWords)?.takeIf { it.isNotBlank() }?.let { return Miss.ContactNotFound(it) }
        startAfter(t, OpenWords)?.takeIf { it.isNotBlank() }?.let { return Miss.UnknownPlace(it) }
        return null
    }

    /** The command in [text], or null if it isn't one (yet: a partial result may still grow). */
    fun parse(text: String): VoiceCommand? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        if (t in Cancel) return VoiceCommand.Cancel

        startAfter(t, CallWords)?.let { rest -> return call(rest) }
        if (t in PauseWords) return VoiceCommand.Pause
        if (t in ResumeWords) return VoiceCommand.Resume
        if (t in NextWords) return VoiceCommand.Next
        if (t in PreviousWords) return VoiceCommand.Previous
        if (t in ReadWords) return VoiceCommand.ReadNotifications
        if (t in QuietOn) return VoiceCommand.Quiet(true)
        if (t in QuietOff) return VoiceCommand.Quiet(false)
        startAfter(t, OpenWords)?.let { rest -> sectionOf(rest)?.let { return VoiceCommand.Open(it) } }
        sectionOf(t)?.let { return VoiceCommand.Open(it) }
        startAfter(t, PlayWords)?.takeIf { it.isNotBlank() }?.let { query ->
            // "metti la musica" / "play music" just resumes
            if (query in GenericMusic) return VoiceCommand.Resume
            return VoiceCommand.Play(query.removePrefix("la ").removePrefix("il ").removePrefix("i "))
        }
        return null
    }

    private fun call(rest: String): VoiceCommand? {
        if (rest.isBlank()) return null
        var name = rest
        var type: String? = null
        for ((suffixes, kind) in NumberTypes) {
            for (suffix in suffixes) {
                if (name.endsWith(" $suffix")) {
                    name = name.removeSuffix(" $suffix").trim()
                    type = kind
                }
            }
        }
        // "sul", "al", "a" left between the name and the number type
        name = name.split(' ').dropLastWhile { it in Fillers }.dropWhile { it in Fillers }.joinToString(" ")
        val matches = matchContacts(name)
        if (matches.isEmpty()) return null
        val said = name.split(' ').filter { it.isNotBlank() }
        val exact = matches.size == 1 && said.all { word -> normalize(matches.first()).split(' ').contains(word) }
        return VoiceCommand.Call(matches, type, exact)
    }

    /** Contacts matching [spoken], best first: exact words, then close spellings. */
    fun matchContacts(spoken: String): List<String> {
        if (spoken.isBlank()) return emptyList()
        val scored = index.map { (name, norm) -> name to score(spoken, norm) }.filter { it.second >= MIN_SCORE }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        // Clear winner, or every name about as good (the driver will choose)
        return scored.filter { it.second >= best - AMBIGUITY }.sortedByDescending { it.second }.map { it.first }.distinct().take(5)
    }

    private fun score(spoken: String, name: String): Double {
        val said = spoken.split(' ').filter { it.isNotBlank() }
        val words = name.split(' ').filter { it.isNotBlank() }
        if (said.isEmpty() || words.isEmpty()) return 0.0
        // Each spoken word against its best word of the name; every spoken word must fit, not just on average
        val perWord = said.map { s -> words.maxOf { w -> similarity(s, w) } }
        if (perWord.min() < MIN_WORD) return 0.0
        val coverage = said.size.toDouble() / words.size
        return perWord.average() * (0.85 + 0.15 * coverage.coerceAtMost(1.0))
    }

    companion object {
        private const val MIN_SCORE = 0.78
        private const val MIN_WORD = 0.75
        private const val AMBIGUITY = 0.04

        fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        /** 1 for equal words, falling with the edit distance. */
        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            val d = levenshtein(a, b)
            return 1.0 - d.toDouble() / maxOf(a.length, b.length)
        }

        private fun levenshtein(a: String, b: String): Int {
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                cur.copyInto(prev)
            }
            return prev[b.length]
        }

        private fun startAfter(text: String, prefixes: List<String>): String? =
            prefixes.firstOrNull { text == it || text.startsWith("$it ") }?.let { text.removePrefix(it).trim() }

        private fun sectionOf(text: String): Section? = Sections.entries.firstOrNull { (words, _) -> text in words }?.value

        private val CallWords = listOf("chiama", "telefona a", "telefona", "fai una chiamata a", "call", "phone", "dial")
        private val PlayWords = listOf("metti", "riproduci", "ascolta", "fammi sentire", "play", "put on", "listen to")
        private val OpenWords = listOf("apri", "vai a", "vai su", "vai alla", "vai al", "mostra", "open", "go to", "show")
        private val Fillers = setOf("sul", "al", "a", "il", "su", "on", "the", "at", "di")
        private val NumberTypes = listOf(
            listOf("cellulare", "cell", "mobile", "telefonino") to "mobile",
            listOf("casa", "home", "fisso") to "home",
            listOf("lavoro", "ufficio", "work", "office") to "work",
        )
        private val PauseWords = setOf("pausa", "metti in pausa", "metti in pausa la musica", "ferma", "ferma la musica", "stop", "pause", "stop the music")
        private val ResumeWords = setOf("riprendi", "continua", "play", "riproduci", "resume", "metti la musica", "fai partire la musica")
        private val NextWords = setOf("avanti", "prossima", "prossimo", "prossima canzone", "brano successivo", "salta", "next", "skip", "next song")
        private val PreviousWords = setOf("indietro", "precedente", "canzone precedente", "brano precedente", "previous", "back", "previous song")
        private val ReadWords = setOf("leggi i messaggi", "leggi messaggi", "leggi le notifiche", "leggi notifiche", "read messages", "read my messages", "read notifications")
        private val QuietOn = setOf("modalita silenziosa", "silenzia le notifiche", "silenzia notifiche", "non disturbare", "quiet mode", "do not disturb")
        private val QuietOff = setOf("disattiva modalita silenziosa", "disattiva il silenzioso", "riattiva le notifiche", "quiet mode off")
        private val Cancel = setOf("annulla", "niente", "lascia stare", "lascia perdere", "cancel", "nothing", "never mind")
        private val GenericMusic = setOf("musica", "la musica", "music", "some music")
        private val Sections = mapOf(
            setOf("home", "la home", "schermata principale") to Section.HOME,
            setOf("musica", "la musica", "music") to Section.MUSIC,
            setOf("mappa", "la mappa", "mappe", "map", "maps") to Section.MAP,
            setOf("telefono", "il telefono", "rubrica", "phone", "contacts") to Section.PHONE,
            setOf("veicolo", "il veicolo", "auto", "macchina", "la macchina", "vehicle", "car") to Section.VEHICLE,
            setOf("notifiche", "le notifiche", "notifications") to Section.NOTIFICATIONS,
            setOf("impostazioni", "le impostazioni", "settings") to Section.SETTINGS,
        )
    }
}
