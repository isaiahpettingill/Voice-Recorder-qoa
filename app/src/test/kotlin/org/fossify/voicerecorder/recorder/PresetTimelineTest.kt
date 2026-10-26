package org.fossify.voicerecorder.recorder

import org.fossify.voicerecorder.audio.EditOptions
import org.fossify.voicerecorder.audio.VoicePreset
import org.junit.Assert.*
import org.junit.Test
import org.fossify.voicerecorder.audio.QoaWriter
import org.fossify.voicerecorder.audio.QoaCodec
import java.io.File
import java.io.RandomAccessFile

class PresetTimelineTest {
    @Test fun defaultNoiseReductionCountsAsAnEffect() {
        val timeline = PresetTimeline()
        timeline.accept(0, EditOptions())
        assertTrue(timeline.hasEffects)
    }
    @Test fun unchangedChunksDoNotResetLibraryEffectState() {
        val timeline=PresetTimeline()
        assertTrue(timeline.accept(0,VoicePreset.CHIPMUNK.defaults()))
        assertFalse(timeline.accept(5120,VoicePreset.CHIPMUNK.defaults()))
        assertEquals(1,timeline.segments.size)
    }
    @Test fun pausedChangesUseNextAcceptedSampleNotWallClock() {
        val timeline=PresetTimeline()
        timeline.accept(0,EditOptions())
        timeline.accept(5120,VoicePreset.DEEP.defaults())
        // While paused the accepted frame position does not advance.
        timeline.accept(5120,VoicePreset.CHIPMUNK.defaults())
        assertEquals(listOf(0L,5120L),timeline.segments.map { it.frame })
        assertEquals(VoicePreset.CHIPMUNK,timeline.segments.last().options.preset)
    }
    @Test fun cleanOnlyDoesNotNeedAnEffectsCopy() {
        val timeline=PresetTimeline(); timeline.accept(0,EditOptions(noiseReduction=false))
        assertFalse(timeline.hasEffects)
        timeline.accept(100,EditOptions(gainDb=2.0)); assertTrue(timeline.hasEffects)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsBackwardPositions() {
        val timeline=PresetTimeline();timeline.accept(20,EditOptions());timeline.accept(10,VoicePreset.DEEP.defaults())
    }
    @Test fun switchingBackToCleanRetainsPriorEffectSegment() {
        val timeline=PresetTimeline();timeline.accept(0,VoicePreset.DEEP.defaults());timeline.accept(6000,EditOptions(noiseReduction=false))
        assertTrue(timeline.hasEffects); assertEquals(2,timeline.segments.size)
    }
    @Test fun processDeathRecoversCompleteFramesWithoutChangingOriginal() {
        val folder=java.nio.file.Files.createTempDirectory("recording-recovery-test").toFile()
        val source=File(folder,"interrupted.qoa")
        try {
            RandomAccessFile(source,"rw").use { file ->
                val writer=QoaWriter(file,48000,1)
                writer.append(ShortArray(5120) { 100 })
                writer.append(ShortArray(100) { 200 }) // pending PCM cannot survive process death
                file.write(byteArrayOf(1,2,3)) // interrupted next frame
            }
            val original=source.readBytes()
            val recovered=RecordingAutomation.recoverCompleteFrames(source,folder)
            try {
                assertEquals(5120,recovered.inputStream().use { QoaCodec.read(it) }.frames)
                assertArrayEquals(original,source.readBytes())
            } finally { recovered.delete() }
        } finally { folder.deleteRecursively() }
    }
}
