package org.fossify.voicerecorder.audio

import kotlin.math.*

/** Starting points, not claims of identity or guaranteed gender conversion. See docs/VOICE_PRESETS.md. */
enum class VoicePreset(val title: String, val pitch: Double, val formants: Double, val effect: Int = 0, val mix: Double = 0.0, val isSoundEffect: Boolean = false) {
    NONE("Clean", 0.0, 0.0),
    FEMININE("Feminine", 4.5, 2.0),
    MASCULINE("Masculine", -4.0, -2.0),
    CHIPMUNK("Chipmunk", 7.0, 7.0),
    DEEP("Deep voice", -7.0, -3.0),
    ROBOT("Robot (amplitude modulation)", 0.0, 0.0, 1, 0.65),
    RADIO("Radio", 0.0, 0.0, 2, 0.85),
    EIGHTIES("80s chorus + echo", 0.0, 0.0, 3, 0.5),
    ECHO("Echo", 0.0, 0.0, 4, 0.5, true),
    CAVE("Cave", 0.0, 0.0, 5, 0.5, true),
    CHORUS("Chorus", 0.0, 0.0, 6, 0.5, true),
    FLANGER("Flanger", 0.0, 0.0, 7, 0.5, true),
    PHASER("Phaser", 0.0, 0.0, 8, 0.5, true);

    fun defaults() = EditOptions(pitchSemitones = pitch, formantSemitones = formants, preset = this, effectMix = mix)
}

data class EditOptions(
    val noiseReduction: Boolean = true,
    val gainDb: Double = 0.0,
    val normalize: Boolean = false,
    val pitchSemitones: Double = 0.0,
    val formantSemitones: Double = 0.0,
    val speed: Double = 1.0,
    val preset: VoicePreset = VoicePreset.NONE,
    val effectMix: Double = 0.0,
    val trimStartMs: Int = 0,
    val trimEndMs: Int? = null,
)

/** Editing orchestration only. Pitch/time/formant and creative DSP are upstream libraries. */
object AudioEffects {
    fun process(audio: PcmAudio, options: EditOptions): PcmAudio {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Audio edit canceled")
        require(options.gainDb.isFinite() && options.gainDb in -36.0..24.0)
        require(options.pitchSemitones.isFinite() && options.pitchSemitones in -12.0..12.0)
        require(options.formantSemitones.isFinite() && options.formantSemitones in -12.0..12.0)
        require(options.speed.isFinite() && options.speed in 0.5..2.0)
        require(options.effectMix.isFinite() && options.effectMix in 0.0..1.0)
        require(options.trimStartMs >= 0 && (options.trimEndMs == null || options.trimEndMs > options.trimStartMs))
        val start = (options.trimStartMs.toLong() * audio.sampleRate / 1000).coerceAtMost(audio.frames.toLong()).toInt()
        val end = options.trimEndMs?.let { (it.toLong() * audio.sampleRate / 1000).coerceAtMost(audio.frames.toLong()).toInt() } ?: audio.frames
        require(end > start) { "Select a non-empty audio range" }
        val expected = ((end - start) / options.speed).roundToInt().coerceAtLeast(1)
        require(expected.toLong() * audio.channels <= QoaCodec.MAX_SAMPLES) { "Edited audio exceeds the memory limit; trim it first" }
        var pcm = audio.samples.copyOfRange(start * audio.channels, end * audio.channels)
        if (options.noiseReduction) pcm = NativeNoiseReducer.reduce(pcm, audio.sampleRate, audio.channels)
        if (options.pitchSemitones != 0.0 || options.formantSemitones != 0.0 || options.speed != 1.0 ||
            (options.preset.effect != 0 && options.effectMix > 0.0)) {
            pcm = NativeVoiceProcessor.process(pcm, audio.sampleRate, audio.channels,
                options.pitchSemitones, options.formantSemitones, options.speed, options.preset.effect, options.effectMix)
        }
        // Basic gain and peak normalization are sample-level utilities, not a custom voice algorithm.
        var multiplier = 10.0.pow(options.gainDb / 20.0)
        if (options.normalize) {
            var peak = 0
            for (sample in pcm) peak = maxOf(peak, abs(sample.toInt()))
            if (peak > 0) multiplier *= 32767.0 * 10.0.pow(-1.0 / 20.0) / peak
        }
        if (multiplier != 1.0) for (i in pcm.indices) {
            if (i % 8192 == 0 && Thread.currentThread().isInterrupted) throw InterruptedException("Audio edit canceled")
            pcm[i] = (pcm[i] * multiplier).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return PcmAudio(pcm, audio.sampleRate, audio.channels)
    }
}
