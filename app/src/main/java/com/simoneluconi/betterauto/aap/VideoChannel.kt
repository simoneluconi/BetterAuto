package com.simoneluconi.betterauto.aap

import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.SetupOuterClass.Setup
import aap_protobuf.service.media.shared.message.StartOuterClass.Start
import aap_protobuf.service.media.shared.message.StopOuterClass.Stop
import aap_protobuf.service.media.sink.MediaMessageIdOuterClass.MediaMessageId
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import com.simoneluconi.betterauto.car.Edges
import com.simoneluconi.betterauto.car.Viewport
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import aap_protobuf.service.media.video.message.VideoFocusModeOuterClass.VideoFocusMode
import aap_protobuf.service.media.video.message.VideoFocusNotificationOuterClass.VideoFocusNotification
import aap_protobuf.service.media.video.message.VideoFocusRequestNotificationOuterClass.VideoFocusRequestNotification
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.car.CarState
import com.simoneluconi.betterauto.car.VideoStats
import com.simoneluconi.betterauto.projection.Projection
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.video.VideoEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Video sink of the head unit. Flow:
 *   phone -> SETUP(H.264 BP)            HU -> CONFIG(max_unacked, accepted config indices)
 *   HU -> VIDEO_FOCUS_NOTIFICATION(PROJECTED), by itself (asked with VIDEO_FOCUS_REQUEST if it does not come)
 *   phone -> START, only once the screen is ours, as Android Auto does
 *   phone -> CODEC_CONFIG (SPS/PPS), then DATA frames; HU -> ACK per frame
 *   HU -> VIDEO_FOCUS_NOTIFICATION(NATIVE): phone -> STOP; back to PROJECTED: a new START, SPS/PPS, keyframe
 */
