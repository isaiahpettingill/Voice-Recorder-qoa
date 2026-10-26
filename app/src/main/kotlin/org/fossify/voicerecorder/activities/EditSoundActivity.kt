package org.fossify.voicerecorder.activities

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.voicerecorder.audio.*
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.createRecordingDocument
import org.fossify.voicerecorder.extensions.ensureStoragePermission
import org.fossify.voicerecorder.models.Events
import org.fossify.commons.helpers.isRPlus
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.util.concurrent.Executors

/** All edits are rendered from the original. Save always creates a new file. */
class EditSoundActivity : SimpleActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var job: java.util.concurrent.Future<*>? = null
    private var original: PcmAudio? = null
    private var preview: MediaPlayer? = null
    private var previewFile: File? = null
    private var exporting: File? = null
    private var generation = 0
    private var busy = false
    private var renderingPreview = false
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var gain: SeekBar
    private lateinit var pitch: SeekBar
    private lateinit var formant: SeekBar
    private lateinit var effectMix: SeekBar
    private lateinit var speed: SeekBar
    private lateinit var normalize: CheckBox
    private lateinit var preset: Spinner
    private lateinit var trimStart: EditText
    private lateinit var trimEnd: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventBus.getDefault().post(Events.StopPlayback())
        savedInstanceState?.getString("exportFile")?.let { name ->
            if (name == File(name).name && name.startsWith("edited-")) exporting = File(cacheDir, name).takeIf { it.isFile }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(16)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(16) + bars.bottom)
            insets
        }
        val scroll = ScrollView(this)
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(controls)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        controls.addView(TextView(this).apply { text = "Edit sound"; textSize = 24f })
        status = TextView(this).apply { text = "Opening audio…"; textSize = 16f }
        controls.addView(status)
        gain = slider("Volume", 48, 24) { "${it - 24} dB" }
        normalize = CheckBox(this).apply {
            text = "Normalize (−1 dB before volume)"
            setOnCheckedChangeListener { _, _ -> stopPreview() }
        }
        controls.addView(normalize)
        pitch = slider("Pitch", 48, 24) { String.format(java.util.Locale.ROOT, "%+.1f semitones", (it - 24) / 2.0) }
        formant = slider("Voice resonance (formants)", 48, 24) { String.format(java.util.Locale.ROOT, "%+.1f semitones", (it - 24) / 2.0) }
        speed = slider("Speed", 150, 50) { String.format(java.util.Locale.ROOT, "%.2f×", (it + 50) / 100.0) }
        controls.addView(TextView(this).apply { text = "Voice effect"; textSize = 18f })
        preset = Spinner(this).apply {
            contentDescription = "Voice preset"
            minimumHeight = dp(48)
            adapter = ArrayAdapter(this@EditSoundActivity, android.R.layout.simple_spinner_dropdown_item,
                VoicePreset.entries.map { it.title })
        }
        controls.addView(preset)
        effectMix = slider("Effect mix", 100, 0) { "$it%" }
        effectMix.isEnabled = false
        preset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position != lastPreset) { lastPreset = position; applyPreset(VoicePreset.entries[position]) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        button("Reset voice preset") { applyPreset(VoicePreset.entries[preset.selectedItemPosition]) }
        controls.addView(TextView(this).apply {
            text = "Presets are starting points. Tune pitch and resonance for your voice; no voice identity is guaranteed. Effects use Rubber Band and Klarinet."
        })
        val trim = LinearLayout(this)
        trimStart = numberField("Start (seconds)", "0")
        trimEnd = numberField("End (seconds)", "")
        trim.addView(trimStart, LinearLayout.LayoutParams(0, dp(64), 1f))
        trim.addView(trimEnd, LinearLayout.LayoutParams(0, dp(64), 1f))
        controls.addView(trim)
        button("Preview") { render(save = false) }
        button("Stop preview") { stopPreview() }
        button("Save QOA copy") { ensureStoragePermission { granted -> if (granted) render(save = true) } }
        button("Export WAV…") { render(save = false, export = true) }
        button("Reset") { stopPreview(); gain.progress = 24; pitch.progress = 24; formant.progress = 24; effectMix.progress = 0; speed.progress = 50; normalize.isChecked = false; preset.setSelection(0); trimStart.setText("0"); trimEnd.setText("") }
        button("Close") { finish() }
        savedInstanceState?.let {
            lastPreset = it.getInt("preset", 0).coerceIn(0, VoicePreset.entries.lastIndex)
            preset.setSelection(lastPreset)
            effectMix.isEnabled = VoicePreset.entries[lastPreset].effect != 0
            pitch.progress = it.getInt("pitch", 24)
            formant.progress = it.getInt("formant", 24)
            speed.progress = it.getInt("speed", 50)
            gain.progress = it.getInt("gain", 24)
            effectMix.progress = it.getInt("effectMix", 0)
            normalize.isChecked = it.getBoolean("normalize", false)
            trimStart.setText(it.getString("trimStart", "0"))
            trimEnd.setText(it.getString("trimEnd", ""))
        }
        val path = intent.getStringExtra("path") ?: intent.data?.toString()
        if (path == null) { finish(); return }
        busy = true
        job = executor.submit {
            try {
                val audio = AudioFiles.readForEditing(this, path)
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    original = audio; busy = false
                    status.text = "${String.format(java.util.Locale.ROOT, "%.1f", audio.samples.size.toDouble() / audio.channels / audio.sampleRate)} s • ${audio.sampleRate} Hz • ${if (audio.channels == 1) "Mono" else "Stereo"}\nOriginal stays unchanged."
                }
            } catch (error: Exception) { showFailure(error) }
        }
    }
    private var lastPreset = 0
    private fun applyPreset(value: VoicePreset) {
        stopPreview()
        pitch.progress = (value.pitch * 2 + 24).toInt()
        formant.progress = (value.formants * 2 + 24).toInt()
        effectMix.progress = (value.mix * 100).toInt()
        effectMix.isEnabled = value.effect != 0
        speed.progress = 50
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun numberField(hintText: String, value: String) = EditText(this).apply {
        hint = hintText; setText(value); inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        contentDescription = hintText; setSingleLine()
    }
    private fun slider(title: String, maximum: Int, initial: Int, display: (Int) -> String): SeekBar {
        val label = TextView(this).apply { textSize = 18f }
        controls.addView(label)
        return SeekBar(this).apply {
            max = maximum; progress = initial; contentDescription = title; minimumHeight = dp(48)
            label.text = "$title: ${display(initial)}"
            ViewCompat.setStateDescription(this, display(initial))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                    label.text = "$title: ${display(value)}"
                    bar?.let { ViewCompat.setStateDescription(it, display(value)) }
                    if (user) stopPreview()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { }
                override fun onStopTrackingTouch(bar: SeekBar?) { }
            })
            controls.addView(this)
        }
    }
    private fun button(title: String, action: () -> Unit) { controls.addView(Button(this).apply { text = title; minHeight = dp(48); setOnClickListener { action() } }) }
    private fun options(): EditOptions {
        val start = trimStart.text.toString().toDoubleOrNull() ?: error("Enter a valid start time.")
        val end = trimEnd.text.toString().takeIf { it.isNotBlank() }?.let { it.toDoubleOrNull() ?: error("Enter a valid end time.") }
        require(start >= 0 && start <= Int.MAX_VALUE / 1000.0 && (end == null || (end > start && end <= Int.MAX_VALUE / 1000.0))) { "End must be after start." }
        return EditOptions(gainDb = (gain.progress - 24).toDouble(), normalize = normalize.isChecked,
            pitchSemitones = (pitch.progress - 24) / 2.0, formantSemitones = (formant.progress - 24) / 2.0,
            effectMix = effectMix.progress / 100.0, speed = (speed.progress + 50) / 100.0,
            preset = VoicePreset.entries[preset.selectedItemPosition], trimStartMs = (start * 1000).toInt(), trimEndMs = end?.let { (it * 1000).toInt() })
    }
    private fun render(save: Boolean, export: Boolean = false) {
        if (busy) return
        val input = original ?: return
        val settings = try { options() } catch (error: Exception) { showFailure(error); return }
        stopPreview()
        busy = true
        renderingPreview = !save && !export
        status.text = if (save) "Saving copy…" else "Rendering…"
        val ticket = ++generation
        job = executor.submit {
            var temp: File? = null
            try {
                val result = AudioEffects.process(input, settings)
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                temp = File.createTempFile("edited-", if (save) ".qoa" else ".wav", cacheDir)
                if (save) temp.outputStream().use { QoaCodec.write(result, it) } else AudioFiles.writeWav(result, temp)
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (save) {
                    val name = "${intent.getStringExtra("title")?.substringBeforeLast('.')?.replace(Regex("[^a-zA-Z0-9 _-]"), "_")?.take(60) ?: "Sound"}_edit_${System.currentTimeMillis()}.qoa"
                    val destination = File(config.saveRecordingsFolder, name)
                    if (isRPlus() || config.saveRecordingsFolder.startsWith("content://")) {
                        val uri = checkNotNull(createRecordingDocument(name)) { "Select a recording folder first." }
                        try { checkNotNull(contentResolver.openOutputStream(uri, "w")).use { output -> temp.inputStream().use { it.copyTo(output) } } }
                        catch (error: Exception) { if (uri.scheme == "file") File(requireNotNull(uri.path)).delete() else runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }; throw error }
                    } else {
                        check(!destination.exists())
                        try { temp.inputStream().use { inputStream -> destination.outputStream().use { inputStream.copyTo(it) } } }
                        catch (error: Exception) { destination.delete(); throw error }
                    }
                    temp.delete()
                    EventBus.getDefault().post(Events.RecordingCompleted())
                    runOnUiThread { if (!isDestroyed) { busy = false; status.text = "Saved $name" } }
                } else {
                    val ready = temp
                    runOnUiThread {
                        if (ticket != generation || isDestroyed || isFinishing) { ready.delete(); return@runOnUiThread }
                        busy = false
                        renderingPreview = false
                        if (export) {
                            exporting?.delete(); exporting = ready
                            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE); type = "audio/wav"; putExtra(Intent.EXTRA_TITLE, "Edited sound.wav")
                            }, 41)
                            status.text = "Choose where to export."
                        } else {
                            previewFile = ready
                            try {
                                preview = MediaPlayer().apply {
                                    setDataSource(ready.absolutePath)
                                    setOnPreparedListener { it.start(); status.text = "Preview playing" }
                                    setOnCompletionListener { stopPreview(); status.text = "Preview finished" }
                                    setOnErrorListener { _, _, _ -> stopPreview(); status.text = "Could not play preview"; true }
                                    prepareAsync()
                                }
                            } catch (error: Exception) { stopPreview(); showFailure(error) }
                        }
                    }
                }
            } catch (error: Exception) {
                temp?.delete()
                runOnUiThread {
                    if (ticket == generation && !isDestroyed && !isFinishing) {
                        busy = false; renderingPreview = false
                        status.text = if (error is InterruptedException) "Canceled" else error.message ?: "Could not process audio."
                    }
                }
            }
        }
    }
    @Deprecated("Legacy activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41) return
        val file = exporting ?: return
        exporting = null
        if (resultCode != RESULT_OK || data?.data == null) { file.delete(); status.text = "Export canceled"; return }
        busy = true
        job = executor.submit {
            try {
                checkNotNull(contentResolver.openOutputStream(data.data!!, "w")).use { output -> file.inputStream().use { it.copyTo(output) } }
                runOnUiThread { busy = false; status.text = "WAV exported" }
            } catch (error: Exception) { showFailure(error) } finally { file.delete() }
        }
    }
    private fun showFailure(error: Exception) { runOnUiThread {
        if (!isDestroyed && !isFinishing) { busy = false; status.text = error.message ?: "Could not process audio." }
    } }
    private fun stopPreview() {
        if (renderingPreview) { generation++; job?.cancel(true); renderingPreview = false; busy = false; status.text = "Preview stopped" }
        preview?.release(); preview = null; previewFile?.delete(); previewFile = null
    }
    override fun onPause() { stopPreview(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportFile", exporting?.name)
        outState.putInt("preset", preset.selectedItemPosition)
        outState.putInt("pitch", pitch.progress)
        outState.putInt("formant", formant.progress)
        outState.putInt("speed", speed.progress)
        outState.putInt("gain", gain.progress)
        outState.putInt("effectMix", effectMix.progress)
        outState.putBoolean("normalize", normalize.isChecked)
        outState.putString("trimStart", trimStart.text.toString())
        outState.putString("trimEnd", trimEnd.text.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        generation++; job?.cancel(true); executor.shutdownNow(); stopPreview()
        if (!isChangingConfigurations) exporting?.delete()
        super.onDestroy()
    }
}
