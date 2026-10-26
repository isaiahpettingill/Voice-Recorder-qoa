package org.fossify.voicerecorder

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.voicerecorder.activities.EditSoundActivity
import org.fossify.voicerecorder.audio.PcmAudio
import org.fossify.voicerecorder.audio.QoaCodec
import org.fossify.voicerecorder.audio.VoicePreset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VoicePresetControlsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }
    private fun views(activity: EditSoundActivity) = descendants(activity.findViewById(android.R.id.content))
    private fun slider(activity: EditSoundActivity, title: String) = views(activity).filterIsInstance<SeekBar>()
        .single { it.contentDescription == title }
    private fun preset(activity: EditSoundActivity) = views(activity).filterIsInstance<Spinner>().single()
    private fun button(activity: EditSoundActivity, title: String) = views(activity).filterIsInstance<Button>()
        .single { it.text.toString() == title }

    @Test fun presetTuningSurvivesRecreationAndResetRestoresDefaults() {
        val file = File.createTempFile("preset-controls-", ".qoa", context.cacheDir)
        val bytes = QoaCodec.encode(PcmAudio(ShortArray(16000) { if (it % 80 < 40) 3000 else -3000 }, 16000, 1))
        file.writeBytes(bytes)
        try {
            ActivityScenario.launch<EditSoundActivity>(Intent(context, EditSoundActivity::class.java)
                .putExtra("path", file.path)).use { scenario ->
                var loaded = false
                for (attempt in 0 until 100) {
                    scenario.onActivity { activity ->
                        loaded = views(activity).filterIsInstance<TextView>().any { it.text.contains("Original stays unchanged") }
                    }
                    if (loaded) break
                    Thread.sleep(50)
                }
                assertTrue("Editor loads fixture", loaded)
                ScreenshotEvidence.capture("editor-default")
                scenario.onActivity { preset(it).setSelection(VoicePreset.FEMININE.ordinal) }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(33, slider(it, "Pitch").progress) // +4.5 semitones
                    assertEquals(28, slider(it, "Voice resonance (formants)").progress) // +2 semitones
                    assertFalse("No creative mix on voice-only preset", slider(it, "Effect mix").isEnabled)
                    slider(it, "Pitch").progress = 31
                    slider(it, "Voice resonance (formants)").progress = 27
                    slider(it, "Volume").progress = 20
                    slider(it, "Speed").progress = 65
                }
                scenario.recreate()
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(VoicePreset.FEMININE.ordinal, preset(it).selectedItemPosition)
                    assertEquals(31, slider(it, "Pitch").progress)
                    assertEquals(27, slider(it, "Voice resonance (formants)").progress)
                    assertEquals(20, slider(it, "Volume").progress)
                    assertEquals(65, slider(it, "Speed").progress)
                    button(it, "Reset voice preset").performClick()
                    assertEquals(33, slider(it, "Pitch").progress)
                    assertEquals(28, slider(it, "Voice resonance (formants)").progress)
                    assertEquals(50, slider(it, "Speed").progress)
                    assertEquals("Preset reset retains volume", 20, slider(it, "Volume").progress)
                    preset(it).setSelection(VoicePreset.EIGHTIES.ordinal)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertTrue(slider(it, "Effect mix").isEnabled)
                    assertEquals(50, slider(it, "Effect mix").progress)
                    slider(it, "Effect mix").progress = 20
                    views(it).filterIsInstance<Button>().single { button -> button.contentDescription == "More editing options" }.performClick()
                }
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Reset"))
                    .perform(androidx.test.espresso.action.ViewActions.click())
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(VoicePreset.NONE.ordinal, preset(it).selectedItemPosition)
                    assertEquals(24, slider(it, "Pitch").progress)
                    assertEquals(24, slider(it, "Voice resonance (formants)").progress)
                    assertEquals(24, slider(it, "Volume").progress)
                    assertEquals(0, slider(it, "Effect mix").progress)
                    assertFalse(slider(it, "Effect mix").isEnabled)
                }
            }
            assertArrayEquals("Preset controls never overwrite source", bytes, file.readBytes())
        } finally { file.delete() }
    }
    @Test fun soundEffectsAreDiscoverableAndTuningRestores() {
        val file = File.createTempFile("effects-ui-", ".qoa", context.cacheDir)
        file.writeBytes(QoaCodec.encode(PcmAudio(ShortArray(16000), 16000, 1)))
        try {
            ActivityScenario.launch<EditSoundActivity>(Intent(context, EditSoundActivity::class.java)
                .putExtra("path", file.path)).use { scenario ->
                scenario.onActivity { activity ->
                    assertFalse("Tuning starts collapsed", slider(activity, "Pitch").isShown)
                    assertEquals(1, views(activity).filterIsInstance<Button>().count { it.text == "Preview" })
                    views(activity).filterIsInstance<android.widget.RadioButton>().single { it.text == "Sound FX" }.performClick()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(VoicePreset.entries.count { it.isSoundEffect }, preset(activity).count)
                    button(activity, "Tune preset").performClick()
                    assertTrue(slider(activity, "Effect mix").isShown)
                    assertTrue(slider(activity, "Effect mix").isEnabled)
                    slider(activity, "Effect mix").progress = 27
                }
                var loaded = false
                for (attempt in 0 until 100) {
                    scenario.onActivity { activity -> loaded = views(activity).filterIsInstance<TextView>().any { it.text.contains("Original stays unchanged") } }
                    if (loaded) break
                    Thread.sleep(50)
                }
                assertTrue("Sound FX fixture loads", loaded)
                ScreenshotEvidence.capture("editor-sound-fx-expanded")
                scenario.recreate()
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue(slider(activity, "Pitch").isShown)
                    assertEquals(27, slider(activity, "Effect mix").progress)
                    assertTrue(views(activity).filterIsInstance<android.widget.RadioButton>().single { it.text == "Sound FX" }.isChecked)
                }
            }
        } finally { file.delete() }
    }

}
