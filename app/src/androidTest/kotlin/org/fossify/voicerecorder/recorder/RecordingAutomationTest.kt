package org.fossify.voicerecorder.recorder

import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.voicerecorder.audio.VoicePreset
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecordingAutomationTest {
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
        journal.timeline.accept(0,VoicePreset.NONE.defaults())
        journal.markDryPublished()
        val restored=RecordingAutomation.restore(source)
        assertNull(restored.render(context,"unused"))
        assertFalse(source.exists())
        assertFalse(File(source.path+".presets.json").exists())
    }
}
