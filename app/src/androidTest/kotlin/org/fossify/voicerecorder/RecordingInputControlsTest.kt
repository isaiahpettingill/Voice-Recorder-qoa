package org.fossify.voicerecorder

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.EXTENSION_QOA
import org.fossify.voicerecorder.activities.EditSoundActivity
import org.fossify.voicerecorder.audio.PcmAudio
import org.fossify.voicerecorder.audio.QoaCodec
import org.fossify.voicerecorder.audio.VoicePreset
import org.fossify.voicerecorder.fragments.RecorderFragment
import org.fossify.voicerecorder.recorder.RecordingPresetSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordingInputControlsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }

    @Test fun noiseChoiceSurvivesEditorRecreationAndRecorderNamePersistsImmediately() {
        val previousExtension = context.config.extension
        val previous = RecordingPresetSettings.load(context)
        val previousName = RecordingPresetSettings.recordingName(context)
        val file = File.createTempFile("noise-controls-", ".qoa", context.cacheDir)
        file.writeBytes(QoaCodec.encode(PcmAudio(ShortArray(16000), 16000, 1)))
        try {
            context.config.extension = EXTENSION_QOA
            RecordingPresetSettings.save(context, VoicePreset.NONE.defaults().copy(noiseReduction = true))
            RecordingPresetSettings.setRecordingName(context, "")
            ActivityScenario.launch<EditSoundActivity>(Intent(context, EditSoundActivity::class.java)
                .putExtra("path", file.path)).use { scenario ->
                scenario.onActivity { activity ->
                    val toggle = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<SwitchCompat>().single()
                    assertTrue(toggle.isChecked)
                    assertTrue("Noise control is not hidden in Tune", toggle.isShown)
                    toggle.performClick()
                    assertFalse(RecordingPresetSettings.load(context).noiseReduction)
                }
                scenario.recreate()
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val toggle = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<SwitchCompat>().single()
                    assertFalse(toggle.isChecked)
                    // Detached inflation avoids starting the microphone or changing playback/navigation.
                    fun recorder() = LayoutInflater.from(activity).inflate(R.layout.fragment_recorder, null, false) as RecorderFragment
                    val first = recorder()
                    first.onResume()
                    val name = first.findViewById<EditText>(R.id.recording_name)
                    assertTrue(name.isEnabled)
                    name.setText("Scene 12 voice")
                    assertEquals("Scene 12 voice", RecordingPresetSettings.recordingName(context))
                    assertFalse(first.findViewById<SwitchCompat>(R.id.recording_noise_reduction).isChecked)
                    val recreated = recorder()
                    assertEquals("Scene 12 voice", recreated.findViewById<EditText>(R.id.recording_name).text.toString())
                    recreated.findViewById<EditText>(R.id.recording_name).setText("")
                    assertEquals("", RecordingPresetSettings.recordingName(context))
                    first.onDestroy(); recreated.onDestroy()
                }
            }
        } finally {
            context.config.extension = previousExtension
            RecordingPresetSettings.save(context, previous)
            RecordingPresetSettings.setRecordingName(context, previousName)
            file.delete()
        }
    }
}
