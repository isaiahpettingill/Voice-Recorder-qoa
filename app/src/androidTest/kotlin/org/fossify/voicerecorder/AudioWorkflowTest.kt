package org.fossify.voicerecorder

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import org.fossify.voicerecorder.activities.EditSoundActivity
import org.fossify.voicerecorder.audio.*
import org.fossify.voicerecorder.recorder.QoaRecorder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AudioWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun signal() = PcmAudio(ShortArray(16000) { (sin(it * 0.17) * 8000).toInt().toShort() }, 16000, 1)

    @Test fun qoaPlaybackAndLegacyWavEditing() {
        val qoa = File.createTempFile("fixture", ".qoa", context.cacheDir)
        val wav = File.createTempFile("fixture", ".wav", context.cacheDir)
        try {
            val source = signal()
            qoa.outputStream().use { QoaCodec.write(source, it) }
            assertTrue(AudioFiles.isQoa(context, qoa.path))
            val playback = AudioFiles.playbackFile(context, qoa.path)
            try {
                assertEquals(source.samples.size * 2L + 44, playback.length())
                val decoded = AudioFiles.readForEditing(context, playback.path)
                assertEquals(16000, decoded.sampleRate)
                assertEquals(source.samples.size, decoded.samples.size)
            } finally { playback.delete() }
            AudioFiles.writeWav(source, wav)
            assertFalse(AudioFiles.isQoa(context, wav.path))
            val legacy = AudioFiles.readForEditing(context, wav.path)
            assertArrayEquals(source.samples, legacy.samples)
        } finally { qoa.delete(); wav.delete() }
    }

    @Test fun editPreviewResetCloseLeavesOriginalUnchanged() {
        val qoa = File.createTempFile("edit-fixture", ".qoa", context.cacheDir)
        val bytes = QoaCodec.encode(signal())
        qoa.writeBytes(bytes)
        try {
            ActivityScenario.launch<EditSoundActivity>(Intent(context, EditSoundActivity::class.java).putExtra("path", qoa.path)).use { scenario ->
                // Rendering is explicitly asynchronous; wait for the background loader.
                var loaded = false
                repeat(100) {
                    scenario.onActivity { activity ->
                        loaded = activity.findViewById<android.view.View>(android.R.id.content)
                            .let { root -> containsText(root, "Original stays unchanged.") }
                    }
                    if (!loaded) Thread.sleep(50)
                }
                assertTrue("Audio loads into editor", loaded)
                onView(withText("Preview")).perform(click())
                Thread.sleep(300)
                onView(withText("Stop preview")).perform(click())
                onView(withContentDescription("More editing options")).perform(click())
                onView(withText("Reset")).perform(click())
                onView(withText("Back")).perform(click())
            }
            assertArrayEquals(bytes, qoa.readBytes())
        } finally { qoa.delete() }
    }
    private fun containsText(view: android.view.View, text: String): Boolean {
        if (view is android.widget.TextView && view.text.contains(text)) return true
        return view is android.view.ViewGroup && (0 until view.childCount).any { containsText(view.getChildAt(it), text) }
    }

    @Test fun launcherAliasResolvesForForkId() {
        val alias = android.content.ComponentName(context.packageName,
            "us.josemancharo.voicerecorderqoa.activities.SplashActivity.Green")
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(alias,
            android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
        assertEquals("org.fossify.voicerecorder.activities.SplashActivity", info.targetActivity)
    }

    /** Keep an actual app Activity resumed while exercising privacy-gated microphone capture. */
    private fun withForegroundMicrophone(block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.RECORD_AUDIO"
        )).use { it.readBytes() }
        val fixture = File.createTempFile("foreground", ".qoa", context.cacheDir)
        fixture.writeBytes(QoaCodec.encode(signal()))
        try {
            ActivityScenario.launch<EditSoundActivity>(Intent(context, EditSoundActivity::class.java)
                .putExtra("path", fixture.path)).use { scenario ->
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                instrumentation.waitForIdleSync()
                block()
            }
        } finally { fixture.delete() }
    }
    private fun recoveries(): Set<File> = File(context.filesDir, "recording-recovery")
        .listFiles()?.toSet() ?: emptySet()

    @Test fun microphonePauseResumeProducesPlayableQoa() = withForegroundMicrophone {
        val before = recoveries()
        val target = File.createTempFile("mic-fixture", ".qoa", context.cacheDir)
        val recorder = QoaRecorder(context)
        try {
            recorder.setOutputFile(target.path)
            recorder.prepare(); recorder.start()
            Thread.sleep(500)
            recorder.pause(); Thread.sleep(200); recorder.resume(); Thread.sleep(500)
            recorder.stop(); recorder.release()
            assertTrue(AudioFiles.isQoa(context, target.path))
            val audio = AudioFiles.readForEditing(context, target.path)
            assertTrue(audio.samples.isNotEmpty())
            assertEquals("Successful save removes its recovery file", before, recoveries())
        } finally {
            recorder.discard(); runCatching { recorder.release() }
            target.delete(); (recoveries() - before).forEach { it.delete() }
        }
    }

    @Test fun cancelDiscardsCapturedAudioWithoutPublishingOutput() = withForegroundMicrophone {
        val before = recoveries()
        val target = File(context.cacheDir, "canceled-${System.nanoTime()}.qoa")
        val recorder = QoaRecorder(context)
        try {
            recorder.setOutputFile(target.path)
            recorder.prepare(); recorder.start(); Thread.sleep(500)
            recorder.discard(); recorder.stop(); recorder.release()
            assertFalse("Cancel must not publish an output", target.exists())
            assertEquals("Cancel removes its private recording", before, recoveries())
        } finally {
            recorder.discard(); runCatching { recorder.release() }
            target.delete(); (recoveries() - before).forEach { it.delete() }
        }
    }

    @Test fun failedOutputPreservesPlayableRecovery() = withForegroundMicrophone {
        val before = recoveries()
        // A directory is deterministically unwritable as a regular output file.
        val directory = File(context.cacheDir, "invalid-output-${System.nanoTime()}").apply { mkdir() }
        val recorder = QoaRecorder(context)
        try {
            recorder.setOutputFile(directory.path)
            recorder.prepare(); recorder.start(); Thread.sleep(500); recorder.stop()
            val failure = runCatching { recorder.release() }.exceptionOrNull()
            assertNotNull("Final output failure must be reported", failure)
            val retained = recoveries() - before
            assertEquals("The sole captured copy must survive", 1, retained.size)
            val recovered = retained.single().inputStream().use { QoaCodec.read(it) }
            assertTrue("Recovery contains valid recorded samples", recovered.samples.isNotEmpty())
        } finally {
            recorder.discard(); runCatching { recorder.release() }
            directory.delete(); (recoveries() - before).forEach { it.delete() }
        }
    }
}
