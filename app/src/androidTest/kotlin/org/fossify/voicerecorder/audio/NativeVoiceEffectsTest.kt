package org.fossify.voicerecorder.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class NativeVoiceEffectsTest {
    private fun tone(rate: Int=16000,channels: Int=1)=PcmAudio(ShortArray(rate*channels) { (sin(2*PI*440*(it/channels)/rate)*10000).roundToInt().toShort() },rate,channels)
    private fun frequency(audio: PcmAudio): Double {
        val start=audio.frames/4
        val end=audio.frames*3/4
        var crossings=0
        for(i in start+1 until end) if(audio.samples[(i-1)*audio.channels]<=0 && audio.samples[i*audio.channels]>0) crossings++
        return crossings.toDouble()*audio.sampleRate/(end-start)
    }
    @Test fun pitchChangesFrequencyWithoutChangingDuration() {
        val input=tone()
        for ((semitones,expected) in listOf(-12.0 to 220.0,12.0 to 880.0)) {
            val out=AudioEffects.process(input,EditOptions(pitchSemitones=semitones))
            assertTrue(abs(input.frames-out.frames)<160); assertEquals(expected,frequency(out),12.0)
        }
    }
    @Test fun tempoChangesDurationWithoutChangingPitch() {
        val input=tone()
        for(speed in listOf(0.5,2.0)) {
            val out=AudioEffects.process(input,EditOptions(speed=speed))
            assertTrue(abs((input.frames/speed).roundToInt()-out.frames)<160); assertEquals(440.0,frequency(out),10.0)
        }
    }
    @Test fun stereoChannelsStayAlignedAndPresetsAreAudible() {
        val input=tone(channels=2)
        for(effect in VoicePreset.entries.filter { it!=VoicePreset.NONE }) {
            val out=AudioEffects.process(input,effect.defaults())
            assertFalse(input.samples.contentEquals(out.samples))
            for(i in 0 until out.frames) assertEquals(out.samples[i*2],out.samples[i*2+1])
        }
        val out=AudioEffects.process(input,EditOptions(speed=1.5,pitchSemitones=5.0))
        for(i in 0 until out.frames) assertEquals(out.samples[i*2],out.samples[i*2+1])
    }
    @Test fun formantAndMixControlsRemainFiniteAndPreserveSource() {
        val input=tone(channels=2); val before=input.samples.copyOf()
        for (formant in listOf(-6.0, 0.0, 6.0)) {
            val output=AudioEffects.process(input, EditOptions(pitchSemitones=4.5, formantSemitones=formant))
            assertTrue(output.samples.any { it.toInt()!=0 })
            assertTrue(abs(output.frames-input.frames)<160)
        }
        assertArrayEquals(before,input.samples)
        val bypass=AudioEffects.process(input, EditOptions(preset=VoicePreset.EIGHTIES, effectMix=0.0))
        assertArrayEquals(before,bypass.samples)
    }
}
