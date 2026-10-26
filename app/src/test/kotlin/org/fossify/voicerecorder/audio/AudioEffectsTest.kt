package org.fossify.voicerecorder.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AudioEffectsTest {
    private fun tone(rate: Int=16000,channels: Int=1)=PcmAudio(ShortArray(rate*channels) { (sin(2*PI*440*(it/channels)/rate)*10000).roundToInt().toShort() },rate,channels)
    private fun frequency(audio: PcmAudio): Double {
        val start=audio.frames/4
        val end=audio.frames*3/4
        var crossings=0
        for(i in start+1 until end) if(audio.samples[(i-1)*audio.channels]<=0 && audio.samples[i*audio.channels]>0) crossings++
        return crossings.toDouble()*audio.sampleRate/(end-start)
    }
    @Test fun identityDoesNotMutateInput() {
        val input=tone(); val before=input.samples.copyOf(); val output=AudioEffects.process(input,EditOptions())
        assertArrayEquals(before,output.samples); assertNotSame(input.samples,output.samples)
        assertArrayEquals(before,input.samples)
    }
    @Test fun gainAndNormalizationAreSafe() {
        val input=tone()
        val quieter=AudioEffects.process(input,EditOptions(gainDb=-6.0206))
        assertTrue(abs(quieter.samples.maxOrNull()!!.toInt()-5000)<=2)
        val normalized=AudioEffects.process(input,EditOptions(normalize=true))
        assertTrue(abs(normalized.samples.maxOrNull()!!.toInt()-29204)<=2)
        val silence=PcmAudio(ShortArray(16000),16000,1)
        assertArrayEquals(silence.samples,AudioEffects.process(silence,EditOptions(normalize=true)).samples)
        val loud=AudioEffects.process(input,EditOptions(gainDb=24.0))
        assertEquals(32767,loud.samples.maxOrNull()!!.toInt()); assertEquals(-32768,loud.samples.minOrNull()!!.toInt())
    }
    @Test fun presetDefaultsAreTunableAndDistinct() {
        assertEquals(4.5, VoicePreset.FEMININE.defaults().pitchSemitones, 0.0)
        assertEquals(2.0, VoicePreset.FEMININE.defaults().formantSemitones, 0.0)
        assertEquals(-4.0, VoicePreset.MASCULINE.defaults().pitchSemitones, 0.0)
        assertEquals(7.0, VoicePreset.CHIPMUNK.defaults().formantSemitones, 0.0)
        val changed = VoicePreset.FEMININE.defaults().copy(formantSemitones = 1.0)
        assertEquals(1.0, changed.formantSemitones, 0.0)
        assertEquals(2.0, VoicePreset.FEMININE.formants, 0.0)
    }
    @Test fun trimsUseFrameBoundariesAndRejectInvalidControls() {
        val input=tone(channels=2)
        val out=AudioEffects.process(input,EditOptions(trimStartMs=100,trimEndMs=300))
        assertEquals(3200,out.frames)
        for (options in listOf(EditOptions(speed=0.0),EditOptions(gainDb=Double.NaN),EditOptions(trimStartMs=2000),EditOptions(pitchSemitones=50.0),EditOptions(formantSemitones=Double.NaN),EditOptions(effectMix=2.0))) {
            try { AudioEffects.process(input,options); fail("Invalid control accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
