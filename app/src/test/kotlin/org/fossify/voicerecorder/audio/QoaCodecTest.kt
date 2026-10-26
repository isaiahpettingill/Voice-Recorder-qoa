package org.fossify.voicerecorder.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QoaCodecTest {
    private fun resource(name: String)=javaClass.getResourceAsStream("/qoa/$name")!!.use { it.readBytes() }
    private fun shorts(bytes: ByteArray): ShortArray {
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }
    @Test fun officialReferenceEncodeAndDecodeAreByteExact() {
        for (name in listOf("mono","stereo","tiny","silence")) {
            val channels=if(name=="stereo" || name=="silence") 2 else 1
            val audio=PcmAudio(shorts(resource("$name.pcm")),48000,channels)
            val reference=resource("$name.qoa")
            assertArrayEquals(name,reference,QoaCodec.encode(audio))
            assertArrayEquals(name,shorts(resource("$name.qoa.pcm")),QoaCodec.decode(reference).samples)
        }
    }
    @Test fun chunkedWriterMatchesReferenceAndPatchesLength() {
        val pcm=shorts(resource("stereo.pcm"))
        val temp=File.createTempFile("qoa-test", ".qoa")
        try {
            RandomAccessFile(temp,"rw").use { file ->
                val writer=QoaWriter(file,48000,2)
                for (start in pcm.indices step 38) writer.append(pcm.copyOfRange(start,minOf(start+38,pcm.size)))
                writer.finish(); writer.finish()
                try { writer.append(shortArrayOf(1,2)); fail("Append after finish") } catch (_: IllegalStateException) { }
            }
            assertArrayEquals(resource("stereo.qoa"),temp.readBytes())
        } finally { temp.delete() }
    }
    @Test fun streamingReaderReturnsBoundedFrames() {
        val reader=QoaReader(ByteArrayInputStream(resource("stereo.qoa")))
        var count=0
        while(true) { val frame=reader.readFrame() ?: break; assertTrue(frame.size<=10240); count+=frame.size }
        assertEquals(20642,count); assertNull(reader.readFrame())
    }
    @Test fun malformedFilesAreRejected() {
        val bytes=resource("mono.qoa")
        val mutations=listOf(
            bytes.copyOf(7),bytes.copyOf(bytes.size-1),bytes+byteArrayOf(0),
            bytes.copyOf().also { it[0]=0 },
            bytes.copyOf().also { for(i in 4..7) it[i]=0 },
            bytes.copyOf().also { it[8]=31 },
            bytes.copyOf().also { it[14]=0; it[15]=0 },
            bytes.copyOf().also { it[12]=0; it[13]=1 }
        )
        for (bad in mutations) {
            try { QoaCodec.decode(bad); fail("Accepted malformed QOA") }
            catch (_: IllegalArgumentException) { } catch (_: EOFException) { }
        }
    }
    @Test fun finalSlicePaddingAndHeaderFormatChangesAreRejected() {
        val tiny=resource("tiny.qoa").also { it[it.lastIndex]=(it.last().toInt() or 1).toByte() }
        try { QoaCodec.decode(tiny); fail("Padding ignored") } catch (_: IllegalArgumentException) { }
        val mono=resource("mono.qoa")
        val secondFrame=8+8+16+256*8
        mono[secondFrame]=2
        try { QoaCodec.decode(mono); fail("Channel change ignored") } catch (_: IllegalArgumentException) { }
    }
    @Test fun interruptedOperationsStopWithoutClearingInterrupt() {
        Thread.currentThread().interrupt()
        try {
            try { QoaCodec.encode(PcmAudio(shortArrayOf(1),48000,1)); fail("Interruption ignored") } catch (_: InterruptedException) { }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    @Test fun decodedAllocationIsBoundedBeforeAllocation() {
        val bytes=resource("mono.qoa")
        try { QoaCodec.decode(bytes,100); fail("Limit ignored") } catch (_: IllegalArgumentException) { }
    }
}
