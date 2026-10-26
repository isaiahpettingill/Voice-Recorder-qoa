package org.fossify.voicerecorder.extensions

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.graphics.createBitmap
import androidx.documentfile.provider.DocumentFile
import org.fossify.commons.extensions.createFirstParentTreeUri
import org.fossify.commons.extensions.createSAFDirectorySdk30
import org.fossify.commons.extensions.getDocumentSdk30
import org.fossify.commons.extensions.getDoesFilePathExistSdk30
import org.fossify.commons.extensions.getDuration
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getSAFDocumentId
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isAudioFast
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isRPlus
import org.fossify.voicerecorder.audio.AudioFiles
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.helpers.Config
import org.fossify.voicerecorder.helpers.DEFAULT_RECORDINGS_FOLDER
import org.fossify.voicerecorder.helpers.IS_RECORDING
import org.fossify.voicerecorder.helpers.MyWidgetRecordDisplayProvider
import org.fossify.voicerecorder.helpers.TOGGLE_WIDGET_UI
import org.fossify.voicerecorder.models.Recording
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToLong

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.trashFolder: String
    get() = if (config.saveRecordingsFolder.startsWith("content://")) {
        runCatching { recordingsDirectory()?.findFile(".trash")?.uri?.toString() }.getOrNull().orEmpty()
    } else "${config.saveRecordingsFolder}/.trash"

/** Keep provider document IDs opaque; never reinterpret a content URI as a disk path. */
fun Context.recordingsDirectory(folder: String = config.saveRecordingsFolder): DocumentFile? = runCatching {
    if (folder.startsWith("content://")) {
        val uri = Uri.parse(folder)
        if (!DocumentsContract.isTreeUri(uri)) return@runCatching null
        DocumentFile.fromTreeUri(this, uri)?.takeIf { it.isDirectory && it.canRead() }
    } else if (folder.isNotBlank()) {
        // Prefer a previously granted SAF path during migration from older versions.
        val granted = if (isRPlus()) runCatching { getDocumentSdk30(folder) }.getOrNull() else null
        granted?.takeIf { it.isDirectory && it.canRead() } ?: File(folder).let { local ->
            if (local.isDirectory && local.canRead()) DocumentFile.fromFile(local) else null
        }
    } else null
}.getOrNull()

private val recordingDocumentLock = Any()

fun Context.createRecordingDocument(name: String, folder: String = config.saveRecordingsFolder): Uri? = synchronized(recordingDocumentLock) { runCatching {
    require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name)
    val directory = recordingsDirectory(folder) ?: return@runCatching null
    if (!directory.canWrite()) return@runCatching null
    // Do not replace an existing recording. Providers can otherwise truncate or silently rename it.
    var candidate = name
    var suffix = 1
    while (directory.findFile(candidate) != null) {
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        candidate = "$stem (${suffix++})" + if (extension.isEmpty()) "" else ".$extension"
    }
    directory.createFile(if (name.endsWith(".qoa", true)) "audio/x-qoa" else name.getMimeType(), candidate)?.uri
}.getOrNull() }

