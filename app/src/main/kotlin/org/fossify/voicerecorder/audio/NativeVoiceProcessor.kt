package org.fossify.voicerecorder.audio

/** JNI integration for pinned Rubber Band and Klarinet; no app-owned voice DSP. */
object NativeVoiceProcessor {
    init { System.loadLibrary("voice-dsp") }

    fun process(samples: ShortArray, sampleRate: Int, channels: Int,
                pitchSemitones: Double, formantSemitones: Double, speed: Double,
                effect: Int, effectMix: Double): ShortArray {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Audio edit canceled")
        return processNative(samples, sampleRate, channels, pitchSemitones, formantSemitones, speed, effect, effectMix)
    }

    private external fun processNative(samples: ShortArray, sampleRate: Int, channels: Int,
                                       pitchSemitones: Double, formantSemitones: Double,
                                       speed: Double, effect: Int, effectMix: Double): ShortArray
}
