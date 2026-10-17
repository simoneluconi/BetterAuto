package com.simoneluconi.betterauto.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Float samples (Android's own mix format) to the car's bit depth, with TPDF dither instead of plain
 * rounding, so quiet passages keep their detail. Used by both ways of getting the phone's audio.
 */
class PcmQuantizer(format: PcmFormat) {
    private val bytesPerSample = format.bits / 8
    private val fullScale = ((1L shl (format.bits - 1)) - 1).toFloat()
    private val maxValue = fullScale.toInt()

    /** The first [count] samples as little-endian PCM, and whether they carry no sound at all. */
    fun quantize(samples: FloatArray, count: Int): Pair<ByteArray, Boolean> {
        val out = ByteArray(count * bytesPerSample)
        var peak = 0f
        for (i in 0 until count) {
            val s = samples[i].coerceIn(-1f, 1f)
            peak = max(peak, abs(s))
            // TPDF dither: sum of two uniform values, 2 LSB peak-to-peak, decorrelates the error from the music
            val dither = Random.nextFloat() - Random.nextFloat()
            val value = (s * fullScale + dither).roundToInt().coerceIn(-maxValue - 1, maxValue)
            val base = i * bytesPerSample
            for (b in 0 until bytesPerSample) out[base + b] = (value shr (8 * b)).toByte()
        }
        // Silence is judged before dither, so dither noise alone never counts as sound
        return out to (peak < SILENCE)
    }

    companion object {
        /** About -78 dBFS. */
        const val SILENCE = 4f / 32768f
    }
}
