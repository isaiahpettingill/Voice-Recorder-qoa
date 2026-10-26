package org.fossify.voicerecorder.audio

import kotlin.math.*

enum class VoicePreset { NONE, ROBOT, RADIO, EIGHTIES }
data class EditOptions(
    val gainDb: Double = 0.0,
    val normalize: Boolean = false,
    val pitchSemitones: Double = 0.0,
    val speed: Double = 1.0,
    val preset: VoicePreset = VoicePreset.NONE,
    val trimStartMs: Int = 0,
    val trimEndMs: Int? = null,
)

/** Offline, deterministic, independent pitch and tempo. Never modifies the input buffer. */
object AudioEffects {
    fun process(audio: PcmAudio, options: EditOptions): PcmAudio {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Audio edit canceled")
        require(options.gainDb.isFinite() && options.gainDb in -36.0..24.0)
        require(options.pitchSemitones.isFinite() && options.pitchSemitones in -12.0..12.0)
        require(options.speed.isFinite() && options.speed in 0.5..2.0)
        require(options.trimStartMs>=0 && (options.trimEndMs==null || options.trimEndMs>options.trimStartMs))
        val start=(options.trimStartMs.toLong()*audio.sampleRate/1000).coerceAtMost(audio.frames.toLong()).toInt()
        val end=options.trimEndMs?.let { (it.toLong()*audio.sampleRate/1000).coerceAtMost(audio.frames.toLong()).toInt() } ?: audio.frames
        require(end>start) { "Select a non-empty audio range" }
        val expected=((end-start)/options.speed).roundToInt().coerceAtLeast(1)
        require(expected.toLong()*audio.channels<=QoaCodec.MAX_SAMPLES) { "Edited audio exceeds the memory limit; trim it first" }
        var pcm=audio.samples.copyOfRange(start*audio.channels,end*audio.channels)
        if (options.pitchSemitones!=0.0) {
            val factor=2.0.pow(options.pitchSemitones/12.0)
            val shifted=resample(pcm,audio.channels,factor)
            pcm=stretch(shifted,audio.channels,audio.sampleRate,1.0/factor,end-start)
        }
        if (options.speed!=1.0) pcm=stretch(pcm,audio.channels,audio.sampleRate,options.speed,expected)
        if (options.preset!=VoicePreset.NONE) pcm=preset(pcm,audio.channels,audio.sampleRate,options.preset)
        var multiplier=10.0.pow(options.gainDb/20.0)
        if (options.normalize) {
            var peak=0
            for (sample in pcm) peak=maxOf(peak,abs(sample.toInt()))
            if (peak>0) multiplier*=32767.0*10.0.pow(-1.0/20.0)/peak // -1 dBFS peak, not loudness/LUFS
        }
        if (multiplier!=1.0) for (i in pcm.indices) pcm[i]=sample(pcm[i]*multiplier)
        return PcmAudio(pcm,audio.sampleRate,audio.channels)
    }
    private fun sample(value: Double): Short = value.roundToInt().coerceIn(-32768,32767).toShort()
    private fun resample(input: ShortArray, channels: Int, factor: Double): ShortArray {
        val frames=input.size/channels
        val count=(frames/factor).roundToInt().coerceAtLeast(1)
        require(count.toLong()*channels<=QoaCodec.MAX_SAMPLES) { "Pitch edit exceeds the memory limit; trim it first" }
        return ShortArray(count*channels) { index ->
            val position=(index/channels)*factor
            val a=position.toInt().coerceAtMost(frames-1)
            val b=minOf(a+1,frames-1)
            val fraction=position-a
            sample(input[a*channels+index%channels]*(1-fraction)+input[b*channels+index%channels]*fraction)
        }
    }
    /** WSOLA overlap-add with waveform alignment. Channels share one alignment to preserve stereo. */
    private fun stretch(input: ShortArray, channels: Int, rate: Int, speed: Double, outputFrames: Int): ShortArray {
        require(outputFrames.toLong()*channels<=QoaCodec.MAX_SAMPLES)
        val frames=input.size/channels
        if (frames<32) return resample(input,channels,frames.toDouble()/outputFrames)
        val window=minOf((rate*0.03).roundToInt().coerceAtLeast(32),frames)
        val hop=window/2
        val overlap=window-hop
        val search=(rate*0.008).roundToInt().coerceAtLeast(1)
        val output=ShortArray(outputFrames*channels)
        input.copyInto(output,0,0,minOf(window,outputFrames)*channels)
        var out=hop
        while (out<outputFrames) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Audio edit canceled")
            val expected=(out*speed).roundToInt().coerceIn(0,maxOf(0,frames-window))
            var best=expected
            var bestScore=Double.NEGATIVE_INFINITY
            val low=maxOf(0,expected-search)
            val high=minOf(frames-window,expected+search)
            val compare=minOf(overlap,outputFrames-out)
            // Coarse sample stride bounds CPU use for long recordings; no channel-specific shifts.
            for (candidate in low..high step 4) {
                var cross=0.0; var sourceEnergy=1.0; var targetEnergy=1.0
                for (j in 0 until compare step 8) for (c in 0 until channels) {
                    val x=input[(candidate+j)*channels+c].toDouble()
                    val y=output[(out+j)*channels+c].toDouble()
                    cross+=x*y; sourceEnergy+=x*x; targetEnergy+=y*y
                }
                val score=cross/sqrt(sourceEnergy*targetEnergy)-abs(candidate-expected)*1e-10
                if (score>bestScore) { bestScore=score; best=candidate }
            }
            val count=minOf(window,outputFrames-out)
            for (j in 0 until count) for (c in 0 until channels) {
                val index=(out+j)*channels+c
                val value=input[(best+j)*channels+c].toDouble()
                output[index]=if (j<overlap) {
                    val blend=j.toDouble()/overlap
                    sample(output[index]*(1-blend)+value*blend)
                } else value.toInt().toShort()
            }
            out+=hop
        }
        return output
    }
    private fun preset(input: ShortArray, channels: Int, rate: Int, effect: VoicePreset): ShortArray {
        val output=ShortArray(input.size)
        val highPrevious=DoubleArray(channels)
        val lowPrevious=DoubleArray(channels)
        val previous=DoubleArray(channels)
        val highAlpha=1.0/(1.0+2*PI*300/rate)
        val lowAlpha=(2*PI*minOf(3400.0,rate*0.4)/rate).let { it/(1+it) }
        for (frame in 0 until input.size/channels) for (c in 0 until channels) {
            val index=frame*channels+c
            val x=input[index].toDouble()
            val value=when(effect) {
                VoicePreset.ROBOT -> x*(0.25+0.75*sin(2*PI*70*frame/rate))
                VoicePreset.RADIO -> {
                    val high=highAlpha*(highPrevious[c]+x-previous[c]); previous[c]=x; highPrevious[c]=high
                    val low=lowPrevious[c]+lowAlpha*(high-lowPrevious[c]); lowPrevious[c]=low
                    tanh(low/9000)*12000
                }
                VoicePreset.EIGHTIES -> {
                    val chorusDelay=((0.018+0.004*sin(2*PI*0.8*frame/rate))*rate).roundToInt()
                    val echoDelay=(0.12*rate).roundToInt().coerceAtLeast(1)
                    val chorus=if (frame>=chorusDelay) input[(frame-chorusDelay)*channels+c].toDouble() else 0.0
                    val echo=if (frame>=echoDelay) input[(frame-echoDelay)*channels+c].toDouble() else 0.0
                    // Bright chorus + short slapback; conservative mix avoids clipping at unity gain.
                    x*0.65+chorus*0.22+echo*0.13
                }
                VoicePreset.NONE -> x
            }
            output[index]=sample(value)
        }
        return output
    }
}
