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

    fun reset() {
        nightMode.value = null
        video.value = VideoStats()
        audio.value = null
        link.value = null
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
