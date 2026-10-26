package org.fossify.voicerecorder.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.fossify.commons.extensions.getDocumentFile
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getLaunchIntent
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.isPathOnSD
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SplashActivity
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.getFormattedFilename
import org.fossify.voicerecorder.extensions.updateWidgets
import org.fossify.voicerecorder.helpers.CANCEL_RECORDING
import org.fossify.voicerecorder.helpers.EXTENSION_QOA
import org.fossify.voicerecorder.recorder.QoaRecorder
import org.fossify.voicerecorder.extensions.createRecordingDocument
import org.fossify.voicerecorder.helpers.EXTENSION_MP3
import org.fossify.voicerecorder.helpers.GET_RECORDER_INFO
import org.fossify.voicerecorder.helpers.RECORDER_RUNNING_NOTIF_ID
import org.fossify.voicerecorder.helpers.RECORDING_PAUSED
import org.fossify.voicerecorder.helpers.RECORDING_RUNNING
import org.fossify.voicerecorder.helpers.RECORDING_STOPPED
import org.fossify.voicerecorder.helpers.STOP_AMPLITUDE_UPDATE
import org.fossify.voicerecorder.helpers.TOGGLE_PAUSE
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.recorder.MediaRecorderWrapper
import org.fossify.voicerecorder.recorder.Mp3Recorder
import org.fossify.voicerecorder.recorder.Recorder
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.util.Timer
import java.util.TimerTask

class RecorderService : Service() {
    companion object {
        var isRunning = false

        private const val AMPLITUDE_UPDATE_MS = 75L
    }


    private var recordingPath = ""
    private var resultUri: Uri? = null

