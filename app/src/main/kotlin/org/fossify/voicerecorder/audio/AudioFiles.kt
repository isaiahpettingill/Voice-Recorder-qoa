package org.fossify.voicerecorder.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder

/** Android decoding and disk-backed playback; QOA never goes through a platform codec. */
object AudioFiles {
    const val EDIT_SAMPLE_LIMIT = 8_000_000
    fun open(context: Context, path: String): InputStream =
        if (path.startsWith("content:") || path.startsWith("file:"))
            checkNotNull(context.contentResolver.openInputStream(Uri.parse(path))) { "Cannot open recording." }
        else File(path).inputStream()

    fun isQoa(context: Context, path: String): Boolean = open(context, path).use {
        val magic = ByteArray(4)
        var count = 0
        while (count < magic.size) { val read = it.read(magic, count, magic.size - count); if (read < 0) break; count += read }
        magic.contentEquals(byteArrayOf(113, 111, 97, 102))
    }

    fun qoaDuration(context: Context, path: String): Int = open(context, path).use {
        val reader = QoaReader(it)
        ((reader.totalFrames.toLong() + reader.sampleRate - 1) / reader.sampleRate).toInt()
    }

    fun playbackFile(context: Context, path: String): File {
        val target = File.createTempFile("qoa-play-", ".wav", context.cacheDir)
        try {
            open(context, path).use { stream ->
                val reader = QoaReader(stream)
                RandomAccessFile(target, "rw").use { output ->
                    writeWavHeader(output, reader.totalFrames.toLong() * reader.channels * 2, reader.sampleRate, reader.channels)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val frame = reader.readFrame() ?: break
                        output.write(pcmBytes(frame))
                    }
                }
            }
            return target
        } catch (error: Exception) { target.delete(); throw error }
    }

    fun readForEditing(context: Context, path: String): PcmAudio {
        if (isQoa(context, path)) return open(context, path).use { QoaCodec.read(it, EDIT_SAMPLE_LIMIT) }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (path.startsWith("content:") || path.startsWith("file:")) extractor.setDataSource(context, Uri.parse(path), null)
            else extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No supported audio track found.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(channels in 1..2) { "Editing supports mono or stereo audio." }
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                require(format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 * rate * channels <= EDIT_SAMPLE_LIMIT) {
                    "This recording is too long for editing. Shorter clips are supported; playback is not limited."
                }
            }
            codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec.configure(format, null, null, 0)
            codec.start()
            val bytes = ByteArrayOutputStream()
            var inputDone = false
            var outputDone = false
            var idle = 0
            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(index))
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = codec.outputFormat
                    rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(channels in 1..2)
                    require(!output.containsKey(MediaFormat.KEY_PCM_ENCODING) || output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) { "Decoder did not produce 16-bit PCM." }
                } else if (index >= 0) {
                    idle = 0
                    val buffer = checkNotNull(codec.getOutputBuffer(index))
                    require(bytes.size().toLong() + info.size <= EDIT_SAMPLE_LIMIT * 2L) { "This recording is too long for editing. Playback is not limited." }
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val chunk = ByteArray(info.size)
                    buffer.get(chunk)
                    bytes.write(chunk)
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                } else {
                    check(++idle < 1000) { "Audio decoder timed out." }
                }
            }
            val raw = bytes.toByteArray()
            require(raw.size % (2 * channels) == 0 && raw.isNotEmpty()) { "Invalid PCM audio." }
            val samples = ShortArray(raw.size / 2)
            java.nio.ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            return PcmAudio(samples, rate, channels)
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            codec?.release(); extractor.release()
        }
    }

    fun writeWav(audio: PcmAudio, target: File) {
        RandomAccessFile(target, "rw").use {
            it.setLength(0)
            writeWavHeader(it, audio.samples.size * 2L, audio.sampleRate, audio.channels)
            // Keep byte staging bounded even for large editable clips.
            for (start in audio.samples.indices step 8192) {
                it.write(pcmBytes(audio.samples.copyOfRange(start, minOf(start + 8192, audio.samples.size))))
            }
        }
    }

    private fun pcmBytes(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample -> bytes[index * 2] = sample.toByte(); bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte() }
    }
    private fun writeWavHeader(output: RandomAccessFile, bytes: Long, rate: Int, channels: Int) {
        require(bytes in 1..(0xffffffffL - 36)) { "Audio exceeds WAV playback size limit." }
        fun int(value: Long) { repeat(4) { output.write((value shr (8 * it)).toInt() and 255) } }
        fun short(value: Int) { output.write(value and 255); output.write(value shr 8 and 255) }
        output.writeBytes("RIFF"); int(bytes + 36); output.writeBytes("WAVEfmt "); int(16)
        short(1); short(channels); int(rate.toLong()); int(rate.toLong() * channels * 2)
        short(channels * 2); short(16); output.writeBytes("data"); int(bytes)
    }
}
