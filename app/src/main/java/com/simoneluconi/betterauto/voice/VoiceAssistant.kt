package com.simoneluconi.betterauto.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.audio.CarMicrophone
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.notifications.CarNotifications
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.phone.Contact
import com.simoneluconi.betterauto.phone.PhoneBook
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.MicSource
import com.simoneluconi.betterauto.settings.NameOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

sealed interface VoiceState {
    data object Idle : VoiceState

    /** [text] is what the recognizer heard so far; [level] 0..1 follows the voice. */
    data class Listening(val text: String, val level: Float, val carMicrophone: Boolean) : VoiceState

    /** More contacts fit the name: the driver picks one. */
    data class Choosing(val names: List<String>, val numberType: String?) : VoiceState

    data class Done(val heard: String, val message: String, val understood: Boolean) : VoiceState
}

/**
 * Voice commands: the car's microphone (the phone's as a fallback) goes to the phone's own on-device
 * speech recognizer, the live transcription is shown, and a command runs as soon as the transcription
 * forms one and holds still for a moment, without waiting for the recognizer to close.
 * Everything on the main thread.
 */
object VoiceAssistant {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recognizer: SpeechRecognizer? = null
    private var feed: LinkedBlockingQueue<ByteArray>? = null
    private var contacts: List<Contact> = emptyList()
    private var parser = CommandParser(emptyList())
    private var resumeMusic = false
    private var heard = ""
    private var pendingCommand: VoiceCommand? = null
    private var startedAt = 0L
    private lateinit var appContext: Context

    private val runPending = Runnable { pendingCommand?.let { execute(it) } }
    private val giveUp = Runnable { finish(understood = false) }
    private val backToIdle = Runnable { _state.value = VoiceState.Idle }

    fun toggle(context: Context) {
        if (_state.value is VoiceState.Listening || _state.value is VoiceState.Choosing) cancel() else start(context)
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        val settings = LauncherSettings.get(appContext)
        if (!settings.voiceEnabled.value) return
        main.removeCallbacks(backToIdle)
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            EventLog.log("Voice: no on-device speech recognition on this phone")
            show(VoiceState.Done("", appContext.getString(R.string.voice_unavailable), understood = false), speak = false)
            return
        }
        stopRecognition()
        heard = ""
        pendingCommand = null
        startedAt = SystemClock.elapsedRealtime()
        loadContacts()

