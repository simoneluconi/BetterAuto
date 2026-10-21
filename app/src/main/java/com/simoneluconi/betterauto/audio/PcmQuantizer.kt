package com.simoneluconi.betterauto.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Float samples (Android's own mix format) to the car's bit depth, with TPDF dither instead of plain
 * rounding, so quiet passages keep their detail. Used by both ways of getting the phone's audio.
 */
class PcmQuantizer(format: PcmFormat) {
    private val bytesPerSample = format.bits / 8
    private val fullScale = ((1L shl (format.bits - 1)) - 1).toFloat()
    private val maxValue = fullScale.toInt()
    private var seed = System.nanoTime().toInt() or 1

    /**
     * Hands the first [count] samples to [sink] as PCM. Silence the sink has no use for (no stream running)
     * is dropped before it costs a conversion: capture runs all session long, music or not.
     */
    fun deliver(sink: AudioSink, samples: FloatArray, count: Int, timestampUs: Long) {
        // Silence is judged before dither, so dither noise alone never counts as sound
        val silent = isSilent(samples, count)
        if (silent && !sink.wantsSilence) return
        sink.onPcm(quantize(samples, count), timestampUs, silent)
    }

    /** The first [count] samples as little-endian PCM. */
    fun quantize(samples: FloatArray, count: Int): ByteArray {
        val out = ByteArray(count * bytesPerSample)
        for (i in 0 until count) {
            val s = samples[i].coerceIn(-1f, 1f)
            // TPDF dither: sum of two uniform values, 2 LSB peak-to-peak, decorrelates the error from the music
            val dither = nextUniform() - nextUniform()
            val value = (s * fullScale + dither).roundToInt().coerceIn(-maxValue - 1, maxValue)
            val base = i * bytesPerSample
            for (b in 0 until bytesPerSample) out[base + b] = (value shr (8 * b)).toByte()
        }
        return out
    }

    /** Uniform in [0, 1) from a xorshift: far cheaper than Random at two values per sample, and enough for dither. */
    private fun nextUniform(): Float {
        var x = seed
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        seed = x
        return (x ushr 8) * (1f / (1 shl 24))
    }

    companion object {
        /** About -78 dBFS. */
        const val SILENCE = 4f / 32768f

        fun isSilent(samples: FloatArray, count: Int): Boolean {
            for (i in 0 until count) if (abs(samples[i]) >= SILENCE) return false
            return true
        }

        /** [deliver] for 16-bit PCM the phone converted itself: [length] bytes of [pcm], copied only when used. */
        fun deliver16(sink: AudioSink, pcm: ByteArray, length: Int, timestampUs: Long) {
            val silent = isSilent16(pcm, length)
            if (silent && !sink.wantsSilence) return
            sink.onPcm(pcm.copyOf(length), timestampUs, silent)
        }

        private fun isSilent16(pcm: ByteArray, length: Int): Boolean {
            val threshold = SILENCE * 32768f
            var i = 0
            while (i + 1 < length) {
                val sample = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                if (abs(sample) >= threshold) return false
                i += 2
            }
            return true
        }
    }
}
