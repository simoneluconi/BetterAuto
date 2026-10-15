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
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
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
            MediaMessageId.MEDIA_MESSAGE_ACK_VALUE -> {
                val ack = Ack.parseFrom(msg.payload)
                window.release(if (ack.hasAck()) ack.ack.coerceAtLeast(1) else 1)
            }
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
    }

    private fun maybeStartStreaming() {
        val cfg = config ?: return
        if (!focused || encoder != null) return

        val (width, height) = cfg.size()
        val fps = if (cfg.frameRate == VideoFrameRateType.VIDEO_FPS_60) 60 else 30
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
            onFrame = ::sendFrame,
        )
        CarState.viewport.value = cfg.viewport()
        val density = if (cfg.density > 0) cfg.density else 160
        projection.start(newEncoder.inputSurface, width, height, density)
    }

    private fun sendFrame(data: ByteArray, timestampUs: Long) {
        // The car is showing its own screen (reversing camera, its menus): nothing to decode there
        if (!focused) return
        // Wait for the head unit to catch up instead of flooding its decoder
        window.tryAcquire(WINDOW_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        val payload = ByteArray(8 + data.size)
        putU32(payload, 0, (timestampUs ushr 32).toInt())
        putU32(payload, 4, timestampUs.toInt())
        data.copyInto(payload, 8)
        send(MediaMessageId.MEDIA_MESSAGE_DATA_VALUE, payload)
        updateStats(data.size)
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
        encoder?.stop()
        encoder = null
        projection.stop()
    }

    private companion object {
        const val SESSION_ID = 0
        const val WINDOW_TIMEOUT_MS = 500L
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

fun VideoConfiguration.describe(): String {
    val (w, h) = size()
    val fps = if (frameRate == VideoFrameRateType.VIDEO_FPS_60) 60 else 30
    val ui = if (hasUiConfig()) ", ui margins ${uiConfig.margins.describe()} insets ${uiConfig.contentInsets.describe()}" else ""
    return "${w}x$h $fps fps, density $density, margins ${widthMargin}x$heightMargin$ui"
}

private fun aap_protobuf.service.media.shared.message.InsetsOuterClass.Insets.describe() = "$left/$top/$right/$bottom"
