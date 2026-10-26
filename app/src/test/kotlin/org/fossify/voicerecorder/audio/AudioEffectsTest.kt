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
    @Test fun pitchChangesFrequencyWithoutChangingDuration() {
        val input=tone()
        for ((semitones,expected) in listOf(-12.0 to 220.0,12.0 to 880.0)) {
            val out=AudioEffects.process(input,EditOptions(pitchSemitones=semitones))
            assertEquals(input.frames,out.frames); assertEquals(expected,frequency(out),12.0)
        }
    }
    @Test fun tempoChangesDurationWithoutChangingPitch() {
        val input=tone()
        for(speed in listOf(0.5,2.0)) {
            val out=AudioEffects.process(input,EditOptions(speed=speed))
            assertEquals((input.frames/speed).roundToInt(),out.frames); assertEquals(440.0,frequency(out),10.0)
        }
    }
    @Test fun stereoChannelsStayAlignedAndPresetsAreAudible() {
        val input=tone(channels=2)
        for(effect in VoicePreset.entries.filter { it!=VoicePreset.NONE }) {
            val out=AudioEffects.process(input,EditOptions(preset=effect))
            assertFalse(input.samples.contentEquals(out.samples))
            for(i in 0 until out.frames) assertEquals(out.samples[i*2],out.samples[i*2+1])
        }
        val out=AudioEffects.process(input,EditOptions(speed=1.5,pitchSemitones=5.0))
        for(i in 0 until out.frames) assertEquals(out.samples[i*2],out.samples[i*2+1])
    }
    @Test fun trimsUseFrameBoundariesAndRejectInvalidControls() {
        val input=tone(channels=2)
        val out=AudioEffects.process(input,EditOptions(trimStartMs=100,trimEndMs=300))
        assertEquals(3200,out.frames)
        for (options in listOf(EditOptions(speed=0.0),EditOptions(gainDb=Double.NaN),EditOptions(trimStartMs=2000),EditOptions(pitchSemitones=50.0))) {
            try { AudioEffects.process(input,options); fail("Invalid control accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
