package com.simoneluconi.betterauto.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class PcmQuantizerTest {
    private val format = PcmFormat(48_000, 2, 16)

    private class Sink(override val wantsSilence: Boolean) : AudioSink {
        override val format = PcmFormat(48_000, 2, 16)
        val chunks = mutableListOf<Pair<ByteArray, Boolean>>()
        override fun onPcm(data: ByteArray, timestampUs: Long, silent: Boolean) {
            chunks += data to silent
        }
    }

    @Test
    fun silenceIsDroppedUnlessTheSinkWantsIt() {
        val quiet = FloatArray(960)
        val idle = Sink(wantsSilence = false)
        PcmQuantizer(format).deliver(idle, quiet, quiet.size, 0)
        assertTrue(idle.chunks.isEmpty())

        val streaming = Sink(wantsSilence = true)
        PcmQuantizer(format).deliver(streaming, quiet, quiet.size, 0)
        assertEquals(1, streaming.chunks.size)
        assertTrue(streaming.chunks[0].second)
    }

    @Test
    fun soundAlwaysGoesOutDitheredWithinOneStep() {
        val samples = FloatArray(4800) { ((it % 200) - 100) / 400f }
        val sink = Sink(wantsSilence = false)
        PcmQuantizer(format).deliver(sink, samples, samples.size, 0)
        val (pcm, silent) = sink.chunks.single()
        assertFalse(silent)
        assertEquals(samples.size * 2, pcm.size)
        for (i in samples.indices) {
            val value = ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toInt()
            assertTrue("sample $i", abs(value - (samples[i] * 32767).roundToInt()) <= 1)
        }
    }

    @Test
    fun sixteenBitChunksAreCopiedOnlyWhenUsed() {
        val pcm = ByteArray(1920).also { it[100] = 0x40 } // one sample well above silence
        val sink = Sink(wantsSilence = false)
        PcmQuantizer.deliver16(sink, pcm, 1000, 0)
        assertEquals(1000, sink.chunks.single().first.size)

        PcmQuantizer.deliver16(sink, ByteArray(1920), 1920, 0)
        assertEquals(1, sink.chunks.size)
    }
}
