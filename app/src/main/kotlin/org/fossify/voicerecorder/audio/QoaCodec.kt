/* QOA algorithm port Copyright (c) 2023 Dominic Szablewski, MIT.
 * Kotlin port and strict file validation for Voice Recorder QOA. */
package org.fossify.voicerecorder.audio

import java.io.*

/** Signed 16-bit, interleaved PCM. Counts are individual samples, not frames. */
data class PcmAudio(val samples: ShortArray, val sampleRate: Int, val channels: Int) {
    init {
        require(channels in 1..2 && sampleRate in 1..192000) { "Only mono/stereo audio up to 192 kHz is supported" }
        require(samples.isNotEmpty() && samples.size % channels == 0) { "Incomplete PCM audio" }
        require(samples.size <= QoaCodec.MAX_SAMPLES) { "Audio is too large to edit" }
    }
    val frames: Int get() = samples.size / channels
    val durationMs: Long get() = frames.toLong() * 1000 / sampleRate
}

object QoaCodec {
    const val MAX_SAMPLES = 16_000_000
    const val FRAME_SAMPLES = 5120
    private val quant = intArrayOf(7,7,7,5,5,3,3,1,0,0,2,2,4,4,6,6,6)
    private val reciprocal = intArrayOf(65536,9363,3121,1457,781,475,311,216,156,117,90,71,57,47,39,32)
    internal val dequant = arrayOf(
        intArrayOf(1,-1,3,-3,5,-5,7,-7),
        intArrayOf(5,-5,18,-18,32,-32,49,-49),
        intArrayOf(16,-16,53,-53,95,-95,147,-147),
        intArrayOf(34,-34,113,-113,203,-203,315,-315),
        intArrayOf(63,-63,210,-210,378,-378,588,-588),
        intArrayOf(104,-104,345,-345,621,-621,966,-966),
        intArrayOf(158,-158,528,-528,950,-950,1477,-1477),
        intArrayOf(228,-228,760,-760,1368,-1368,2128,-2128),
        intArrayOf(316,-316,1053,-1053,1895,-1895,2947,-2947),
        intArrayOf(422,-422,1405,-1405,2529,-2529,3934,-3934),
        intArrayOf(548,-548,1828,-1828,3290,-3290,5117,-5117),
        intArrayOf(696,-696,2320,-2320,4176,-4176,6496,-6496),
        intArrayOf(868,-868,2893,-2893,5207,-5207,8099,-8099),
        intArrayOf(1064,-1064,3548,-3548,6386,-6386,9933,-9933),
        intArrayOf(1286,-1286,4288,-4288,7718,-7718,12005,-12005),
        intArrayOf(1536,-1536,5120,-5120,9216,-9216,14336,-14336),
    )
    internal class Lms(val history: IntArray = IntArray(4), val weights: IntArray = intArrayOf(0,0,-8192,16384)) {
        fun copy() = Lms(history.copyOf(), weights.copyOf())
        fun predict(): Int { var sum = 0; for (i in 0..3) sum += weights[i] * history[i]; return sum shr 13 }
        fun update(sample: Int, residual: Int) {
            val delta = residual shr 4
            for (i in 0..3) weights[i] += if (history[i] < 0) -delta else delta
            for (i in 0..2) history[i] = history[i+1]
            history[3] = sample
        }
    }
    fun encode(audio: PcmAudio): ByteArray = ByteArrayOutputStream().also { write(audio, it) }.toByteArray()
    fun decode(bytes: ByteArray, maxSamples: Int = MAX_SAMPLES): PcmAudio = read(ByteArrayInputStream(bytes), maxSamples)
    fun write(audio: PcmAudio, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(0x716f6166); out.writeInt(audio.frames)
        val states = Array(audio.channels) { Lms() }
        var frame = 0
        while (frame < audio.frames) {
            val length = minOf(FRAME_SAMPLES, audio.frames-frame)
            out.write(encodeFrame(audio.samples, frame * audio.channels, length, audio.sampleRate, audio.channels, states))
            frame += length
        }
        out.flush()
    }
    internal fun encodeFrame(pcm: ShortArray, offset: Int, frames: Int, rate: Int, channels: Int, states: Array<Lms>): ByteArray {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Audio encoding canceled")
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        val size = 8 + channels*16 + ((frames+19)/20)*channels*8
        out.writeLong((channels.toLong() shl 56) or (rate.toLong() shl 32) or (frames.toLong() shl 16) or size.toLong())
        for (state in states) { state.history.forEach { out.writeShort(it) }; state.weights.forEach { out.writeShort(it) } }
        val previous = IntArray(channels)
        for (start in 0 until frames step 20) for (c in 0 until channels) {
            val count = minOf(20,frames-start)
            var bestRank = Long.MAX_VALUE
            var bestSlice = 0L
            var bestState = states[c]
            var bestScale = 0
            for (candidate in 0..15) {
                val scale = (candidate+previous[c]) and 15
                val state = states[c].copy()
                var slice = scale.toLong()
                var rank = 0L
                for (i in 0 until count) {
                    val sample = pcm[offset+(start+i)*channels+c].toInt()
                    val predicted = state.predict()
                    val residual = sample-predicted
                    var n = (residual*reciprocal[scale]+32768) shr 16
                    n += residual.compareTo(0)-n.compareTo(0)
                    val q = quant[n.coerceIn(-8,8)+8]
                    val dq = dequant[scale][q]
                    val reconstructed = (predicted+dq).coerceIn(-32768,32767)
                    var penalty = 0
                    for (w in state.weights) penalty += w*w
                    penalty = ((penalty shr 18)-0x8ff).coerceAtLeast(0)
                    val error = (sample-reconstructed).toLong()
                    rank += error*error + (penalty*penalty).toLong()
                    if (rank > bestRank) break
                    state.update(reconstructed,dq)
                    slice = (slice shl 3) or q.toLong()
                }
                if (rank < bestRank) { bestRank=rank; bestSlice=slice; bestState=state; bestScale=scale }
            }
            previous[c]=bestScale; states[c]=bestState
            out.writeLong(bestSlice shl ((20-count)*3))
        }
        return buffer.toByteArray()
    }
    fun read(input: InputStream, maxSamples: Int = MAX_SAMPLES): PcmAudio {
        require(maxSamples in 1..MAX_SAMPLES)
        val reader = QoaReader(input)
        val total = reader.totalFrames.toLong()*reader.channels
        require(total in 1..maxSamples.toLong()) { "Audio is too large to edit" }
        val data = ShortArray(total.toInt())
        var position=0
        while (true) {
            val frame=reader.readFrame() ?: break
            frame.copyInto(data,position); position+=frame.size
        }
        return PcmAudio(data,reader.sampleRate,reader.channels)
    }
}

