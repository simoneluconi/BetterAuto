package com.simoneluconi.betterauto.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.aap.AudioSinkChannel
import com.simoneluconi.betterauto.car.CarMotion
import com.simoneluconi.betterauto.car.CarMotionState
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.settings.LauncherSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.math.abs

data class PcmFormat(val sampleRate: Int, val channels: Int, val bits: Int) {
    override fun toString() = "PCM $sampleRate Hz $bits-bit ${if (channels == 1) "mono" else "${channels}ch"}"
}

/** Receives captured phone audio, already in [format]. */
interface AudioSink {
    val format: PcmFormat

    /** Called on the capture thread with one chunk of PCM; [silent] when the chunk carries no sound. */
    fun onPcm(data: ByteArray, timestampUs: Long, silent: Boolean)

    /** Read on the capture thread: whether a silent chunk is of any use now; when not, capture skips it. */
    val wantsSilence: Boolean

    /** Level to play at, read on the capture thread for each chunk (below 1 while the music is ducked). */
    val gain: Float get() = 1f
}

/**
 * Joins the two halves of car audio, which arrive independently: the user's capture consent
 * (a MediaProjection) and the head unit's media channel, configured with its PCM format.
 * Capture runs only while both are present.
 *
 * Navigation prompts: with Shizuku they move to the car's guidance stream, which the car plays over
 * the music, lowering it. Playback capture cannot take them, so without Shizuku (or without a guidance
 * stream) they play on the phone: [NavPrompts] notices them, the phone's speaker comes back for the
 * moment and the music in the car is lowered under them.
 */
object CarAudio {
    private lateinit var appContext: Context
    private var projection: MediaProjection? = null
    private var sink: AudioSink? = null
    private var guidanceSink: AudioSink? = null
    private var capture: AudioCapture? = null
    private var shizuku: ShizukuCapture? = null
    private var shizukuGuidance: ShizukuCapture? = null
    private var prompts: NavPrompts? = null
    /** Shizuku failed during this car connection: use playback capture until the next one. */
    private var shizukuFailed = false
    private var autoPlayed = false
    private val main = Handler(Looper.getMainLooper())

    /**
     * Call on the main thread when the service or the app starts. If the app died while the phone was
     * muted, the music may still be playing: pause it first, so restoring the volume never blasts the phone.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (capture == null && PhoneSpeakerMute.isMuted(appContext)) {
            NowPlaying.acquire(appContext)
            NowPlaying.pause()
            NowPlaying.release()
            PhoneSpeakerMute.release(appContext)
        }
    }

    /** The kinds of sound that go to the car: music, and per setting apps that don't say. */
    fun carUsages(): IntArray = buildList {
        add(AudioAttributes.USAGE_MEDIA)
        if (LauncherSettings.get(appContext).unknownAudio.value) add(AudioAttributes.USAGE_UNKNOWN)
    }.toIntArray()

    /**
     * Only the music apps go to the car when Shizuku moves the audio: the players BetterAuto lists (they
     * offer a media browser) and the one playing now. Videos in a social app, for instance, keep playing
     * on the phone.
     */
    private fun musicApps(): IntArray {
        val packages = (MediaApps.installed(appContext) + listOfNotNull(NowPlaying.track.value?.packageName)).distinct()
        return packages.mapNotNull { runCatching { appContext.packageManager.getApplicationInfo(it, 0).uid }.getOrNull() }
            .distinct().toIntArray()
    }

    val hasProjection: Boolean
        @Synchronized get() = projection != null

    /** The audio is moved to the car through Shizuku, so no capture consent is needed. */
    val movesAudio: Boolean
        @Synchronized get() = shizuku != null

    /** Told when the way audio reaches the car changes: consent given or gone, Shizuku on or off (any thread). */
    @Volatile
    var onProjectionChanged: (() -> Unit)? = null

    /** Told each time the way is chosen: true when a car is connected and the music stays on the phone (any thread). */
    @Volatile
    var onAudioOnPhone: ((onPhone: Boolean) -> Unit)? = null

    @Synchronized
    fun setProjection(value: MediaProjection?) {
        if (value === projection) return
        projection?.stop()
        projection = value
        restart()
        onProjectionChanged?.invoke()
    }

    /**
     * Call when the car disconnects, before the music is paused. Back within [RESUME_MEMORY_MS], the
     * music comes back as it was: still paused if the driver had paused it, playing if it was playing.
     * Only real sessions count (the car's audio channel was set up), not failed connection attempts.
     */
    @Synchronized
    fun onDisconnected() {
        if (sink == null) return
        val now = System.currentTimeMillis()
        val playingNow = NowPlaying.track.value?.playing == true
        // Cars switching off pause the music themselves a little before they go: that was not the driver
        val pausedBySwitchOff = !playingNow && playingBeforeCarPause && now - carPausedAt < SWITCH_OFF_PAUSE_MS
        val playing = playingNow || pausedBySwitchOff
        prefs().edit { putLong(KEY_DISCONNECTED_AT, now).putBoolean(KEY_WAS_PLAYING, playing) }
        val how = if (playingNow) "playing" else if (pausedBySwitchOff) "paused by the car switching off" else "paused"
        EventLog.log("Car audio: disconnected, music was $how")
        carPausedAt = 0L
    }

