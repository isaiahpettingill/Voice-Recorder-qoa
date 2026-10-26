package org.fossify.voicerecorder

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.voicerecorder.activities.MainActivity
import org.fossify.voicerecorder.audio.AudioFiles
import org.fossify.voicerecorder.audio.PcmAudio
import org.fossify.voicerecorder.audio.QoaCodec
import org.fossify.voicerecorder.extensions.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SharedStorageWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri("us.josemancharo.voicerecorderqoa.storage.tests", "root")
    @Before fun grantTestMicrophonePermission() {
        val command = "pm grant ${context.packageName} android.permission.RECORD_AUDIO"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).use { it.readBytes() }
    }
    private fun fixture() = QoaCodec.encode(PcmAudio(ShortArray(1600) { (it % 100 * 200).toShort() }, 16000, 1))
    private fun withFolder(block: () -> Unit) {
        val old = context.config.saveRecordingsFolder
        context.config.saveRecordingsFolder = tree.toString()
        try { block() } finally {
            context.recordingsDirectory()?.listFiles()?.forEach { it.delete() }
            context.config.saveRecordingsFolder = old
        }
    }

    @Test fun opaqueGenericMimeQoaCreateListReopenAndRename() = withFolder {
        val uri = requireNotNull(context.createRecordingDocument("sound.qoa"))
        assertEquals("content", uri.scheme)
        assertTrue(DocumentsContract.getDocumentId(uri).startsWith("opaque:"))
        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(fixture()) }
        assertEquals("application/octet-stream", context.contentResolver.getType(uri))
        val entries = context.getAllRecordings()
        assertEquals(1, entries.size)
        assertEquals(uri.toString(), entries.single().path)
        assertTrue(context.hasRecordings())
        assertEquals(1600, AudioFiles.readForEditing(context, entries.single().path).samples.size)
        // New Context / Config object must reuse the saved opaque URI, not a reconstructed path.
        val reopened = context.createPackageContext(context.packageName, 0)
        assertEquals(tree.toString(), reopened.config.saveRecordingsFolder)
        assertEquals(1, reopened.getAllRecordings().size)
        val renamed = DocumentsContract.renameDocument(context.contentResolver, uri, "renamed.qoa")!!
        assertEquals("renamed.qoa", context.getAllRecordings().single().title)
        assertTrue(AudioFiles.isQoa(context, renamed.toString()))
        runCatching { DocumentsContract.renameDocument(context.contentResolver, renamed, "reject.qoa") }
        assertEquals("renamed.qoa", context.getAllRecordings().single().title)
        assertTrue(AudioFiles.isQoa(context, renamed.toString()))
    }

    @Test fun childTrashDirectoryStaysDistinctAndRoundTrips() = withFolder {
        val uri = requireNotNull(context.createRecordingDocument("keep.qoa"))
        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(fixture()) }
        val trash = context.getOrCreateTrashFolder()
        assertNotEquals(tree.toString(), trash)
        assertEquals(".trash", context.recordingsDirectory(trash)?.name)
        assertEquals(0, context.recordingsDirectory(trash)!!.listFiles().size)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var latch = CountDownLatch(1)
            var succeeded = false
            scenario.onActivity { activity -> activity.trashRecordings(context.getAllRecordings()) { succeeded = it; latch.countDown() } }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertTrue(succeeded)
            assertTrue(context.getAllRecordings().isEmpty())
            val trashed = context.getAllRecordings(true)
            assertEquals(1, trashed.size)
            assertTrue(AudioFiles.isQoa(context, trashed.single().path))
            latch = CountDownLatch(1)
            scenario.onActivity { activity -> activity.restoreRecordings(trashed) { succeeded = it; latch.countDown() } }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertTrue(succeeded)
            assertEquals(1, context.getAllRecordings().size)
            assertTrue(context.getAllRecordings(true).isEmpty())
            latch = CountDownLatch(1)
            scenario.onActivity { activity -> activity.deleteRecordings(context.getAllRecordings()) { succeeded = it; latch.countDown() } }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertTrue(succeeded)
            assertFalse(context.hasRecordings())
        }
    }

    @Test fun outputOpenFailureCleansDestinationButKeepsSource() = withFolder {
        val uri = requireNotNull(context.createRecordingDocument("only.qoa"))
        val bytes = fixture()
        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
        val rejectedDirectory = requireNotNull(context.recordingsDirectory()?.createDirectory("Reject output"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val latch = CountDownLatch(1)
            var succeeded = true
            scenario.onActivity { activity -> activity.moveRecordings(context.getAllRecordings(), tree.toString(),
                rejectedDirectory.uri.toString()) { succeeded = it; latch.countDown() } }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertFalse(succeeded)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertEquals(0, rejectedDirectory.listFiles().size)
            assertEquals(1, context.getAllRecordings().size)
        }
    }

    @Test fun rejectedDestinationPreservesOnlyRecordingAndInvalidUriIsSafe() = withFolder {
        val uri = requireNotNull(context.createRecordingDocument("only.qoa"))
        val bytes = fixture()
        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val latch = CountDownLatch(1)
            var succeeded = true
            scenario.onActivity { activity -> activity.moveRecordings(context.getAllRecordings(), tree.toString(),
                "content://invalid.provider/tree/missing") { succeeded = it; latch.countDown() } }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertFalse(succeeded)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
        assertNull(context.recordingsDirectory("content://invalid.provider/tree/missing"))
        assertNull(context.createRecordingDocument("../unsafe.qoa"))
        assertNull(context.createRecordingDocument("bad\\name.qoa"))
        assertEquals(1, context.getAllRecordings().size)
    }
}