/** Bounded memory file decoder. Rejects streaming/dynamic format and malformed/trailing data. */
class QoaReader(input: InputStream) {
    private val source = DataInputStream(BufferedInputStream(input))
    val totalFrames: Int
    val channels: Int
    val sampleRate: Int
    private var nextHeader: Long
    private var readFrames=0
    init {
        require(source.readInt()==0x716f6166) { "Not a QOA file" }
        val total=source.readInt().toLong() and 0xffffffffL
        require(total in 1..Int.MAX_VALUE.toLong()) { "Unsupported QOA sample count" }
        totalFrames=total.toInt()
        nextHeader=source.readLong()
        channels=(nextHeader ushr 56).toInt()
        sampleRate=((nextHeader ushr 32) and 0xffffff).toInt()
        require(channels in 1..2 && sampleRate in 1..192000) { "Unsupported QOA channel count or sample rate" }
    }
    fun readFrame(): ShortArray? {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Audio decoding canceled")
        if (readFrames==totalFrames) return null
        val header=nextHeader
        val length=((header ushr 16) and 0xffff).toInt()
        val size=(header and 0xffff).toInt()
        require((header ushr 56).toInt()==channels && ((header ushr 32) and 0xffffff).toInt()==sampleRate) { "QOA format changed" }
        require(length in 1..QoaCodec.FRAME_SAMPLES && length<=totalFrames-readFrames) { "Invalid QOA frame length" }
        require(length==minOf(QoaCodec.FRAME_SAMPLES,totalFrames-readFrames)) { "Short intermediate QOA frame" }
        require(size==8+channels*16+((length+19)/20)*channels*8) { "Invalid QOA frame size" }
        val states=Array(channels) { QoaCodec.Lms(IntArray(4) { source.readShort().toInt() },IntArray(4) { source.readShort().toInt() }) }
        val samples=ShortArray(length*channels)
        for (start in 0 until length step 20) for (c in 0 until channels) {
            val slice=source.readLong()
            val scale=(slice ushr 60).toInt()
            val count=minOf(20,length-start)
            for (i in 0 until count) {
                val quant=((slice ushr (57-i*3)) and 7).toInt()
                val dq=QoaCodec.dequant[scale][quant]
                val sample=(states[c].predict()+dq).coerceIn(-32768,32767)
                states[c].update(sample,dq); samples[(start+i)*channels+c]=sample.toShort()
            }
            val unused=(20-count)*3
            require(unused==0 || (slice and ((1L shl unused)-1))==0L) { "Invalid QOA padding" }
        }
        readFrames+=length
        if (readFrames<totalFrames) nextHeader=source.readLong()
        else require(source.read()==-1) { "Trailing QOA data" }
        return samples
    }
}

/** Streaming capture to a seekable private temporary file; finish patches the total sample count. */
class QoaWriter(private val file: RandomAccessFile, private val sampleRate: Int, private val channels: Int) {
    private val states=Array(channels.coerceIn(1,2)) { QoaCodec.Lms() }
    private val pending=ShortArray(QoaCodec.FRAME_SAMPLES*channels.coerceIn(1,2))
    private var used=0
    private var total=0L
    private var finished=false
    init {
        require(channels in 1..2 && sampleRate in 1..192000)
        file.setLength(0); file.writeInt(0x716f6166); file.writeInt(0)
    }
    fun append(samples: ShortArray, count: Int = samples.size) {
        check(!finished)
        require(count in 0..samples.size && count%channels==0)
        require(total+count/channels<=Int.MAX_VALUE) { "Recording is too long" }
        var offset=0
        while (offset<count) {
            val take=minOf(count-offset,pending.size-used)
            samples.copyInto(pending,used,offset,offset+take); used+=take; offset+=take
            if (used==pending.size) flush()
        }
        total+=count/channels
    }
    private fun flush() { if (used>0) { file.write(QoaCodec.encodeFrame(pending,0,used/channels,sampleRate,channels,states)); used=0 } }
    fun finish() {
        if (finished) return
        require(total>0) { "Recording is empty" }
        flush(); file.seek(4); file.writeInt(total.toInt()); file.fd.sync(); finished=true
    }
}