    /** When the car last sent a plain pause, and whether the music was playing then. */
    private var carPausedAt = 0L
    private var playingBeforeCarPause = false

    /** The car sent a pause (not play/pause): maybe the driver, maybe the car switching off. */
    @Synchronized
    fun onCarPause() {
        playingBeforeCarPause = NowPlaying.track.value?.playing == true
        carPausedAt = System.currentTimeMillis()
        checkSwitchOff()
    }

    /** When the car last dropped the phone's calls connection during this session. */
    private var callsDroppedAt = 0L
    /** When the car paused the music switching off while the session went on (a quick stop); 0 when it did not. */
    private var switchOffPauseAt = 0L
    private var driveOffWatch: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The car dropped the phone's calls connection: with a pause at the same moment, the car is switching off. */
    @Synchronized
    fun onCarCallsDropped() {
        callsDroppedAt = System.currentTimeMillis()
        checkSwitchOff()
    }

    /** The car connected the phone for calls again: back from a quick stop, if it was one. */
    @Synchronized
    fun onCarCallsBack() = resumeAfterSwitchOff("the car connected the phone again")

    /**
     * A pause together with the calls dropped is the car switching off: a driver's pause never drops the
     * Bluetooth. A dongle may keep the session through a quick stop, so no reconnection brings the music back:
     * it plays again when the car drives off (or connects the phone again), unless the driver started and
     * stopped it meanwhile.
     */
    private fun checkSwitchOff() {
        if (sink == null || !playingBeforeCarPause || switchOffPauseAt != 0L) return
        if (carPausedAt == 0L || callsDroppedAt == 0L || abs(carPausedAt - callsDroppedAt) > SWITCH_OFF_TOGETHER_MS) return
        switchOffPauseAt = System.currentTimeMillis()
        EventLog.log("Car audio: the car paused the music switching off, it plays again when the car drives off")
        driveOffWatch?.cancel()
        driveOffWatch = scope.launch {
            val playingByHand = NowPlaying.track.map { it?.playing == true }.distinctUntilChanged().dropWhile { it }.filter { it }.map { null }
            val drivesOff = CarMotionState.state.filter { it == CarMotion.MOVING }.map { "the car drives off" }
            val why = merge(playingByHand, drivesOff).first()
            synchronized(this@CarAudio) {
                if (why != null) resumeAfterSwitchOff(why) else switchOffPauseAt = 0L
            }
        }
    }

    private fun resumeAfterSwitchOff(why: String) {
        if (switchOffPauseAt == 0L) return
        val away = System.currentTimeMillis() - switchOffPauseAt
        switchOffPauseAt = 0L
        driveOffWatch?.cancel()
        driveOffWatch = null
        if (sink == null || away > RESUME_MEMORY_MS || NowPlaying.track.value?.playing == true) return
        EventLog.log("Car audio: back from a quick stop ($why), the music plays again")
        main.post { NowPlaying.resume() }
    }