        // The music steps aside while listening, and comes back unless the command was about music
        resumeMusic = NowPlaying.isPlaying
        if (resumeMusic) NowPlaying.pause()
        Speech.stop()
        Speech.chime()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (Build.VERSION.SDK_INT >= 33) {
                // Contact names and command words: the recognizer leans towards them
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(Biasing + contacts.take(200).map { it.name }))
            }
        }
        // The car's microphone whenever the car has one, unless the driver chose the phone's
        val carMic = settings.voiceMicrophone.value == MicSource.CAR && Build.VERSION.SDK_INT >= 33 && attachCarMicrophone(intent)
        EventLog.log("Voice: listening (${if (carMic) "car" else "phone"} microphone, ${Locale.getDefault().toLanguageTag()})")

        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        recognizer = rec
        rec.setRecognitionListener(listener)
        show(VoiceState.Listening("", 0f, carMic), speak = false)
        main.postDelayed(giveUp, MAX_LISTEN_MS)
        rec.startListening(intent)
    }

    fun cancel() {
        EventLog.log("Voice: cancelled")
        stopRecognition()
        Speech.cancelTone()
        restoreMusic()
        _state.value = VoiceState.Idle
    }

    /** From the list of contacts that fit the spoken name. */
    fun choose(name: String) {
        val choosing = _state.value as? VoiceState.Choosing ?: return
        call(name, choosing.numberType)
    }

    /** The car's microphone through a pipe, in its own format; false if the car has none. */
    private fun attachCarMicrophone(intent: Intent): Boolean {
        val format = CarMicrophone.format.value ?: return false
        val (read, write) = ParcelFileDescriptor.createPipe()
        val queue = LinkedBlockingQueue<ByteArray>()
        if (!CarMicrophone.start { pcm -> queue.offer(pcm) }) {
            read.close()
            write.close()
            return false
        }
        feed = queue
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, format.channels)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, format.sampleRate)
        // The connection's thread must never wait on the recognizer: a queue and a writer of our own
        thread(name = "VoiceFeed") {
            runCatching {
                FileOutputStream(write.fileDescriptor).use { out ->
                    while (true) {
                        val chunk = queue.take()
                        if (chunk.isEmpty()) break
                        out.write(chunk)
                    }
                }
            }
            runCatching { write.close() }
            runCatching { read.close() }
        }
        return true
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            val listening = _state.value as? VoiceState.Listening ?: return
            _state.value = listening.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::onText)
        }

        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::onText)
            finish(understood = pendingCommand != null)
        }

        override fun onEndOfSpeech() {
            // The transcription is final enough: act now rather than wait for the recognizer
            if (pendingCommand != null) main.post(runPending)
        }

        override fun onError(error: Int) {
            EventLog.log("Voice: recognizer error $error")
            finish(understood = pendingCommand != null)
        }
    }

    private fun onText(text: String) {
        if (_state.value !is VoiceState.Listening) return
        if (text == heard) return
        heard = text
        (_state.value as? VoiceState.Listening)?.let { _state.value = it.copy(text = text) }
        val command = parser.parse(text)
        pendingCommand = command
        main.removeCallbacks(runPending)
        // A command that holds still for a moment is what the driver meant
        if (command != null) main.postDelayed(runPending, STABLE_MS)
    }

    private fun finish(understood: Boolean) {
        if (_state.value !is VoiceState.Listening) return
        val command = pendingCommand
        if (understood && command != null) {
            execute(command)
            return
        }
        stopRecognition()
        restoreMusic()
        Speech.cancelTone()
        val message = if (heard.isBlank()) appContext.getString(R.string.voice_nothing_heard) else appContext.getString(R.string.voice_not_understood, heard)
        EventLog.log("Voice: not understood after ${SystemClock.elapsedRealtime() - startedAt} ms")
        show(VoiceState.Done(heard, message, understood = false), speak = false)
    }

    private fun execute(command: VoiceCommand) {
        if (_state.value !is VoiceState.Listening) return
        EventLog.log("Voice: ${command::class.simpleName} after ${SystemClock.elapsedRealtime() - startedAt} ms")
        stopRecognition()
        val settings = LauncherSettings.get(appContext)
        when (command) {
            is VoiceCommand.Call -> {
                resumeMusic = false
                if (command.candidates.size == 1) call(command.candidates.first(), command.numberType)
                else _state.value = VoiceState.Choosing(command.candidates, command.numberType)
                return
            }
            is VoiceCommand.Play -> {
                resumeMusic = false
                val ok = NowPlaying.playFromSearch(command.query)
                done(if (ok) appContext.getString(R.string.voice_playing, command.query) else appContext.getString(R.string.voice_no_player))
            }
            VoiceCommand.Pause -> {
                resumeMusic = false
                NowPlaying.pause()
                done(appContext.getString(R.string.voice_paused), speak = false)
            }
            VoiceCommand.Resume -> {
                resumeMusic = false
                NowPlaying.resume()
                done(appContext.getString(R.string.voice_resumed), speak = false)
            }
            VoiceCommand.Next -> {
                resumeMusic = true
                NowPlaying.next()
                done(appContext.getString(R.string.voice_next), speak = false)
            }
            VoiceCommand.Previous -> {
                resumeMusic = true
                NowPlaying.previous()
                done(appContext.getString(R.string.voice_previous), speak = false)
            }
            is VoiceCommand.Open -> {
                settings.setSection(command.section)
                done(appContext.getString(R.string.voice_opening), speak = false)
            }
            is VoiceCommand.Quiet -> {
                settings.setQuietMode(command.on)
                done(appContext.getString(if (command.on) R.string.voice_quiet_on else R.string.voice_quiet_off))
            }
            VoiceCommand.ReadNotifications -> {
                val list = CarNotifications.visible(CarNotifications.all.value, settings).take(3)
                val text = if (list.isEmpty()) appContext.getString(R.string.voice_no_notifications)
                else list.joinToString(". ") { n -> "${n.title}: ${n.last?.text.orEmpty()}" }
                // The music comes back after the reading
                done(text)
            }
            VoiceCommand.Cancel -> {
                restoreMusic()
                _state.value = VoiceState.Idle
                return
            }
        }
        restoreMusic()
    }

    private fun call(name: String, numberType: String?) {
        val contact = contacts.firstOrNull { it.name == name }
        if (contact == null) {
            done(appContext.getString(R.string.voice_not_understood, name))
            return
        }
        scope.launch {
            val numbers = PhoneBook.numbers(appContext, contact.id)
            val wanted = when (numberType) {
                "mobile" -> listOf(Phone.TYPE_MOBILE, Phone.TYPE_WORK_MOBILE)
                "home" -> listOf(Phone.TYPE_HOME)
                "work" -> listOf(Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE, Phone.TYPE_COMPANY_MAIN)
                else -> emptyList()
            }
            val number = numbers.firstOrNull { it.type in wanted }
                ?: numbers.firstOrNull { it.primary }
                ?: numbers.firstOrNull { it.type == Phone.TYPE_MOBILE }
                ?: numbers.firstOrNull()
            if (number == null) {
                done(appContext.getString(R.string.voice_no_number, name))
                return@launch
            }
            done(appContext.getString(R.string.voice_calling, name, number.label))
            // Let the reply finish before the call takes the audio
            main.postDelayed({ Calls.place(appContext, number.number) }, CALL_DELAY_MS)
        }
    }

    private fun done(message: String, speak: Boolean = true) {
        show(VoiceState.Done(heard, message, understood = true), speak = speak)
    }

    private fun show(state: VoiceState, speak: Boolean) {
        _state.value = state
        if (state is VoiceState.Done) {
            if (speak && LauncherSettings.get(appContext).voiceReplies.value) Speech.say(appContext, state.message)
            main.removeCallbacks(backToIdle)
            main.postDelayed(backToIdle, if (state.understood) DONE_SHOW_MS else FAILED_SHOW_MS)
        }
    }

    private fun stopRecognition() {
        main.removeCallbacks(runPending)
        main.removeCallbacks(giveUp)
        recognizer?.let {
            it.setRecognitionListener(null)
            runCatching { it.cancel() }
            it.destroy()
        }
        recognizer = null
        CarMicrophone.stop()
        feed?.offer(ByteArray(0))
        feed = null
    }

    private fun restoreMusic() {
        if (resumeMusic) NowPlaying.resume()
        resumeMusic = false
    }

    /** Contacts by the natural spoken order ("Mario Rossi"), refreshed at every use. */
    private fun loadContacts() {
        parser = CommandParser(contacts.map { it.name })
        scope.launch {
            contacts = PhoneBook.contacts(appContext, NameOrder.FIRST_LAST)
            parser = CommandParser(contacts.map { it.name })
            // Text heard while the contacts were loading gets another look
            if (heard.isNotBlank()) {
                val again = heard
                heard = ""
                onText(again)
            }
        }
    }

    private val Biasing = listOf("chiama", "metti", "pausa", "avanti", "indietro", "apri", "cellulare", "casa", "lavoro")

    private const val STABLE_MS = 550L
    private const val MAX_LISTEN_MS = 9_000L
    private const val DONE_SHOW_MS = 2_500L
    private const val FAILED_SHOW_MS = 3_500L
    private const val CALL_DELAY_MS = 1_400L
}
