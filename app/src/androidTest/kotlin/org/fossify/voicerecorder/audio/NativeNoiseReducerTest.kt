package org.fossify.voicerecorder.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

class NativeNoiseReducerTest {
    @Test fun silenceOddTailsRatesAndStereoPreserveExactLength() {
        for (rate in listOf(8000, 16000, 22050, 44100, 48000, 96000)) {
            for (channels in 1..2) {
                for (frames in listOf(0, 1, rate / 50 - 1, rate / 50 + 1, rate + 17)) {
                    val input = ShortArray(frames * channels)
                    assertArrayEquals(input, NativeNoiseReducer.reduce(input, rate, channels))
                }
            }
        }
    }

    @Test fun streamingChunksMatchWholeBufferAndChannelsStayIndependent() {
        val rate = 16000
        val random = Random(42)
        val mono = ShortArray(rate * 3 + 17) { (random.nextInt(2001) - 1000).toShort() }
        val original = mono.copyOf()
        val expected = NativeNoiseReducer.reduce(mono, rate, 1)
        val stereo = ShortArray(mono.size * 2) { if (it % 2 == 0) mono[it / 2] else 0 }
        val actual = ArrayList<Short>()
        NativeNoiseReducer(rate, 2).use { session ->
            var start = 0
            while (start < stereo.size) {
                val end = minOf(start + 238, stereo.size)
                actual.addAll(session.process(stereo.copyOfRange(start, end)).toList())
                start = end
            }
            actual.addAll(session.finish().toList())
        }
        assertEquals(stereo.size, actual.size)
        for (i in mono.indices) { assertEquals(expected[i], actual[2 * i]); assertEquals(0.toShort(), actual[2 * i + 1]) }
        assertArrayEquals(original, mono)
        fun energy(a: ShortArray, start: Int) = a.drop(start).sumOf { it.toDouble() * it } / (a.size - start)
        assertTrue("Stationary noise should be attenuated", energy(expected, rate) < energy(mono, rate) * 0.3)
    }

    @Test fun spectralDenoiserPreservesVoicedSignalAndDoesNotShiftTime() {
        val rate = 16000
        val noise = Random(7)
        // A stationary sine becomes background hum to an adaptive denoiser.
        // Use a synthetic changing-pitch harmonic/syllabic fixture instead; this
        // is a regression signal, not a real-speech or perceptual benchmark.
        val clean = ShortArray(rate * 4)
        val input = ShortArray(clean.size) { i ->
            val t = i.toDouble() / rate
            val envelope = 0.3 + 0.7 * sin(2 * PI * 2 * t).pow(2)
            val phase = 130 * t + 3 * (1 - cos(5 * t))
            val voice = if (i < rate) 0.0 else (1..8).sumOf { harmonic ->
                6500 * envelope * sin(2 * PI * harmonic * phase) / harmonic
            }
            clean[i] = voice.roundToInt().toShort()
            (voice + noise.nextInt(2001) - 1000).roundToInt().toShort()
        }
        val output = NativeNoiseReducer.reduce(input, rate, 1)
        var projection = 0.0
        var cleanEnergy = 0.0
        for (i in rate * 2 until rate * 3) {
            projection += output[i].toDouble() * clean[i]
            cleanEnergy += clean[i].toDouble() * clean[i]
        }
        // Projection rejects silence, uncorrelated noise and shifted speech;
        // reference Speex 1.2.1 host result is 0.711 (not a listening benchmark).
        assertTrue("Changing voiced harmonics should be preserved: ${projection / cleanEnergy}",
            projection / cleanEnergy > 0.60)
        val impulse = ShortArray(rate); impulse[rate / 2] = 30000
        val transient = NativeNoiseReducer.reduce(impulse, rate, 1)
        assertEquals(rate / 2, transient.indices.maxBy { transient[it] })
    }

    @Test fun cancelCloseAndRepeatedFinishAreSafe() {
        val result = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                NativeNoiseReducer(48000, 1).use { session ->
                    Thread.currentThread().interrupt()
                    session.process(ShortArray(4096))
                }
            } catch (error: Throwable) { result.set(error) }
        }
        thread.start(); thread.join(5000)
        assertFalse(thread.isAlive)
        assertTrue(result.get() is InterruptedException)
        val session = NativeNoiseReducer(48000, 1)
        session.process(shortArrayOf(12)); assertEquals(1, session.finish().size)
        try { session.finish(); fail("Repeated finish must fail") } catch (_: IllegalStateException) { }
        session.close(); session.close()
        try { session.process(shortArrayOf(12)); fail("Closed session must fail") } catch (_: IllegalStateException) { }
    }

    @Test fun longStreamsHaveNoEditorSizeLimit() {
        NativeNoiseReducer(48000, 1).use { session ->
            val block = ShortArray(8192)
            var received = 0L
            repeat(1025) { received += session.process(block).size }
            received += session.finish().size
            assertEquals(1025L * block.size, received) // >8M samples without whole-input allocation
        }
    }
}