class VideoChannel(
    session: AapSession,
    channelId: Int,
    private val configs: List<VideoConfiguration>,
    private val projection: Projection,
) : ServiceChannel(session, channelId) {
    override val name = "video"

    private var config: VideoConfiguration? = null
    private var configIndex = 0
    /** A stream is started (START sent, no STOP since). */
    private var started = false
    /** Each START opens a new stream session. */
    private var streamSession = 0

    @Volatile
    private var focused = false
    private var encoder: VideoEncoder? = null
    /** The running encoder's SPS/PPS, sent again whenever the car gives the screen back. */
    @Volatile
    private var codecConfig: ByteArray? = null

    @Volatile
    private var closed = false
    private var watchdog: Thread? = null
    private var restarts = 0
    /** When the watchdog asked a silent screen to redraw, 0 if it has not. */
    private var redrawAskedAt = 0L
    /** When the frame now going out started waiting (window or socket), 0 when none is. */
    @Volatile
    private var sendingSince = 0L
    private var slowLinkLogged = false

    // Frames in flight, bounded by the head unit's max_unacked
    @Volatile
    private var window = Semaphore(1)
    private var statsFrames = 0
    private var statsBytes = 0L
    private var statsStart = 0L

    private val settings = LauncherSettings.get(projection.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onOpened() {
        send(
            MediaMessageId.MEDIA_MESSAGE_SETUP_VALUE,
            Setup.newBuilder().setType(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP).build(),
        )
        // Another size: the car screen drawn again at the new density (while one is shown)
        scope.launch {
            settings.uiSize.drop(1).collect {
                EventLog.log("Video: size on the car screen ${it.name.lowercase()}, drawing it again")
                synchronized(this@VideoChannel) { if (encoder != null) restartStreaming() }
            }
        }
    }

    override fun onChannelMessage(msg: AapMessage) {
        when (msg.id) {
            MediaMessageId.MEDIA_MESSAGE_CONFIG_VALUE -> onConfig(Config.parseFrom(msg.payload))
            MediaMessageId.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE ->
                onFocus(VideoFocusNotification.parseFrom(msg.payload))
            MediaMessageId.MEDIA_MESSAGE_ACK_VALUE -> window.release(ackedMessages(msg.payload))
            else -> EventLog.log("Video: unhandled message ${msg.id} (${msg.payload.size} bytes)")
        }
    }

    private fun onConfig(response: Config) {
        val accepted = response.configurationIndicesList
        // Debug: a forced configuration, when the car accepts it (or lists none, meaning any)
        val forced = forcedConfig?.takeIf { it in configs.indices && (accepted.isEmpty() || it in accepted) }
        val index = forced ?: accepted.firstOrNull() ?: 0
        EventLog.log("Video: the car accepts configurations ${accepted.ifEmpty { configs.indices.toList() }.joinToString()}${if (forced != null) ", using $forced (forced)" else ""}")
        val chosen = configs.getOrNull(index)
        if (response.status != Config.Status.STATUS_READY || chosen == null) {
            EventLog.log("Video: configuration rejected (${response.status}, index $index)")
            return
        }
        config = chosen
        configIndex = index
        window = Semaphore(response.maxUnacked.coerceAtLeast(1))
        EventLog.log("Video ready: ${chosen.describe()}, max_unacked=${response.maxUnacked}")
        askFocusIfNotGiven()
        maybeStartStreaming()
    }

    /**
     * The car gives the screen by itself, as it does to Android Auto, which never asks. Some head units
     * (Crankshaft) wait to be asked: the request goes out only if the screen has not come after a moment.
     */
    private fun askFocusIfNotGiven() {
        thread(name = "video-focus", isDaemon = true) {
            try {
                Thread.sleep(FOCUS_WAIT_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (closed || focused) return@thread
            EventLog.log("Video: no screen from the car after $FOCUS_WAIT_MS ms, asking for it")
            send(
                MediaMessageId.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE,
                VideoFocusRequestNotification.newBuilder().setMode(VideoFocusMode.VIDEO_FOCUS_PROJECTED).build(),
            )
        }
    }

    @Synchronized
    private fun onFocus(notification: VideoFocusNotification) {
        EventLog.log("Video focus: ${notification.focus}")
        val wasFocused = focused
        // The car still shows our video while one of its own popups has the input (mode 4)
        focused = notification.focus == VideoFocusMode.VIDEO_FOCUS_PROJECTED ||
            notification.focus == VideoFocusMode.VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS
        if (focused == wasFocused) return
        val running = encoder
        when {
            !focused -> {
                running?.setPaused(true)
                stopStream()
            }
            running == null -> maybeStartStreaming()
            else -> {
                // A stream the car saw stop is started again as at the beginning: an Audi MMI (Harman) that only
                // got the frames again put up "observe the warnings on your phone" a few seconds later
                startStream()
                codecConfig?.let { send(MediaMessageId.MEDIA_MESSAGE_CODEC_CONFIG_VALUE, it) }
                // The resume brings a keyframe
                running.setPaused(false)
            }
        }
        // Nobody sees the launcher meanwhile: it stops drawing too, not only the encoder
        if (running != null) projection.setActive(focused)
    }

    @Synchronized
    private fun maybeStartStreaming() {
        val cfg = config ?: return
        if (closed || !focused || encoder != null) return
        // The stream starts once the car has given the screen: a START while it still shows its own
        // (NATIVE) made an Audi MMI (Harman) put up "observe the warnings on your phone", depending on timing
        startStream()

        val (width, height) = cfg.size()
        val fps = cfg.fps()
        val newEncoder = VideoEncoder(width, height, fps, bitrate = width * height * fps / 8)
        encoder = newEncoder
        statsStart = System.nanoTime()
        CarState.video.value = VideoStats(
            width = width,
            height = height,
            maxUnacked = window.availablePermits(),
            encoder = newEncoder.name,
        )
        newEncoder.start(
            onCodecConfig = {
                codecConfig = it
                send(MediaMessageId.MEDIA_MESSAGE_CODEC_CONFIG_VALUE, it)
            },
            frameHeadroom = MEDIA_TIMESTAMP_BYTES,
            onFrame = ::sendFrame,
        )
        CarState.viewport.value = cfg.viewport()
        // As big as the driver chose ("Size on the car screen"), from the density the car states
        val density = ((if (cfg.density > 0) cfg.density else 160) * settings.uiSize.value.scale).roundToInt()
        projection.start(newEncoder.inputSurface, width, height, density)
        active = this
        startWatchdog()
    }

    private fun startStream() {
        if (started) return
        started = true
        EventLog.log("Video: the screen is ours, starting the stream (session $streamSession)")
        send(
            MediaMessageId.MEDIA_MESSAGE_START_VALUE,
            Start.newBuilder().setSessionId(streamSession).setConfigurationIndex(configIndex).build(),
        )
    }

    /** The car shows its own screen: the stream ends there, as Android Auto ends it. */
    private fun stopStream() {
        if (!started) return
        started = false
        streamSession++
        EventLog.log("Video: the car took the screen, stream stopped")
        send(MediaMessageId.MEDIA_MESSAGE_STOP_VALUE, Stop.getDefaultInstance())
    }

    /**
     * A screen that sends nothing is frozen for the driver: rebuild the pipeline when it is stuck. Silence alone
     * is not proof: a still screen may give no frames (not every encoder repeats them), and over WiFi a frame
     * may be stuck on its way out. So a silent screen is first redrawn, and rebuilt only if even that brings no
     * frame; a send that blocks is a slow link, which a rebuild would not fix.
     */
    private fun startWatchdog() {
        if (watchdog != null) return
        watchdog = thread(name = "video-watchdog", isDaemon = true) {
            try {
                while (!closed) {
                    Thread.sleep(WATCHDOG_PERIOD_MS)
                    checkStalled()
                }
            } catch (_: InterruptedException) {
            }
        }
    }

    @Synchronized
    private fun checkStalled() {
        val running = encoder ?: return
        if (closed || !focused) return
        val now = System.nanoTime()
        val sending = sendingSince
        if (sending != 0L) {
            val blockedMs = (now - sending) / 1_000_000
            if (blockedMs >= STALL_MS && !slowLinkLogged) {
                slowLinkLogged = true
                EventLog.log("Video: a frame has been waiting $blockedMs ms to go out, the link to the head unit is slow")
            }
            return
        }
        val silentMs = (now - running.lastOutputNanos) / 1_000_000
        if (silentMs < STALL_MS) {
            redrawAskedAt = 0L
            return
        }
        if (redrawAskedAt == 0L || redrawAskedAt < running.lastOutputNanos) {
            redrawAskedAt = now
            projection.redraw()
            return
        }
        if ((now - redrawAskedAt) / 1_000_000 < REDRAW_WAIT_MS || restarts > MAX_RESTARTS) return
        redrawAskedAt = 0L
        if (restarts == MAX_RESTARTS) {
            restarts++
            EventLog.log("Video: still no frames after $MAX_RESTARTS restarts, giving up")
            return
        }
        restarts++
        EventLog.log("Video: no frames from the encoder for $silentMs ms, not even after a redraw, rebuilding the car screen ($restarts/$MAX_RESTARTS)")
        restartStreaming()
    }

    /** New encoder, virtual display and launcher; the car gets fresh SPS/PPS and a keyframe. */
    @Synchronized
    fun restartStreaming() {
        if (closed) return
        encoder?.stop()
        encoder = null
        projection.stop()
        maybeStartStreaming()
    }

    /** [payload]: the frame after [MEDIA_TIMESTAMP_BYTES] of room for its timestamp. */
    private fun sendFrame(payload: ByteArray, timestampUs: Long) {
        // The car is showing its own screen (reversing camera, its menus): nothing to decode there
        if (!focused) return
        sendingSince = System.nanoTime()
        try {
            // Wait for the head unit to catch up instead of flooding its decoder
            window.tryAcquire(WINDOW_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            putMediaTimestamp(payload, timestampUs)
            send(MediaMessageId.MEDIA_MESSAGE_DATA_VALUE, payload)
        } finally {
            sendingSince = 0L
        }
        slowLinkLogged = false
        updateStats(payload.size - MEDIA_TIMESTAMP_BYTES)
    }

    private fun updateStats(bytes: Int) {
        statsFrames++
        statsBytes += bytes
        val elapsed = System.nanoTime() - statsStart
        if (elapsed < 5_000_000_000L) return
        val fps = statsFrames * 1_000_000_000.0 / elapsed
        val kbps = statsBytes * 8 * 1_000_000 / elapsed
        CarState.video.value = CarState.video.value.copy(sentFps = fps, kbps = kbps)
        EventLog.log("Video: %.1f fps, %d kbps".format(Locale.ROOT, fps, kbps))
        statsFrames = 0
        statsBytes = 0
        statsStart = System.nanoTime()
    }

    override fun close() {
        closed = true
        scope.cancel()
        watchdog?.interrupt()
        synchronized(this) {
            if (active === this) active = null
            encoder?.stop()
            encoder = null
        }
        projection.stop()
    }

    companion object {
        /** Debug: the car's video configuration to use from the next session on, null for the car's first choice. */
        @Volatile
        var forcedConfig: Int? = null

        /** The video channel streaming to the car now, if any (used by debug tools). */
        @Volatile
        var active: VideoChannel? = null
            private set

        private const val WINDOW_TIMEOUT_MS = 500L
        private const val WATCHDOG_PERIOD_MS = 1000L
        private const val STALL_MS = 3000L
        /** After a redraw a live pipeline answers within a frame or two. */
        private const val REDRAW_WAIT_MS = 1500L
        private const val MAX_RESTARTS = 3

        /** How long the car may take to give the screen by itself before it is asked. */
        private const val FOCUS_WAIT_MS = 1500L
    }
}

fun VideoConfiguration.size(): Pair<Int, Int> = when (codecResolution) {
    VideoCodecResolutionType.VIDEO_1280x720 -> 1280 to 720
    VideoCodecResolutionType.VIDEO_1920x1080 -> 1920 to 1080
    VideoCodecResolutionType.VIDEO_2560x1440 -> 2560 to 1440
    VideoCodecResolutionType.VIDEO_3840x2160 -> 3840 to 2160
    VideoCodecResolutionType.VIDEO_720x1280 -> 720 to 1280
    VideoCodecResolutionType.VIDEO_1080x1920 -> 1080 to 1920
    VideoCodecResolutionType.VIDEO_1440x2560 -> 1440 to 2560
    VideoCodecResolutionType.VIDEO_2160x3840 -> 2160 to 3840
    else -> 800 to 480
}

/** The part of the frame the head unit shows: per-side margins (newer units) or centered width/height margins. */
fun VideoConfiguration.viewport(): Viewport {
    val (w, h) = size()
    val ui = if (hasUiConfig()) uiConfig else null
    val area = if (ui != null && ui.hasMargins()) {
        val m = ui.margins
        Viewport(w, h, m.left, m.top, w - m.left - m.right, h - m.top - m.bottom)
    } else {
        Viewport(w, h, widthMargin / 2, heightMargin / 2, w - widthMargin, h - heightMargin)
    }
    if (area.width <= 0 || area.height <= 0 || area.left < 0 || area.top < 0) return Viewport.full(w, h)
    val insets = ui?.takeIf { it.hasContentInsets() }?.contentInsets
        ?: ui?.takeIf { it.hasStableContentInsets() }?.stableContentInsets
    return if (insets == null) area else area.copy(insets = Edges(insets.left, insets.top, insets.right, insets.bottom))
}

fun VideoConfiguration.fps(): Int = if (frameRate == VideoFrameRateType.VIDEO_FPS_60) 60 else 30

fun VideoConfiguration.describe(): String {
    val (w, h) = size()
    val fps = fps()
    val ui = if (hasUiConfig()) ", ui margins ${uiConfig.margins.describe()} insets ${uiConfig.contentInsets.describe()}" else ""
    return "${w}x$h $fps fps, density $density, margins ${widthMargin}x$heightMargin$ui"
}

private fun aap_protobuf.service.media.shared.message.InsetsOuterClass.Insets.describe() = "$left/$top/$right/$bottom"
