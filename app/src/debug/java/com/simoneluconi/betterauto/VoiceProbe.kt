package com.simoneluconi.betterauto

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Development: can the phone's own speech recognizer do the car's voice commands? Checks on-device
 * recognition and the Italian model, then feeds it a synthesized phrase as if it came from the car's
 * microphone (or listens to the phone's microphone), and logs what it understood and how fast.
 *   adb shell am broadcast -p com.simoneluconi.betterauto -a com.simoneluconi.betterauto.DEBUG_INPUT --es voice probe
 *   ... --es voice probe --es phrase "chiama Mario Rossi"
 *   ... --es voice mic        (speak to the phone within a few seconds)
 */
object VoiceProbe {
    private const val TAG = "Voice probe"
    private const val RATE = 16_000
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    fun run(context: Context, mode: String, phrase: String, language: String) = main.post {
        val app = context.applicationContext
        log("recognition available: ${SpeechRecognizer.isRecognitionAvailable(app)}")
        if (Build.VERSION.SDK_INT < 31) {
            log("on-device recognition needs Android 12")
            return@post
        }
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        log("on-device recognition available: $onDevice")
        if (!onDevice) return@post
        val intent = intent(language)
        if (Build.VERSION.SDK_INT >= 33) {
            val probe = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            probe.checkRecognitionSupport(intent, app.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    log("installed: ${support.installedOnDeviceLanguages}")
                    log("supported, not downloaded: ${support.supportedOnDeviceLanguages}")
                    log("downloading: ${support.pendingOnDeviceLanguages}")
                    probe.destroy()
                    if (mode == "mic") listen(app, intent, null) else synthesizeAndRecognize(app, intent, phrase, language)
                }

                override fun onError(error: Int) {
                    log("support check error $error")
                    probe.destroy()
                    if (mode == "mic") listen(app, intent, null) else synthesizeAndRecognize(app, intent, phrase, language)
                }
            })
        } else {
            listen(app, intent, null)
        }
    }

    private fun intent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        if (Build.VERSION.SDK_INT >= 33) {
            // Words the commands expect: helps the recognizer pick names and commands right
            putExtra(
                RecognizerIntent.EXTRA_BIASING_STRINGS,
                arrayListOf("chiama", "Mario Rossi", "cellulare", "metti", "pausa", "avanti", "indietro", "Symfonium"),
            )
        }
    }

    /** The phrase spoken by the phone's text-to-speech, then given to the recognizer as raw PCM. */
    private fun synthesizeAndRecognize(context: Context, intent: Intent, phrase: String, language: String) {
        if (Build.VERSION.SDK_INT < 33) {
            log("feeding our own audio needs Android 13")
            return
        }
        val file = File(context.cacheDir, "voice_probe.wav")
        tts?.shutdown()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            val t = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                log("text-to-speech not ready ($status)")
                return@TextToSpeech
            }
            log("text-to-speech engine ${t.defaultEngine}, language ${t.setLanguage(Locale.forLanguageTag(language))}")
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    main.post {
                        val pcm = runCatching { wavTo16kMono(file.readBytes()) }.getOrNull()
                        t.shutdown()
                        if (pcm == null) {
                            log("could not read the synthesized audio")
                            return@post
                        }
                        log("phrase \"$phrase\": ${pcm.size / 2 * 1000 / RATE} ms of audio")
                        listen(context, intent, pcm)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = log("text-to-speech failed")
            })
            t.synthesizeToFile(phrase, Bundle(), file, "probe")
        }
        tts = engine
    }

    /** Runs one recognition: from [pcm] through a pipe (like the car's microphone), or the phone's mic. */
    private fun listen(context: Context, base: Intent, pcm: ByteArray?) {
        recognizer?.destroy()
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = rec
        val intent = Intent(base)
        val started = SystemClock.elapsedRealtime()
        var audioEnd = 0L
        var firstPartial = 0L
        if (pcm != null && Build.VERSION.SDK_INT >= 33) {
            val (read, write) = ParcelFileDescriptor.createPipe()
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            thread(name = "VoiceProbeFeed") {
                // Real time, in 20 ms chunks, like a microphone: half a second of silence, the phrase, one second of silence
                runCatching {
                    FileOutputStream(write.fileDescriptor).use { out ->
                        val chunk = RATE / 50 * 2
                        val audio = ByteArray(RATE) + pcm + ByteArray(RATE * 2)
                        var offset = 0
                        while (offset < audio.size) {
                            val n = minOf(chunk, audio.size - offset)
                            out.write(audio, offset, n)
                            offset += n
                            if (offset >= RATE + pcm.size && audioEnd == 0L) audioEnd = SystemClock.elapsedRealtime()
                            Thread.sleep(20)
                        }
                    }
                }.onFailure { log("feeding stopped: $it") }
                runCatching { write.close() }
            }
        } else {
            log("listening to the phone's microphone: speak now")
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = log("ready after ${SystemClock.elapsedRealtime() - started} ms")
            override fun onBeginningOfSpeech() = log("speech detected at ${SystemClock.elapsedRealtime() - started} ms")
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                if (audioEnd == 0L) audioEnd = SystemClock.elapsedRealtime()
                log("end of speech at ${SystemClock.elapsedRealtime() - started} ms")
            }

            override fun onError(error: Int) {
                log("error $error (${errorName(error)}) at ${SystemClock.elapsedRealtime() - started} ms")
                rec.destroy()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (text.isBlank()) return
                if (firstPartial == 0L) firstPartial = SystemClock.elapsedRealtime()
                log("partial \"$text\" at ${SystemClock.elapsedRealtime() - started} ms")
            }

            override fun onResults(results: Bundle?) {
                val now = SystemClock.elapsedRealtime()
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                log("results ${texts.mapIndexed { i, t -> "\"$t\" ${scores?.getOrNull(i)?.let { "(%.2f)".format(it) } ?: ""}" }}")
                log("final ${now - started} ms from start, ${if (audioEnd > 0) now - audioEnd else -1} ms after the end of the phrase")
                rec.destroy()
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        rec.startListening(intent)
    }

    /** 16-bit PCM WAV (any rate, mono or stereo) to 16 kHz mono. */
    private fun wavTo16kMono(wav: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var channels = 1
        var rate = RATE
        var pos = 12
        var data: ByteArray? = null
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            if (id == "fmt ") {
                channels = buf.getShort(pos + 10).toInt()
                rate = buf.getInt(pos + 12)
            } else if (id == "data") {
                data = wav.copyOfRange(pos + 8, minOf(wav.size, pos + 8 + size))
                break
            }
            pos += 8 + size + (size and 1)
        }
        val samples = ByteBuffer.wrap(data ?: error("no data")).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = samples.remaining() / channels
        val outFrames = (frames.toLong() * RATE / rate).toInt()
        val out = ByteBuffer.allocate(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outFrames) {
            val src = i.toDouble() * rate / RATE
            val a = src.toInt().coerceAtMost(frames - 1)
            val b = (a + 1).coerceAtMost(frames - 1)
            val f = src - a
            fun mono(frame: Int): Double = (0 until channels).sumOf { samples.get(frame * channels + it).toDouble() } / channels
            out.putShort((mono(a) * (1 - f) + mono(b) * f).toInt().coerceIn(-32768, 32767).toShort())
        }
        return out.array()
    }

    private fun errorName(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permissions"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language not downloaded"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "cannot check support"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "server disconnected"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
        else -> "?"
    }

    private fun log(message: String) = EventLog.log("$TAG: $message")
}
