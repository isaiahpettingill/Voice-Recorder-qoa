package org.fossify.voicerecorder.recorder

import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.voicerecorder.audio.VoicePreset
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecordingAutomationTest {
    @Test fun automaticFilenameSurvivesRecoveryAndClearingCustomName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("automatic-name-", ".qoa", context.cacheDir)
        val journal = RecordingAutomation(source, 48000, "2026-10-26 Voice.qoa")
        try {
            journal.captureSettings(0, VoicePreset.NONE.defaults(), "Custom")
            val restored = RecordingAutomation.restore(source)
            restored.persistName("")
            assertEquals("2026-10-26 Voice.qoa", restored.recordingName)
            assertEquals("2026-10-26 Voice.qoa", RecordingAutomation.restore(source).recordingName)
        } finally { journal.delete(); source.delete() }
    }

    @Test fun pausedNameChangesDoNotCreateAudioSegmentsAndNoiseChoiceRestores() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("name-journal-", ".qoa", context.cacheDir)
        val journal = RecordingAutomation(source, 48000)
        try {
            journal.captureSettings(0, VoicePreset.NONE.defaults(), "First take")
            journal.captureSettings(48000, VoicePreset.NONE.defaults().copy(noiseReduction = false), "Second take")
            // Paused capture accepts no more samples. Renaming must persist without inventing a segment.
            journal.persistName("Final take.qoa")
            val restored = RecordingAutomation.restore(source)
            assertEquals("Final take.qoa", restored.recordingName)
            assertEquals(listOf(0L, 48000L), restored.timeline.segments.map { it.frame })
            assertTrue(restored.timeline.segments.first().options.noiseReduction)
            assertFalse(restored.timeline.segments.last().options.noiseReduction)
            assertEquals(1.0, restored.timeline.segments.last().options.speed, 0.0)
        } finally { journal.delete(); source.delete() }
    }

    @Test fun missingNoiseChoiceMigratesPreferencesButNotHistoricalJournals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("recording-presets", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getString("options", null)
        try {
            prefs.edit().putString("options", "{\"preset\":\"DEEP\",\"pitch\":-4.0}").commit()
            val migrated = RecordingPresetSettings.load(context)
            assertTrue(migrated.noiseReduction)
            assertEquals(-4.0, migrated.pitchSemitones, 0.0)
            assertFalse(RecordingPresetSettings.decode(org.json.JSONObject("{}" )).noiseReduction)
            RecordingPresetSettings.save(context, migrated.copy(noiseReduction = false))
            assertFalse(RecordingPresetSettings.load(context).noiseReduction)
        } finally {
            if (previous == null) prefs.edit().remove("options").commit()
            else prefs.edit().putString("options", previous).commit()
        }
    }

    @Test fun settingsAndSampleJournalSurviveRecreation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val original=RecordingPresetSettings.load(context)
        val source=File.createTempFile("automation-test-", ".qoa",context.cacheDir)
        val journal=RecordingAutomation(source,48000)
        try {
            val selected=VoicePreset.CHIPMUNK.defaults().copy(speed=.8,effectMix=.25)
            RecordingPresetSettings.save(context,selected)
            assertEquals(selected,RecordingPresetSettings.load(context))
            journal.timeline.accept(0,VoicePreset.NONE.defaults())
            journal.timeline.accept(5120,selected)
            journal.persist()
            val restored=RecordingAutomation.restore(source)
            assertEquals(journal.timeline.segments,restored.timeline.segments)
            assertTrue(restored.timeline.hasEffects)
        } finally { RecordingPresetSettings.save(context,original);journal.delete();source.delete() }
    }
    @Test fun renderFailureRetainsDrySourceAndJournal() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File.createTempFile("automation-test-", ".qoa",context.cacheDir)
        source.writeBytes(byteArrayOf(1,2,3))
        val journal=RecordingAutomation(source,48000)
        journal.timeline.accept(0,VoicePreset.DEEP.defaults());journal.persist()
        try {
            runCatching { journal.render(context,"invalid-folder") }
            assertTrue(source.exists())
            assertTrue(RecordingAutomation.restore(source).timeline.hasEffects)
        } finally { journal.delete();source.delete() }
    }
    @Test fun publishedCleanRecoveryCompletesInterruptedCleanup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File.createTempFile("automation-clean-", ".qoa",context.cacheDir)
        val journal=RecordingAutomation(source,48000)
        journal.timeline.accept(0,VoicePreset.NONE.defaults().copy(noiseReduction=false))
        journal.markDryPublished()
        val restored=RecordingAutomation.restore(source)
        assertNull(restored.render(context,"unused"))
        assertFalse(source.exists())
        assertFalse(File(source.path+".presets.json").exists())
    }
}
