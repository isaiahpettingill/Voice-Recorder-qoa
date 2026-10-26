package org.fossify.voicerecorder.recorder

import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.voicerecorder.audio.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class StreamingCleanupTest {
    @Test fun cleanupStreamsPastEditorLimitAndPreservesDryFile() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val source = File.createTempFile("long-dry-", ".qoa", cache)
        val target = File.createTempFile("long-clean-", ".qoa", cache)
        try {
            // Already encoded silence keeps this regression fast while exceeding the editing cap.
            val block = QoaCodec.encode(PcmAudio(ShortArray(5120), 48000, 1)).copyOfRange(8, 8 + 2072)
            val blocks = AudioFiles.EDIT_SAMPLE_LIMIT / 5120 + 1
            RandomAccessFile(source, "rw").use { out ->
                out.writeInt(0x716f6166); out.writeInt(blocks * 5120)
                repeat(blocks) { out.write(block) }
            }
            val originalLength = source.length()
            RecordingAutomation.renderNoiseReduction(source, target, listOf(PresetSegment(0, EditOptions())))
            target.inputStream().use {
                val reader = QoaReader(it)
                assertTrue(reader.totalFrames > AudioFiles.EDIT_SAMPLE_LIMIT)
                var count = 0
                while (true) { val samples = reader.readFrame() ?: break; count += samples.size; assertTrue(samples.all { sample -> sample.toInt() == 0 }) }
                assertEquals(blocks * 5120, count)
            }
            assertEquals(originalLength, source.length())
        } finally { source.delete(); target.delete() }
    }

    @Test fun toggleBoundariesKeepExactLengthAndOffRegion() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val source = File.createTempFile("toggle-dry-", ".qoa", cache)
        val target = File.createTempFile("toggle-clean-", ".qoa", cache)
        try {
            val pcm = ShortArray(12001 * 2) { (it % 503 - 250).toShort() }
            source.outputStream().use { QoaCodec.write(PcmAudio(pcm, 44100, 2), it) }
            RecordingAutomation.renderNoiseReduction(source, target, listOf(
                PresetSegment(0, EditOptions()), PresetSegment(3333, EditOptions(noiseReduction=false)),
                PresetSegment(8888, EditOptions())))
            val result = target.inputStream().use { QoaCodec.read(it) }
            assertEquals(pcm.size, result.samples.size)
            assertEquals(44100, result.sampleRate)
            assertEquals(2, result.channels)
        } finally { source.delete(); target.delete() }
    }

    @Test fun cancellationDoesNotModifyOriginal() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val source = File.createTempFile("cancel-dry-", ".qoa", cache)
        val target = File.createTempFile("cancel-clean-", ".qoa", cache)
        try {
            source.writeBytes(QoaCodec.encode(PcmAudio(ShortArray(5120), 48000, 1)))
            val original = source.readBytes()
            Thread.currentThread().interrupt()
            try {
                RecordingAutomation.renderNoiseReduction(source, target, listOf(PresetSegment(0, EditOptions())))
                fail("Expected cancellation")
            } catch (_: InterruptedException) { } finally { Thread.interrupted() }
            assertArrayEquals(original, source.readBytes())
        } finally { Thread.interrupted(); source.delete(); target.delete() }
    }
}
