package org.fossify.voicerecorder

import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.voicerecorder.extensions.createRecordingDocument
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecordingNameReservationTest {
    @Test fun concurrentCopiesWithSameNameNeverOverwrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "name-reservation-${System.nanoTime()}").apply { mkdirs() }
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until 8).map { index -> executor.submit<String> {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                val uri = checkNotNull(context.createRecordingDocument("Same take.qoa", folder.path))
                checkNotNull(context.contentResolver.openOutputStream(uri, "w")).use { it.write(index) }
                uri.toString()
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val names = jobs.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(8, names.toSet().size)
            val files = folder.listFiles().orEmpty()
            assertEquals(8, files.size)
            assertEquals((0 until 8).toSet(), files.map { it.readBytes().single().toInt() }.toSet())
        } finally {
            start.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            folder.deleteRecursively()
        }
    }
}
