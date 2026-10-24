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

    /** The level applied to the last sample: a gain change is ramped over a chunk, never a click. */
    private var level = 1f

    /**
     * Hands the first [count] samples to [sink] as PCM. Silence the sink has no use for (no stream running)
     * is dropped before it costs a conversion: capture runs all session long, music or not.
     */
    fun deliver(sink: AudioSink, samples: FloatArray, count: Int, timestampUs: Long) {
        // Silence is judged before dither, so dither noise alone never counts as sound
        val silent = isSilent(samples, count)
        if (silent && !sink.wantsSilence) return
        applyGain(samples, count, sink.gain)
        sink.onPcm(quantize(samples, count), timestampUs, silent)
    }

    /** Scales the samples toward [target], moving linearly across this chunk from the previous level. */
    internal fun applyGain(samples: FloatArray, count: Int, target: Float) {
        if (level == 1f && target == 1f) return
        val start = level
        val step = if (count > 0) (target - start) / count else 0f
        for (i in 0 until count) samples[i] *= start + step * (i + 1)
        level = target
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
            val out = pcm.copyOf(length)
            // Rare fallback path: a plain scale, without the ramp
            val gain = sink.gain
            if (gain != 1f) scale16(out, gain)
            sink.onPcm(out, timestampUs, silent)
        }

        private fun scale16(pcm: ByteArray, gain: Float) {
            var i = 0
            while (i + 1 < pcm.size) {
                val sample = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                val scaled = (sample * gain).roundToInt()
                pcm[i] = scaled.toByte()
                pcm[i + 1] = (scaled shr 8).toByte()
                i += 2
            }
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
