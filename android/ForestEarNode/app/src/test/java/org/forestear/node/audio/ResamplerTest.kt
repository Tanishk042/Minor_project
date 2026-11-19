package org.forestear.node.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ResamplerTest {

    @Test
    fun identityJustScalesToFloat() {
        val resampler = StreamingResampler(16000, 16000)
        assertTrue(resampler.isIdentity)
        val out = resampler.process(shortArrayOf(0, 16384, -32768, 32767))
        assertEquals(4, out.size)
        assertEquals(0.0f, out[0], 1e-9f)
        assertEquals(0.5f, out[1], 1e-9f)
        assertEquals(-1.0f, out[2], 1e-9f)
        assertEquals(32767 / 32768.0f, out[3], 1e-9f)
    }

    @Test
    fun fortyEightKToSixteenKProducesOneThirdOfTheSamples() {
        val resampler = StreamingResampler(48000, 16000)
        assertFalse(resampler.isIdentity)
        val out = resampler.process(ShortArray(4800))
        assertTrue("got ${out.size} samples", out.size in 1599..1601)
    }

    @Test
    fun constantSignalStaysContinuousAcrossBlocks() {
        // A DC input must not produce a step at the block boundary; that is the
        // whole point of carrying `prevSample` between calls.
        val expected = 1000 / 32768.0f
        val resampler = StreamingResampler(48000, 16000)
        val block = ShortArray(4800) { 1000 }
        val first = resampler.process(block)
        val second = resampler.process(block)
        for (v in first) assertEquals(expected, v, 1e-6f)
        for (v in second) assertEquals(expected, v, 1e-6f)
    }

    @Test
    fun zeroCountReturnsEmptyArray() {
        val resampler = StreamingResampler(48000, 16000)
        assertEquals(0, resampler.process(ShortArray(16), 0).size)
        assertEquals(0, resampler.process(ShortArray(0)).size)
    }

    @Test
    fun resetMakesTheNextBlockMatchAFreshResampler() {
        val block = ShortArray(480) { ((it * 37) % 2000 - 1000).toShort() }
        val fresh = StreamingResampler(48000, 16000).process(block)
        val reused = StreamingResampler(48000, 16000)
        reused.process(ShortArray(960))
        reused.reset()
        assertArrayEquals(fresh, reused.process(block), 1e-6f)
    }

    @Test
    fun nearestSupportedRatePrefersTheClosestOnALogScale() {
        assertEquals(16000, StreamingResampler.nearestSupportedRate(16000, listOf(8000, 16000, 44100, 48000)))
        assertEquals(16000, StreamingResampler.nearestSupportedRate(16000, listOf(16000, 48000)))
        // 44100 is closer to 16000 than 48000 on a log scale.
        assertEquals(44100, StreamingResampler.nearestSupportedRate(16000, listOf(44100, 48000)))
        // 48000 is closer to 44100 than 16000.
        assertEquals(48000, StreamingResampler.nearestSupportedRate(44100, listOf(16000, 48000)))
    }

    @Test
    fun nearestSupportedRateFallsBackWhenNothingIsEnumerated() {
        assertEquals(16000, StreamingResampler.nearestSupportedRate(16000, emptyList()))
        assertEquals(16000, StreamingResampler.nearestSupportedRate(16000, listOf(0, -1)))
    }

    @Test
    fun candidateRatesStartWithTheRequestAndAreDistinct() {
        val candidates = StreamingResampler.candidateRates(16000, listOf(48000))
        assertEquals(16000, candidates.first())
        assertTrue(candidates.contains(48000))
        assertEquals(candidates.distinct(), candidates)
    }

    @Test
    fun rejectsNonPositiveRates() {
        for (bad in listOf(0, -1)) {
            val inputFailed = runCatching { StreamingResampler(bad, 16000) }.isFailure
            val outputFailed = runCatching { StreamingResampler(16000, bad) }.isFailure
            assertTrue("input rate $bad should be rejected", inputFailed)
            assertTrue("output rate $bad should be rejected", outputFailed)
        }
    }

    @Test
    fun wholeBufferHelperMatchesStreamingOneShot() {
        val block = ShortArray(4800) { (it % 256).toShort() }
        val helper = StreamingResampler.resample(block, 48000, 16000)
        val direct = StreamingResampler(48000, 16000).process(block)
        assertArrayEquals(direct, helper, 0f)
        assertTrue(abs(helper.size - 1600) <= 1)
    }
}
