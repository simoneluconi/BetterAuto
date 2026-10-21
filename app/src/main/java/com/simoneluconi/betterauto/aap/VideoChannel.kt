package com.simoneluconi.betterauto.aap

import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.SetupOuterClass.Setup
import aap_protobuf.service.media.shared.message.StartOuterClass.Start
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
import com.simoneluconi.betterauto.video.VideoEncoder
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Video sink of the head unit. Flow:
 *   phone -> SETUP(H.264 BP)            HU -> CONFIG(max_unacked, accepted config indices)
 *   phone -> START + VIDEO_FOCUS_REQUEST HU -> VIDEO_FOCUS_NOTIFICATION(PROJECTED)
 *   phone -> CODEC_CONFIG (SPS/PPS), then DATA frames; HU -> ACK per frame
 */
class VideoChannel(
    session: AapSession,
    channelId: Int,
    private val configs: List<VideoConfiguration>,
    private val projection: Projection,
) : ServiceChannel(session, channelId) {
    override val name = "video"

    private var config: VideoConfiguration? = null

    @Volatile
    private var focused = false
    private var encoder: VideoEncoder? = null

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

    override fun onOpened() {
        send(
            MediaMessageId.MEDIA_MESSAGE_SETUP_VALUE,
            Setup.newBuilder().setType(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP).build(),
        )
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
        val index = response.configurationIndicesList.firstOrNull() ?: 0
        val chosen = configs.getOrNull(index)
        if (response.status != Config.Status.STATUS_READY || chosen == null) {
            EventLog.log("Video: configuration rejected (${response.status}, index $index)")
            return
        }
        config = chosen
        window = Semaphore(response.maxUnacked.coerceAtLeast(1))
        EventLog.log("Video ready: ${chosen.describe()}, max_unacked=${response.maxUnacked}")

        send(
            MediaMessageId.MEDIA_MESSAGE_START_VALUE,
            Start.newBuilder().setSessionId(SESSION_ID).setConfigurationIndex(index).build(),
        )
        send(
            MediaMessageId.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE,
            VideoFocusRequestNotification.newBuilder().setMode(VideoFocusMode.VIDEO_FOCUS_PROJECTED).build(),
        )
        maybeStartStreaming()
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
            !focused -> running?.setPaused(true)
            running == null -> maybeStartStreaming()
            else -> running.setPaused(false)
        }
        // Nobody sees the launcher meanwhile: it stops drawing too, not only the encoder
        if (running != null) projection.setActive(focused)
    }

    @Synchronized
    private fun maybeStartStreaming() {
        val cfg = config ?: return
        if (closed || !focused || encoder != null) return

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
            onCodecConfig = { send(MediaMessageId.MEDIA_MESSAGE_CODEC_CONFIG_VALUE, it) },
            frameHeadroom = MEDIA_TIMESTAMP_BYTES,
            onFrame = ::sendFrame,
        )
        CarState.viewport.value = cfg.viewport()
        val density = if (cfg.density > 0) cfg.density else 160
        projection.start(newEncoder.inputSurface, width, height, density)
        active = this
        startWatchdog()
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
        watchdog?.interrupt()
        synchronized(this) {
            if (active === this) active = null
            encoder?.stop()
            encoder = null
        }
        projection.stop()
    }

    companion object {
        /** The video channel streaming to the car now, if any (used by debug tools). */
        @Volatile
        var active: VideoChannel? = null
            private set

        private const val SESSION_ID = 0
        private const val WINDOW_TIMEOUT_MS = 500L
        private const val WATCHDOG_PERIOD_MS = 1000L
        private const val STALL_MS = 3000L
        /** After a redraw a live pipeline answers within a frame or two. */
        private const val REDRAW_WAIT_MS = 1500L
        private const val MAX_RESTARTS = 3
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
