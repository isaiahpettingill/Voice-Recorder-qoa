package org.fossify.voicerecorder.extensions

import android.app.Activity
import android.provider.DocumentsContract
import android.view.WindowManager
import androidx.core.net.toUri
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.MONTH_SECONDS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.models.Recording
import java.io.File

fun Activity.setKeepScreenAwake(keepScreenOn: Boolean) {
    if (keepScreenOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

fun BaseSimpleActivity.ensureStoragePermission(callback: (result: Boolean) -> Unit) {
    val folder = recordingsDirectory()
    if (runCatching { folder != null && folder.canRead() && folder.canWrite() }.getOrDefault(false)) {
        callback(true)
    } else {
        launchFolderPicker(config.saveRecordingsFolder) { selected ->
            if (selected != null) config.saveRecordingsFolder = selected
            callback(selected != null)
        }
    }
}

fun BaseSimpleActivity.launchFolderPicker(currentPath: String, callback: (newPath: String?) -> Unit) {
    (this as? org.fossify.voicerecorder.activities.SimpleActivity)?.chooseRecordingFolder(currentPath, callback)
        ?: callback(null)
}

fun BaseSimpleActivity.deleteRecordings(
    recordingsToRemove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) {
    ensureBackgroundThread {
        var success = true
        recordingsToRemove.forEach { recording ->
            val deleted = runCatching {
                if (recording.path.startsWith("content://")) {
                    DocumentsContract.deleteDocument(contentResolver, recording.path.toUri())
                } else File(recording.path).delete()
            }.getOrDefault(false)
            if (!deleted) success = false
        }
        callback(success)
    }
}

fun BaseSimpleActivity.trashRecordings(
    recordingsToMove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) = moveRecordings(
    recordingsToMove = recordingsToMove,
    sourceParent = config.saveRecordingsFolder,
    destinationParent = getOrCreateTrashFolder(),
    callback = callback
)

fun BaseSimpleActivity.restoreRecordings(
    recordingsToRestore: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) = moveRecordings(
    recordingsToMove = recordingsToRestore,
    sourceParent = getOrCreateTrashFolder(),
    destinationParent = config.saveRecordingsFolder,
    callback = callback
)

fun BaseSimpleActivity.moveRecordings(
    recordingsToMove: Collection<Recording>,
    sourceParent: String,
    destinationParent: String,
    callback: (success: Boolean) -> Unit
) {
    ensureBackgroundThread {
        if (sourceParent == destinationParent) {
            callback(true)
            return@ensureBackgroundThread
        }
        if (recordingsDirectory(destinationParent)?.canWrite() != true) {
            callback(false)
            return@ensureBackgroundThread
        }
        var allSucceeded = true
        recordingsToMove.forEach { recording ->
            var destination: android.net.Uri? = null
            val copied = runCatching {
                destination = createRecordingDocument(recording.title, destinationParent)
                    ?: error("Cannot create destination recording")
                val source = if (recording.path.startsWith("content://")) {
                    contentResolver.openInputStream(recording.path.toUri())
                } else File(recording.path).inputStream()
                checkNotNull(source).use { input ->
                    checkNotNull(contentResolver.openOutputStream(destination!!, "w")).use { output ->
                        val copiedBytes = input.copyTo(output)
                        output.flush()
                        // When the provider supplies a size, reject incomplete copies before deletion.
                        if (recording.size > 0) check(copiedBytes == recording.size.toLong()) { "Incomplete recording copy" }
                    }
                }
            }.isSuccess
            if (!copied) {
                destination?.let { uri -> runCatching {
                    if (uri.scheme == "content") DocumentsContract.deleteDocument(contentResolver, uri)
                    else File(checkNotNull(uri.path)).delete()
                } }
                allSucceeded = false
            } else {
                // Copy first and delete only after both streams close successfully. A failed deletion
                // leaves two valid copies rather than risking the only recording.
                val removed = runCatching {
                    if (recording.path.startsWith("content://")) DocumentsContract.deleteDocument(contentResolver, recording.path.toUri())
                    else File(recording.path).delete()
                }.getOrDefault(false)
                if (!removed) allSucceeded = false
            }
        }
        callback(allSucceeded)
    }
}

fun BaseSimpleActivity.deleteTrashedRecordings() {
    deleteRecordings(getAllRecordings(trashed = true)) {}
}

fun BaseSimpleActivity.deleteExpiredTrashedRecordings() {
    if (
        config.useRecycleBin &&
        config.lastRecycleBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000
    ) {
        config.lastRecycleBinCheck = System.currentTimeMillis()
        ensureBackgroundThread {
            try {
                val recordingsToRemove = getAllRecordings(trashed = true)
                    .filter { it.timestamp < System.currentTimeMillis() - MONTH_SECONDS * 1000L }
                if (recordingsToRemove.isNotEmpty()) {
                    deleteRecordings(recordingsToRemove) {}
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
