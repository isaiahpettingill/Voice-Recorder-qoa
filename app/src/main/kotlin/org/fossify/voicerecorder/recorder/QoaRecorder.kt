package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.ParcelFileDescriptor
import org.fossify.voicerecorder.audio.QoaWriter
import org.fossify.voicerecorder.extensions.config
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Captures bounded PCM chunks. Only encoded QOA is retained on disk. */
class QoaRecorder(private val context: Context, private val automaticName: String = "Recording-${System.currentTimeMillis()}") : Recorder {
    private var path: String? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var source: AudioRecord? = null
    private var worker: Thread? = null
    private var temporary: File? = null
    private var automation: RecordingAutomation? = null
    @Volatile private var frozenName: String? = null
    private val settings = context.getSharedPreferences("recording-presets", Context.MODE_PRIVATE)
    private val nameListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "recordingName") {
            try { automation?.persistName(RecordingPresetSettings.recordingName(context)) }
            catch (error: Exception) { failure = error }
        }
    }
    private var capturedFrames = 0L
    private var discard = false
    private var finalized = false
    @Volatile private var stopped = false
    @Volatile private var paused = false
    @Volatile private var failure: Exception? = null
    private val amplitude = AtomicInteger()
    override fun setOutputFile(path: String) { this.path = path }
    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        descriptor = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
    }
    @SuppressLint("MissingPermission")
    override fun prepare() {
        val rate = context.config.samplingRate
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "This microphone does not support the selected sample rate." }
        source = AudioRecord(context.config.microphoneMode, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 20480)).also {
            check(it.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed." }
        }
        val recovery = File(context.filesDir, "recording-recovery").apply { mkdirs() }
        temporary = File.createTempFile("recording-", ".qoa", recovery)
        automation = RecordingAutomation(checkNotNull(temporary), rate, automaticName).also { it.updateName(RecordingPresetSettings.recordingName(context)); it.persist() }
        settings.registerOnSharedPreferenceChangeListener(nameListener)
    }
    override fun start() {
        val input = checkNotNull(source)
        input.startRecording()
        check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start." }
        worker = Thread({
            try {
                RandomAccessFile(checkNotNull(temporary), "rw").use { file ->
                    val writer = QoaWriter(file, context.config.samplingRate, 1)
                    val buffer = ShortArray(5120)
                    try {
                    while (!stopped) {
                        val count = input.read(buffer, 0, buffer.size)
                        if (count < 0 && !stopped) error("Microphone read failed ($count).")
                        if (count > 0 && !paused && !stopped) {
                            var peak = 0
                            for (index in 0 until count) peak = maxOf(peak, abs(buffer[index].toInt()))
                            amplitude.set(peak)
                            val journal = checkNotNull(automation)
                            journal.captureSettings(capturedFrames, RecordingPresetSettings.load(context), frozenName ?: RecordingPresetSettings.recordingName(context))
                            writer.append(buffer, count)
                            capturedFrames += count
                        }
                    }
                    } finally {
                        // Preserve completed samples even if capture failed midway.
                        writer.finish()
                        finalized = true
                    }
                }
            } catch (error: Exception) { failure = error }
        }, "qoa-recorder").also { it.start() }
    }
    override fun pause() { paused = true; amplitude.set(0) }
    override fun resume() { paused = false }
    override fun stop() {
        settings.unregisterOnSharedPreferenceChangeListener(nameListener)
        stopped = true
        try {
            source?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
        } finally {
            worker?.join(5000)
        }
        if (worker?.isAlive == true) {
            // Stop a stuck read before releasing its descriptor or touching its file.
            source?.release(); source = null
            worker?.join(5000)
        }
        check(worker?.isAlive != true) { "Microphone did not stop. Recovery file: ${temporary?.absolutePath}" }
        automation?.persistName(frozenName ?: RecordingPresetSettings.recordingName(context))
        failure?.let { throw it }
    }
    fun freezeName() {
        frozenName = RecordingPresetSettings.recordingName(context)
        settings.unregisterOnSharedPreferenceChangeListener(nameListener)
    }
    fun finalName(): String = checkNotNull(automation).recordingName
    fun discard() { discard = true }
    override fun release() {
        var saved = false
        try {
            if (!stopped || worker?.isAlive == true) stop()
            if (!discard) {
                failure?.let { throw it }
                check(finalized) { "Recording could not be finalized." }
                checkNotNull(temporary).inputStream().use { input ->
                    (descriptor?.let { FileOutputStream(it.fileDescriptor) }
                        ?: FileOutputStream(checkNotNull(path))).use { output -> input.copyTo(output); output.flush() }
                }
                // Complete all publication I/O before recording that the dry copy is safe.
                descriptor?.close(); descriptor = null
                if (automation?.timeline?.hasEffects == true) automation?.markDryPublished()
                saved = true
            }
        } catch (error: Exception) {
            throw IllegalStateException("Could not save recording. Recovery file retained: ${temporary?.absolutePath}. ${error.message}", error)
        } finally {
            // Cleanup errors must not turn a successfully published dry copy into a failed save.
            runCatching { source?.release() }; source = null
            runCatching { descriptor?.close() }; descriptor = null
            // Failed copies must never delete the only original. A live writer owns its file.
            if (worker?.isAlive != true && (discard || (saved && automation?.timeline?.hasEffects != true))) {
                automation?.delete(); temporary?.delete(); temporary = null
            }
        }
    }
    fun renderPresetCopy(folder: String): android.net.Uri? =
        automation?.takeIf { it.timeline.hasEffects }?.render(context, folder)
    override fun getMaxAmplitude() = amplitude.get()
}
