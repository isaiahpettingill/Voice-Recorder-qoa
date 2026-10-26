package org.fossify.voicerecorder.recorder

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.fossify.voicerecorder.audio.*
import org.fossify.voicerecorder.extensions.createRecordingDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Changes are anchored to accepted PCM frames, never elapsed wall-clock time. */
data class PresetSegment(val frame: Long, val options: EditOptions)
class PresetTimeline {
    val segments = mutableListOf<PresetSegment>()
    fun accept(frame: Long, options: EditOptions): Boolean {
        require(frame >= 0 && (segments.isEmpty() || frame >= segments.last().frame))
        if (segments.lastOrNull()?.options == options) return false
        if (segments.lastOrNull()?.frame == frame) segments.removeAt(segments.lastIndex)
        segments.add(PresetSegment(frame, options))
        return true
    }
    val hasEffects: Boolean get() = segments.any { it.options != EditOptions() }
}

object RecordingPresetSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("recording-presets", Context.MODE_PRIVATE)
    fun load(context: Context): EditOptions = runCatching {
        decode(JSONObject(prefs(context).getString("options", "{}")!!))
    }.getOrDefault(EditOptions())
    fun save(context: Context, options: EditOptions) { prefs(context).edit().putString("options", encode(options).toString()).apply() }
    internal fun encode(o: EditOptions) = JSONObject().put("preset", o.preset.name).put("pitch", o.pitchSemitones)
        .put("formants", o.formantSemitones).put("speed", o.speed).put("mix", o.effectMix).put("gain", o.gainDb)
    internal fun decode(j: JSONObject): EditOptions {
        val preset = VoicePreset.entries.firstOrNull { it.name == j.optString("preset") } ?: VoicePreset.NONE
        fun number(key: String, default: Double, min: Double, max: Double) = j.optDouble(key, default).takeIf { it.isFinite() }?.coerceIn(min, max) ?: default
        return preset.defaults().copy(pitchSemitones=number("pitch", preset.pitch, -12.0, 12.0),
            formantSemitones=number("formants", preset.formants, -12.0, 12.0), speed=number("speed",1.0,.5,2.0),
            effectMix=number("mix",preset.mix,0.0,1.0), gainDb=number("gain",0.0,-36.0,24.0))
    }
}