    private fun shouldAutoPlay(): Boolean {
        val prefs = prefs()
        val away = System.currentTimeMillis() - prefs.getLong(KEY_DISCONNECTED_AT, 0L)
        if (away in 0 until RESUME_MEMORY_MS) {
            val wasPlaying = prefs.getBoolean(KEY_WAS_PLAYING, false)
            EventLog.log("Car audio: back after ${away / 1000} s, music ${if (wasPlaying) "resumes" else "stays paused"} as it was")
            return wasPlaying
        }
        return LauncherSettings.get(appContext).autoPlay.value
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "car_audio_state"
    private const val KEY_DISCONNECTED_AT = "disconnected_at"
    private const val KEY_WAS_PLAYING = "was_playing"
    /** How long a disconnection keeps the music as it was, instead of the auto-play setting. */
    private const val RESUME_MEMORY_MS = 15 * 60 * 1000L
    /** A car's pause this close to its going away is the car switching off (seen 40-50 s before on VW group cars). */
    private const val SWITCH_OFF_PAUSE_MS = 2 * 60 * 1000L
    /** A car switching off pauses and drops the calls within half a second (Audi MMX2): a little margin. */
    private const val SWITCH_OFF_TOGETHER_MS = 3_000L

    @Synchronized
    fun setSink(value: AudioSink?) {
        // A new car audio channel is a new connection: allow auto-play again, and Shizuku another try
        if (value == null) {
            autoPlayed = false
            shizukuFailed = false
            // The session is over: the next connection's own auto-play decides
            switchOffPauseAt = 0L
            callsDroppedAt = 0L
            driveOffWatch?.cancel()
            driveOffWatch = null
        }
        sink = value
        restart()
    }

    /**
     * The car's guidance stream (navigation prompts), when it offers one. It comes right after the media
     * one: only the prompts' part changes, the music keeps going.
     */
    @Synchronized
    fun setGuidanceSink(value: AudioSink?) {
        guidanceSink = value
        if (shizuku == null) return // copying: the prompts stay on the phone either way
        shizukuGuidance?.stop()
        shizukuGuidance = null
        stopPrompts()
        startGuidance()
    }

    private fun restart() {
        val wasMoving = shizuku != null
        capture?.stop()
        capture = null
        shizuku?.stop()
        shizuku = null
        shizukuGuidance?.stop()
        shizukuGuidance = null
        stopPrompts()
        val s = sink
        val shizukuState = if (s != null) ShizukuAudio.state(appContext) else null
        if (s != null) EventLog.log("Car audio: Shizuku ${if (shizukuFailed) "failed earlier" else shizukuState.toString()}")
        // With Shizuku the audio is moved like the system Android Auto does; otherwise it is copied
        if (s != null && !shizukuFailed && shizukuState == ShizukuAudio.State.READY) {
            PhoneSpeakerMute.release(appContext)
            lateinit var started: ShizukuCapture
            started = ShizukuCapture(
                appContext, s, ::musicApps, carUsages(),
                onStarted = { autoPlayOnce() }, onFailed = { onShizukuFailed(started) },
            )
            shizuku = started.also { it.start() }
            startGuidance()
            if (!wasMoving) onProjectionChanged?.invoke()
            onAudioOnPhone?.invoke(false)
            return
        }
        if (wasMoving) onProjectionChanged?.invoke()
        val p = projection
        if (p == null || s == null) {
            PhoneSpeakerMute.release(appContext)
            onAudioOnPhone?.invoke(s != null)
            return
        }
        capture = try {
            AudioCapture(p, s, carUsages()).also { it.start() }
        } catch (e: Exception) {
            EventLog.log("Audio capture failed: $e")
            null
        }
        if (capture == null) {
            PhoneSpeakerMute.release(appContext)
            onAudioOnPhone?.invoke(true)
            return
        }
        onAudioOnPhone?.invoke(false)
        PhoneSpeakerMute.engage(appContext)
        // Capture cannot take the prompts: they play on the (muted) phone, brought back up while they speak
        startPrompts(liftSpeaker = true)
        autoPlayOnce()
    }

    /** With Shizuku: the prompts to the car's guidance stream, or, without one, left on the phone with the music lowered. */
    private fun startGuidance() {
        val g = guidanceSink
        if (g == null) {
            EventLog.log("Car audio: no guidance stream from the car (yet), navigation prompts stay on the phone")
            startPrompts(liftSpeaker = false)
            return
        }
        lateinit var started: ShizukuCapture
        started = ShizukuCapture(
            appContext, g, { IntArray(0) }, intArrayOf(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE),
            onStarted = { EventLog.log("Car audio: navigation prompts go to the car's guidance stream") },
            onFailed = { onGuidanceFailed(started) },
        )
        shizukuGuidance = started.also { it.start() }
    }

    /** The prompts' route failed: they stay on the phone (not muted with Shizuku), the music still steps aside. */
    @Synchronized
    private fun onGuidanceFailed(failed: ShizukuCapture) {
        if (shizukuGuidance !== failed) return
        shizukuGuidance = null
        failed.stop()
        EventLog.log("Car audio: navigation prompts could not be moved, they stay on the phone")
        startPrompts(liftSpeaker = false)
    }

    /** Follows prompts playing on the phone: the music in the car lowered under them, and the muted speaker lifted. */
    private fun startPrompts(liftSpeaker: Boolean) {
        val media = sink as? AudioSinkChannel ?: return
        prompts = NavPrompts(appContext) { speaking ->
            EventLog.log("Car audio: navigation prompt ${if (speaking) "on the phone, music lowered" else "over"}")
            media.ducked = speaking
            if (liftSpeaker) {
                if (speaking) PhoneSpeakerMute.lift(appContext) else PhoneSpeakerMute.lower(appContext)
            }
        }.also { p -> main.post { p.start() } }
    }

    private fun stopPrompts() {
        val p = prompts ?: return
        prompts = null
        (sink as? AudioSinkChannel)?.ducked = false
        main.post { p.stop() }
    }

    /** Debug: copy the audio as without Shizuku ([on]), until the next connection or until asked back. */
    @Synchronized
    fun copyInstead(on: Boolean) {
        shizukuFailed = on
        restart()
    }

    /** Shizuku stopped working mid-connection: fall back to playback capture for the rest of it. */
    @Synchronized
    private fun onShizukuFailed(failed: ShizukuCapture) {
        if (shizuku !== failed) return
        shizukuFailed = true
        EventLog.log("Car audio: Shizuku not usable, copying the audio instead")
        restart()
    }

    /** Like Android Auto: start the music once per connection, only when it really goes to the car. */
    @Synchronized
    private fun autoPlayOnce() {
        if (autoPlayed) return
        autoPlayed = true
        if (shouldAutoPlay()) {
            EventLog.log("Car audio: auto-play on connect")
            main.post { NowPlaying.resume() }
        }
    }
}
