package org.fossify.voicerecorder.audio

import java.io.Closeable

/** Offline SpeexDSP spectral speech denoising. No AGC, pitch or speed change.
 * Feed frame-aligned interleaved PCM, then append finish() output. Output can lag
 * input by ~20 ms, but the concatenated result has exactly the input length.
 * Keep one session per continuous region, and always close it (including cancel).
 */
class NativeNoiseReducer(sampleRate: Int, private val channels: Int) : Closeable {
    private var handle: Long = createNative(sampleRate, channels)
    private var finished = false

    @Synchronized
    fun process(samples: ShortArray): ShortArray {
        check(handle != 0L && !finished) { "Noise reduction session closed or finished" }
        require(samples.size <= CHUNK_SAMPLES && samples.size % channels == 0) { "PCM chunk must be channel-aligned and at most $CHUNK_SAMPLES samples" }
        checkCanceled()
        return try { processNative(handle, samples, false) } catch (error: Throwable) {
            close() // Never reuse a partially processed state after cancellation/failure.
            throw error
        }
    }

    @Synchronized
    fun finish(): ShortArray {
        check(handle != 0L && !finished) { "Noise reduction session closed or finished" }
        checkCanceled()
        return try { processNative(handle, shortArrayOf(), true).also { finished = true } } catch (error: Throwable) {
            close()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) { closeNative(handle); handle = 0L }
    }

    private external fun createNative(sampleRate: Int, channels: Int): Long
    private external fun processNative(handle: Long, samples: ShortArray, finish: Boolean): ShortArray
    private external fun closeNative(handle: Long)

    companion object {
        const val CHUNK_SAMPLES = 65_536
        init { System.loadLibrary("voice-dsp") }

        private fun checkCanceled() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Noise reduction canceled")
        }

        fun reduce(samples: ShortArray, sampleRate: Int, channels: Int): ShortArray {
            require(channels in 1..2 && samples.size % channels == 0) { "Expected mono or stereo PCM" }
            checkCanceled()
            val result = ShortArray(samples.size)
            var written = 0
            NativeNoiseReducer(sampleRate, channels).use { session ->
                var offset = 0
                while (offset < samples.size) {
                    val count = minOf(CHUNK_SAMPLES, samples.size - offset)
                    val block = session.process(samples.copyOfRange(offset, offset + count))
                    block.copyInto(result, written)
                    written += block.size
                    offset += count
                }
                val tail = session.finish()
                tail.copyInto(result, written)
                written += tail.size
            }
            check(written == result.size) { "Noise reduction output length mismatch" }
            return result
        }
    }
}