/** Durable sidecar remains with the dry source until a separate effects copy is published. */
class RecordingAutomation(private val source: File, private val rate: Int) {
    val timeline = PresetTimeline()
    private var dryPublished = false
    fun markDryPublished() { dryPublished = true; persist() }
    private val journal = AtomicFile(File(source.path + ".presets.json"))
    fun persist() {
        val entries = JSONArray()
        timeline.segments.forEach { entries.put(JSONObject().put("frame", it.frame).put("options", RecordingPresetSettings.encode(it.options))) }
        val bytes = JSONObject().put("version",1).put("sampleRate",rate).put("dryPublished",dryPublished).put("segments",entries).toString().toByteArray()
        val out = journal.startWrite()
        try { out.write(bytes); journal.finishWrite(out) } catch (e: Exception) { journal.failWrite(out); throw e }
    }
    fun delete() { journal.delete() }
    fun render(context: Context, folder: String): Uri? = synchronized(renderLock) { renderLocked(context, folder) }
    private fun renderLocked(context: Context, folder: String): Uri? {
        if (!timeline.hasEffects && dryPublished) { delete(); source.delete(); return null }
        val recovered = recoverCompleteFrames(source, context.cacheDir)
        try { return renderRecovered(context, folder, recovered) } finally { recovered.delete() }
    }
    private fun renderRecovered(context: Context, folder: String, recovered: File): Uri? {
        if (!dryPublished) {
            val dryUri = publish(context, recovered, "Recovered-original-${System.currentTimeMillis()}.qoa", folder)
            markDryPublished()
            org.greenrobot.eventbus.EventBus.getDefault().post(org.fossify.voicerecorder.models.Events.RecordingSaved(dryUri))
        }
        if (!timeline.hasEffects) { delete(); source.delete(); return null }
        val audio = recovered.inputStream().use { QoaCodec.read(it, AudioFiles.EDIT_SAMPLE_LIMIT) }
        val rendered = File.createTempFile("rendered-", ".qoa", context.cacheDir)
        var destination: Uri? = null
        try {
            RandomAccessFile(rendered,"rw").use { file ->
                val writer = QoaWriter(file,audio.sampleRate,audio.channels)
                timeline.segments.forEachIndexed { index, segment ->
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val start = segment.frame.coerceAtMost(audio.frames.toLong()).toInt()
                    val end = (timeline.segments.getOrNull(index+1)?.frame ?: audio.frames.toLong()).coerceAtMost(audio.frames.toLong()).toInt()
                    if (end > start) {
                        val chunk = PcmAudio(audio.samples.copyOfRange(start*audio.channels,end*audio.channels), audio.sampleRate,audio.channels)
                        val result = AudioEffects.process(chunk, segment.options)
                        writer.append(result.samples)
                    }
                }
                writer.finish()
            }
            destination = publish(context, rendered, "Recording-effects-${System.currentTimeMillis()}.qoa", folder)
            delete(); source.delete()
            return destination
        } catch (e: Exception) {
            destination?.let { runCatching { if(it.scheme == "file") File(checkNotNull(it.path)).delete() else android.provider.DocumentsContract.deleteDocument(context.contentResolver,it) } }
            throw e
        } finally { rendered.delete() }
    }
    companion object {
        private val renderLock = Any()
        private fun publish(context: Context, source: File, name: String, folder: String): Uri {
            val uri = checkNotNull(context.createRecordingDocument(name,folder)) { "Cannot create copy in recording folder." }
            try {
                checkNotNull(context.contentResolver.openOutputStream(uri,"w")).use { out -> source.inputStream().use { it.copyTo(out) } }
                return uri
            } catch (e: Exception) {
                runCatching { if(uri.scheme == "file") File(checkNotNull(uri.path)).delete() else android.provider.DocumentsContract.deleteDocument(context.contentResolver,uri) }
                throw e
            }
        }
        /** Recover only complete encoded frames; never modify the only original in place. */
        internal fun recoverCompleteFrames(source: File, cache: File): File {
            val copy=File.createTempFile("recover-qoa-", ".qoa",cache)
            try {
                source.copyTo(copy,overwrite=true)
                RandomAccessFile(copy,"rw").use { file ->
                    require(file.length() >= 8 && file.readInt() == 0x716f6166) { "Invalid recovery recording" }
                    val declared=file.readInt()
                    if(declared != 0) return copy
                    var end=8L; var frames=0L; var format=0L
                    while(file.length()-end >= 8) {
                        file.seek(end);val header=file.readLong()
                        val channels=(header ushr 56).toInt();val rate=((header ushr 32) and 0xffffff).toInt()
                        val count=((header ushr 16) and 0xffff).toInt();val size=(header and 0xffff).toInt()
                        if(channels !in 1..2 || rate !in 1..192000 || count != QoaCodec.FRAME_SAMPLES || size != 8+channels*16+((count+19)/20)*channels*8 || end+size>file.length()) break
                        if(format != 0L && format != header ushr 32) break
                        format=header ushr 32; frames+=count;end+=size
                    }
                    require(frames in 1..Int.MAX_VALUE.toLong()) { "No complete audio frames to recover" }
                    file.setLength(end);file.seek(4);file.writeInt(frames.toInt());file.fd.sync()
                }
                return copy
            } catch(e: Exception) { copy.delete();throw e }
        }
        fun restore(source: File): RecordingAutomation {
            val json = JSONObject(AtomicFile(File(source.path+".presets.json")).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            require(json.getInt("version") == 1)
            return RecordingAutomation(source,json.getInt("sampleRate")).also { automation ->
                automation.dryPublished=json.optBoolean("dryPublished",false)
                val entries=json.getJSONArray("segments")
                for (i in 0 until entries.length()) { val entry=entries.getJSONObject(i); automation.timeline.accept(entry.getLong("frame"), RecordingPresetSettings.decode(entry.getJSONObject("options"))) }
            }
        }
    }
}