    private var duration = 0
    private var status = RECORDING_STOPPED
    private var durationTimer = Timer()
    private var amplitudeTimer = Timer()
    private var recorder: Recorder? = null
    private var pendingFinalizers = 0
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent.action) {
            GET_RECORDER_INFO -> broadcastRecorderInfo()
            STOP_AMPLITUDE_UPDATE -> amplitudeTimer.cancel()
            TOGGLE_PAUSE -> togglePause()
            CANCEL_RECORDING -> cancelRecording()
            org.fossify.voicerecorder.helpers.SAVE_RECORDING -> stopRecording()
            else -> startRecording()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        stopRecording()
        isRunning = false
        updateWidgets(false)
    }

    // mp4 output format with aac encoding should produce good enough m4a files according to https://stackoverflow.com/a/33054794/1967672
    @SuppressLint("DiscouragedApi")
    private fun startRecording() {
        isRunning = true
        updateWidgets(true)
        if (recorder != null) {
            return
        }

        val name = "${getFormattedFilename()}.${config.getExtension()}"
        val usesDocuments = config.saveRecordingsFolder.startsWith("content://") || isRPlus()
        recordingPath = if (usesDocuments) name else File(config.saveRecordingsFolder, name).absolutePath
        resultUri = null

        try {
            recorder = if (config.extension == EXTENSION_QOA) {
                QoaRecorder(this, name)
            } else if (recordMp3()) {
                Mp3Recorder(this)
            } else {
                MediaRecorderWrapper(this)
            }

            if (recorder is QoaRecorder) {
                // QOA captures privately; choose its final name at Stop, including edits made while recording.
            } else if (usesDocuments) {
                val fileUri = checkNotNull(createRecordingDocument(name)) { "Could not create recording." }
                resultUri = fileUri
                contentResolver.openFileDescriptor(fileUri, "w")!!
                    .use { recorder?.setOutputFile(it) }
            } else if (isPathOnSD(recordingPath)) {
                var document = getDocumentFile(recordingPath.getParentPath())
                document = document?.createFile("", recordingPath.getFilenameFromPath())
                check(document != null) { "Failed to create document on SD Card" }
                resultUri = document.uri
                contentResolver.openFileDescriptor(document.uri, "w")!!
                    .use { recorder?.setOutputFile(it) }
            } else {
                recorder?.setOutputFile(recordingPath)
                resultUri = FileProvider.getUriForFile(
                    this, "${BuildConfig.APPLICATION_ID}.provider", File(recordingPath)
                )
            }

            recorder?.prepare()
            recorder?.start()
            duration = 0
            status = RECORDING_RUNNING
            broadcastRecorderInfo()
            startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())

            durationTimer = Timer()
            durationTimer.scheduleAtFixedRate(getDurationUpdateTask(), 1000, 1000)

            startAmplitudeUpdates()
        } catch (e: Exception) {
            showErrorToast(e)
            stopRecording()
        }
    }

    private fun stopRecording() = finishRecording(cancel = false)

    private fun cancelRecording() {
        finishRecording(cancel = true)
    }

    private fun finishRecording(cancel: Boolean) {
        durationTimer.cancel()
        amplitudeTimer.cancel()
        status = RECORDING_STOPPED
        val active = recorder ?: run {
            if (!destroyed && pendingFinalizers == 0) stopSelf()
            return
        }
        pendingFinalizers++
        isRunning = false
        updateWidgets(false)
        recorder = null // One finalizer; onDestroy and repeated Stop cannot finalize twice.
        if (!destroyed) runCatching { startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification()) }
        val completedFolder = config.saveRecordingsFolder
        var completedPath = recordingPath
        var completedUri = resultUri
        var documentOutput = completedUri?.scheme == "content" && completedUri.authority != "${BuildConfig.APPLICATION_ID}.provider"
        if (active is QoaRecorder) {
            active.freezeName()
            if (cancel) active.discard()
        }
        ensureBackgroundThread {
            try {
            var failure: Exception? = null
            try {
                active.stop()
                if (!cancel && active is QoaRecorder) {
                    val name = active.finalName()
                    val destination = checkNotNull(createRecordingDocument(name, completedFolder)) { "Could not create recording." }
                    completedUri = destination
                    completedPath = destination.path.orEmpty()
                    documentOutput = destination.scheme == "content" && destination.authority != "${BuildConfig.APPLICATION_ID}.provider"
                    contentResolver.openFileDescriptor(destination, "w")!!.use { active.setOutputFile(it) }
                }
            } catch (error: Exception) { failure = error }
            finally {
                try { active.release() } catch (error: Exception) {
                    // Prefer the error containing recovery instructions.
                    failure = error
                }
            }
            if (cancel || failure != null) {
                try {
                    if (documentOutput && completedUri != null) DocumentsContract.deleteDocument(contentResolver, checkNotNull(completedUri))
                    else if (active !is QoaRecorder || completedUri != null) File(completedUri?.takeIf { it.scheme == "file" }?.path ?: completedPath).delete()
                } catch (error: Exception) { showErrorToast(error) }
                if (!cancel) failure?.let { showErrorToast(it) }
            } else {
                scanRecording(completedPath, completedUri)
                if (active is QoaRecorder) {
                    try { active.renderPresetCopy(completedFolder)?.let { recordingSavedSuccessfully(it) } }
                    catch (error: Exception) { showErrorToast(IllegalStateException("Original saved. Effects remain pending in recovery: ${error.message}", error)) }
                }
            }
            } catch (error: Exception) {
                runCatching { showErrorToast(error) }
            } finally {
            android.os.Handler(mainLooper).post {
                pendingFinalizers--
                if (!destroyed && recorder == null && pendingFinalizers == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                EventBus.getDefault().post(Events.RecordingCompleted())
            }
            }
        }
    }

    private fun broadcastRecorderInfo() {
        broadcastDuration()
        broadcastStatus()
        startAmplitudeUpdates()
    }

    @SuppressLint("DiscouragedApi")
    private fun startAmplitudeUpdates() {
        amplitudeTimer.cancel()
        amplitudeTimer = Timer()
        amplitudeTimer.scheduleAtFixedRate(getAmplitudeUpdateTask(), 0, AMPLITUDE_UPDATE_MS)
    }

    @SuppressLint("NewApi")
    private fun togglePause() {
        try {
            if (status == RECORDING_RUNNING) {
                recorder?.pause()
                status = RECORDING_PAUSED
            } else if (status == RECORDING_PAUSED) {
                recorder?.resume()
                status = RECORDING_RUNNING
            }
            broadcastStatus()
            startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun scanRecording(recordingPath: String, resultUri: Uri?) {
        if (recordingPath.endsWith(".qoa", true) || (resultUri?.scheme == "content" && resultUri.authority != "${BuildConfig.APPLICATION_ID}.provider")) {
            resultUri?.let { recordingSavedSuccessfully(it) }
            return
        }
        MediaScannerConnection.scanFile(
            this,
            arrayOf(recordingPath),
            arrayOf(recordingPath.getMimeType())
        ) { _, uri ->
            if (uri == null) {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                return@scanFile
            }

            recordingSavedSuccessfully(resultUri ?: uri)
        }
    }

    private fun recordingSavedSuccessfully(savedUri: Uri) {
        toast(R.string.recording_saved_successfully)
        EventBus.getDefault().post(Events.RecordingSaved(savedUri))
    }

    private fun getDurationUpdateTask() = object : TimerTask() {
        override fun run() {
            if (status == RECORDING_RUNNING) {
                duration++
                broadcastDuration()
            }
        }
    }

    private fun getAmplitudeUpdateTask() = object : TimerTask() {
        override fun run() {
            if (recorder != null) {
                try {
                    EventBus.getDefault()
                        .post(Events.RecordingAmplitude(recorder!!.getMaxAmplitude()))
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun showNotification(): Notification {
        val channelId = "simple_recorder"
        val label = getString(R.string.app_name)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        NotificationChannel(channelId, label, NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            notificationManager.createNotificationChannel(this)
        }

        val icon = R.drawable.ic_graphic_eq_vector
        val title = label
        val visibility = NotificationCompat.VISIBILITY_PUBLIC
        var text = if (status == RECORDING_STOPPED && pendingFinalizers > 0) "Saving and processing recording" else getString(R.string.recording)
        if (status == RECORDING_PAUSED) {
            text += " (${getString(R.string.paused)})"
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(getOpenAppIntent())
            .setPriority(NotificationManager.IMPORTANCE_DEFAULT)
            .setVisibility(visibility)
            .setSound(null)
            .setOngoing(true)
            .setAutoCancel(true)

        return builder.build()
    }

    private fun getOpenAppIntent(): PendingIntent {
        val intent = getLaunchIntent() ?: Intent(this, SplashActivity::class.java)
        return PendingIntent.getActivity(
            this,
            RECORDER_RUNNING_NOTIF_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun broadcastDuration() {
        EventBus.getDefault().post(Events.RecordingDuration(duration))
    }

    private fun broadcastStatus() {
        EventBus.getDefault().post(Events.RecordingStatus(status))
    }

    private fun recordMp3(): Boolean {
        return config.extension == EXTENSION_MP3
    }
}
