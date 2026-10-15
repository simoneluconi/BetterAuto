package com.simoneluconi.betterauto.car

import kotlinx.coroutines.flow.MutableStateFlow

/** Live data coming from the head unit, observed by the UI. */
object CarState {
    /** Night mode reported by the car's sensor, null until the car tells us. */
    val nightMode = MutableStateFlow<Boolean?>(null)

    val video = MutableStateFlow(VideoStats())

    /** Car audio format and state, e.g. "PCM 48000 Hz 16-bit 2ch · streaming". Null without an audio channel. */
    val audio = MutableStateFlow<String?>(null)

    /** Short description of the link, e.g. "USB · TLSv1.2". Null when disconnected. */
    val link = MutableStateFlow<String?>(null)

    /** Visible area of the video frame negotiated with the head unit. Null before the video starts. */
    val viewport = MutableStateFlow<Viewport?>(null)

    /** Development: pretend the car screen has this shape ("9:16", "1280x480"), inside the real frame. */
    val simulatedScreen = MutableStateFlow<String?>(null)

    /** The area the launcher uses in a [frameWidth] x [frameHeight] frame. */
    fun viewportFor(frameWidth: Int, frameHeight: Int, real: Viewport?, simulated: String?): Viewport {
        simulated?.let { parseScreen(frameWidth, frameHeight, it) }?.let { return it }
        return real?.takeIf { it.frameWidth == frameWidth && it.frameHeight == frameHeight }
            ?: Viewport.full(frameWidth, frameHeight)
    }

    fun viewportFor(frameWidth: Int, frameHeight: Int) =
        viewportFor(frameWidth, frameHeight, viewport.value, simulatedScreen.value)

    private fun parseScreen(frameWidth: Int, frameHeight: Int, spec: String): Viewport? {
        val parts = spec.split(':', 'x').mapNotNull { it.trim().toFloatOrNull() }
        if (parts.size != 2 || parts[0] <= 0f || parts[1] <= 0f) return null
        return if (':' in spec) Viewport.aspect(frameWidth, frameHeight, parts[0] / parts[1])
        else Viewport.centered(frameWidth, frameHeight, parts[0].toInt(), parts[1].toInt())
    }

    fun reset() {
        nightMode.value = null
        video.value = VideoStats()
        audio.value = null
        link.value = null
        viewport.value = null
    }
}

data class VideoStats(
    val width: Int = 0,
    val height: Int = 0,
    val sentFps: Double = 0.0,
    val kbps: Long = 0,
    val maxUnacked: Int = 0,
    val encoder: String = "",
)