fun Context.drawableToBitmap(drawable: Drawable): Bitmap {
    val size = (60 * resources.displayMetrics.density).toInt()
    val mutableBitmap = createBitmap(size, size)
    val canvas = Canvas(mutableBitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return mutableBitmap
}

fun Context.updateWidgets(isRecording: Boolean) {
    val widgetIDs = AppWidgetManager.getInstance(applicationContext)
        ?.getAppWidgetIds(
            ComponentName(
                applicationContext,
                MyWidgetRecordDisplayProvider::class.java
            )
        ) ?: return

    if (widgetIDs.isNotEmpty()) {
        Intent(applicationContext, MyWidgetRecordDisplayProvider::class.java).apply {
            action = TOGGLE_WIDGET_UI
            putExtra(IS_RECORDING, isRecording)
            sendBroadcast(this)
        }
    }
}

fun Context.getOrCreateTrashFolder(): String {
    if (config.saveRecordingsFolder.startsWith("content://")) {
        val root = recordingsDirectory() ?: return ""
        return runCatching {
            val existing = root.findFile(".trash")
            val directory = existing?.takeIf { it.isDirectory } ?: if (existing == null) root.createDirectory(".trash") else null
            directory?.uri?.toString().orEmpty()
        }.getOrDefault("")
    }
    val folder = File(trashFolder)
    if (!folder.exists() && !folder.mkdirs()) return ""
    return folder.absolutePath
}

fun Context.getDefaultRecordingsFolder(): String {
    val defaultPath = getDefaultRecordingsRelativePath()
    return "$internalStoragePath/$defaultPath"
}

fun Context.getDefaultRecordingsRelativePath(): String {
    return if (isQPlus()) {
        "${Environment.DIRECTORY_MUSIC}/$DEFAULT_RECORDINGS_FOLDER"
    } else {
        getString(R.string.app_name)
    }
}

fun Context.hasRecordings(): Boolean = runCatching {
    recordingsDirectory()?.listFiles()?.any { it.isAudioRecording() } ?: false
}.getOrDefault(false)

fun Context.getAllRecordings(trashed: Boolean = false): ArrayList<Recording> {
    return if (isRPlus() || config.saveRecordingsFolder.startsWith("content://")) {
        val recordings = arrayListOf<Recording>()
        recordings.addAll(getRecordings(trashed))
        if (trashed) {
            // Return recordings trashed using MediaStore, this won't be needed in the future
            @Suppress("DEPRECATION")
            recordings.addAll(getMediaStoreTrashedRecordings())
        }

        recordings
    } else {
        getLegacyRecordings(trashed)
    }
}

private fun Context.getRecordings(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = if (trashed) trashFolder else config.saveRecordingsFolder
    val files = runCatching { recordingsDirectory(folder)?.listFiles() }.getOrNull() ?: return recordings
    files.forEach { file ->
        runCatching {
            if (file.isFile && file.isAudioRecording()) recordings.add(readRecordingFromFile(file))
        }

    }

    return recordings
}

@Deprecated(
    message = "Use getRecordings instead. This method is only here for backward compatibility.",
    replaceWith = ReplaceWith("getRecordings(trashed = true)")
)
private fun Context.getMediaStoreTrashedRecordings(): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = config.saveRecordingsFolder
    val documentFiles = runCatching { recordingsDirectory(folder)?.listFiles() }.getOrNull() ?: return recordings
    documentFiles.forEach { file ->
        if (file.isTrashedMediaStoreRecording()) {
            val recording = readRecordingFromFile(file)
            recordings.add(
                recording.copy(
                    title = "^\\.trashed-\\d+-".toRegex().replace(file.name!!, "")
                )
            )
        }
    }

    return recordings
}

private fun Context.getLegacyRecordings(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = if (trashed) {
        trashFolder
    } else {
        config.saveRecordingsFolder
    }
    val files = File(folder).listFiles() ?: return recordings

    files.filter { (it.isAudioFast() || it.extension.equals("qoa", true)) }.forEach {
        val id = it.hashCode()
        val title = it.name
        val path = it.absolutePath
        val timestamp = it.lastModified()
        val duration = if (it.extension.equals("qoa", true)) runCatching { AudioFiles.qoaDuration(this, it.absolutePath) }.getOrDefault(0) else getDuration(it.absolutePath) ?: 0
        val size = it.length().toInt()
        recordings.add(
            Recording(
                id = id,
                title = title,
                path = path,
                timestamp = timestamp,
                duration = duration,
                size = size
            )
        )
    }
    return recordings
}

private fun Context.readRecordingFromFile(file: DocumentFile): Recording {
    val id = file.uri.toString().hashCode()
    val title = file.name!!
    val path = if (file.uri.scheme == "file") file.uri.path.orEmpty() else file.uri.toString()
    val timestamp = file.lastModified()
    val duration = if (file.name?.endsWith(".qoa", true) == true) runCatching { AudioFiles.qoaDuration(this, path).toLong() }.getOrDefault(0L) else getDurationFromUri(file.uri)
    val size = file.length().toInt()
    return Recording(
        id = id,
        title = title,
        path = path,
        timestamp = timestamp,
        duration = duration.toInt(),
        size = size
    )
}

private fun Context.getDurationFromUri(uri: Uri): Long {
    return try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
            (time.toLong() / 1000.toDouble()).roundToLong()
        } finally { retriever.release() }
    } catch (e: Exception) {
        0L
    }
}

// move to commons in the future
fun Context.getFormattedFilename(): String {
    val pattern = config.filenamePattern
    val calendar = Calendar.getInstance()

    val year = calendar.get(Calendar.YEAR).toString()
    val month = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.MONTH) + 1)
    val day = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.DAY_OF_MONTH))
    val hour = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.HOUR_OF_DAY))
    val minute = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.MINUTE))
    val second = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.SECOND))

    return pattern
        .replace("%Y", year, false)
        .replace("%M", month, false)
        .replace("%D", day, false)
        .replace("%h", hour, false)
        .replace("%m", minute, false)
        .replace("%s", second, false)
}
