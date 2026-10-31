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
    /** Drive to [place]: an address, a town, a shop or a restaurant ("roadhouse"), "casa". */
    data class Navigate(val place: String) : VoiceCommand
    /** A stop on the way to the destination ("fermati al benzinaio"). */
    data class AddStop(val place: String) : VoiceCommand
    data object SkipStop : VoiceCommand
    data object EndNavigation : VoiceCommand
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
        if (t in SkipStopWords) return VoiceCommand.SkipStop
        if (t in EndNavigationWords) return VoiceCommand.EndNavigation
        startAfter(t, OpenWords)?.let { rest -> sectionOf(rest)?.let { return VoiceCommand.Open(it) } }
        sectionOf(t)?.let { return VoiceCommand.Open(it) }
        // "vai a" a section was handled above: anything else after it is a place to drive to
        place(t, AddStopRegex)?.let { return sectionOf(it)?.let { s -> VoiceCommand.Open(s) } ?: VoiceCommand.AddStop(it) }
        place(t, NavigateRegex)?.let { return VoiceCommand.Navigate(it) }
        startAfter(t, PlayWords)?.takeIf { it.isNotBlank() }?.let { query ->
            // "metti la musica" / "play music" just resumes
            if (query in GenericMusic) return VoiceCommand.Resume
            return VoiceCommand.Play(query.removePrefix("la ").removePrefix("il ").removePrefix("i "))
        }
        return null
    }

    /**
     * "Portami al roadhouse", "naviga verso piazza del campo", "voglio andare all'ospedale", "take me to the
     * station", "fermati al benzinaio": the place after the words that ask to drive (or stop) and the preposition
     * that follows them.
     */
    private fun place(t: String, regex: Regex): String? {
        val m = regex.matchEntire(t) ?: return null
        var place = m.groupValues[2].trim()
        // "the" / "il" left before the place in English or after a bare verb
        place = place.split(' ').dropWhile { it in Articles }.joinToString(" ")
        // "vai avanti", "go back": a direction, not a place; "vai alla prossima canzone": the music, not a place
        if (place in NotPlaces || place.split(' ').any { it in MusicWords }) return null
        return place.takeIf { it.isNotBlank() }
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
                .replace(Marks, "")
                .replace(NotWord, " ")
                .replace(Spaces, " ")
                .trim()

        // Compiled once: every contact name and every partial result goes through normalize()
        private val Marks = Regex("\\p{M}")
        private val NotWord = Regex("[^a-z0-9 ]")
        private val Spaces = Regex("\\s+")

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
        private val OpenWords = listOf("apri", "vai a", "vai su", "vai alla", "vai al", "passa a", "passa alla", "passa al", "mostra", "open", "go to", "show")
        private val Fillers = setOf("sul", "al", "a", "il", "su", "on", "the", "at", "di")
        private val NumberTypes = listOf(
            listOf("cellulare", "cell", "mobile", "telefonino") to "mobile",
            listOf("casa", "home", "fisso") to "home",
            listOf("lavoro", "ufficio", "work", "office") to "work",
        )
        private val PauseWords = setOf(
            "pausa", "metti in pausa", "metti in pausa la musica", "ferma", "ferma la musica", "stoppa", "stoppa la musica",
            "stop", "pause", "stop the music", "stop music", "pause the music", "pause music",
        )
        private val ResumeWords = setOf("riprendi", "continua", "play", "riproduci", "resume", "metti la musica", "fai partire la musica")
        private val NextWords = setOf(
            "avanti", "prossima", "prossimo", "prossima canzone", "prossimo brano", "brano successivo", "canzone successiva",
            "salta", "passa alla prossima", "passa alla prossima canzone", "passa al prossimo brano", "passa al brano successivo",
            "passa alla canzone successiva", "vai alla prossima", "vai al prossimo", "vai alla prossima canzone", "vai al prossimo brano",
            "vai al brano successivo", "vai alla canzone successiva", "next", "skip", "next song", "next track", "skip this song",
        )
        private val PreviousWords = setOf(
            "indietro", "precedente", "canzone precedente", "brano precedente", "torna indietro", "passa alla precedente",
            "passa alla canzone precedente", "passa al brano precedente", "vai alla precedente", "vai alla canzone precedente",
            "vai al brano precedente", "torna alla canzone precedente", "previous", "back", "previous song", "previous track",
        )
        private val ReadWords = setOf("leggi i messaggi", "leggi messaggi", "leggi le notifiche", "leggi notifiche", "read messages", "read my messages", "read notifications")
        private val QuietOn = setOf("modalita silenziosa", "silenzia le notifiche", "silenzia notifiche", "non disturbare", "quiet mode", "do not disturb")
        private val QuietOff = setOf("disattiva modalita silenziosa", "disattiva il silenzioso", "riattiva le notifiche", "quiet mode off")
        private val Cancel = setOf("annulla", "niente", "lascia stare", "lascia perdere", "cancel", "nothing", "never mind")
        private val GenericMusic = setOf("musica", "la musica", "music", "some music")

        /** The ways to ask to be driven somewhere (normalized: no accents, apostrophes as spaces). */
        private val NavigateVerbs = listOf(
            "portami", "accompagnami", "guidami", "conducimi", "naviga", "navigare", "avvia la navigazione", "avvia navigazione",
            "andiamo", "vai", "voglio andare", "devo andare", "dobbiamo andare", "vorrei andare", "fammi andare",
            "indicazioni stradali", "indicazioni", "percorso", "strada", "come arrivo", "come si arriva", "come ci arrivo",
            "imposta la destinazione", "imposta destinazione", "destinazione", "navigazione",
            "take me", "navigate", "drive me", "drive", "directions", "go", "get me", "route me", "route", "lets go", "let s go",
            "set destination", "set the destination", "how do i get",
        )

        /** What may come between the verb and the place ("al", "all'", "fino alla", "verso", "to"...). */
        private val NavigatePrepositions = listOf(
            "fino a", "fino al", "fino alla", "fino all", "fino allo", "verso il", "verso la", "verso",
            "a", "ad", "al", "allo", "alla", "all", "ai", "agli", "alle", "in", "nel", "nello", "nella", "nell",
            "da", "dal", "dallo", "dalla", "dall", "dai", "dagli", "dalle", "per", "su", "sul", "sulla", "presso",
            "to the", "to", "towards", "for", "at",
        )

        /** The ways to ask for a stop on the way. */
        private val AddStopVerbs = listOf(
            "fermati", "fermiamoci", "facciamo tappa", "fai tappa", "fai una tappa", "facciamo una tappa", "fai una sosta",
            "facciamo una sosta", "aggiungi una tappa", "aggiungi tappa", "aggiungi una sosta", "aggiungi sosta",
            "tappa", "sosta",
            // Not "via": in Italian it is the street ("via Roma")
            "stop", "stop by", "add a stop", "add stop", "make a stop",
        ) + listOf("passa", "passiamo").flatMap { verb ->
            // "Passa" alone is also "passa al prossimo brano": a stop only with "da" or "per"
            listOf("da", "dal", "dallo", "dalla", "dall", "dai", "dagli", "dalle", "per").map { "$verb $it" }
        }

        /** Words of the music: what they name is a song or a command, never a place to drive to. */
        private val MusicWords = setOf(
            "brano", "brani", "canzone", "canzoni", "traccia", "tracce", "prossima", "prossimo", "precedente",
            "successiva", "successivo", "musica", "radio", "volume", "playlist", "podcast", "album",
            "song", "songs", "track", "tracks", "music", "next", "previous",
        )

        private val NavigateRegex = verbsThenPlace(NavigateVerbs)
        private val AddStopRegex = verbsThenPlace(AddStopVerbs)

        private fun verbsThenPlace(verbs: List<String>) = Regex(
            "^(" + verbs.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")" +
                "(?: (?:" + NavigatePrepositions.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + "))? (.+)$",
        )
        private val SkipStopWords = setOf(
            "salta la tappa", "salta tappa", "salta la prossima tappa", "salta questa tappa", "salta la sosta",
            "skip the stop", "skip stop", "skip the next stop", "skip this stop",
        )
        private val EndNavigationWords = setOf(
            "termina la navigazione", "termina navigazione", "ferma la navigazione", "interrompi la navigazione", "annulla la navigazione",
            "chiudi la navigazione", "stop navigation", "stop the navigation", "end navigation", "end the navigation", "cancel navigation",
            "cancel the navigation",
        )
        private val Articles = setOf("il", "lo", "la", "i", "gli", "le", "the")
        private val NotPlaces = setOf("avanti", "indietro", "back", "forward", "on", "ahead", "home screen")
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
