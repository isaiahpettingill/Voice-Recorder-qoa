package org.fossify.voicerecorder.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getFilenameExtension
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.extensions.renameFile
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.databinding.DialogRenameRecordingBinding
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.Recording
import org.greenrobot.eventbus.EventBus
import java.io.File

class RenameRecordingDialog(
    val activity: BaseSimpleActivity,
    val recording: Recording,
    val callback: () -> Unit
) {
    init {
        val binding = DialogRenameRecordingBinding.inflate(activity.layoutInflater).apply {
            renameRecordingTitle.setText(recording.title.substringBeforeLast('.'))
        }
        val view = binding.root

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(
                    view = view,
                    dialog = this,
                    titleId = org.fossify.commons.R.string.rename
                ) { alertDialog ->
                    alertDialog.showKeyboard(binding.renameRecordingTitle)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newTitle = binding.renameRecordingTitle.value
                        if (newTitle.isEmpty()) {
                            activity.toast(org.fossify.commons.R.string.empty_name)
                            return@setOnClickListener
                        }

                        if (!newTitle.isAValidFilename()) {
                            activity.toast(org.fossify.commons.R.string.invalid_name)
                            return@setOnClickListener
                        }

                        ensureBackgroundThread {
                            if (recording.path.startsWith("content://")) {
                                renameRecording(recording, newTitle)
                            } else {
                                renameRecordingLegacy(recording, newTitle)
                            }

                            activity.runOnUiThread {
                                callback()
                                alertDialog.dismiss()
                            }
                        }
                    }
                }
            }
    }

    private fun renameRecording(recording: Recording, newTitle: String) {
        val oldExtension = recording.title.getFilenameExtension()
        val newDisplayName = "${newTitle.removeSuffix(".$oldExtension")}.$oldExtension"

        try {
            val uri = android.net.Uri.parse(recording.path)
            val renamed = android.provider.DocumentsContract.renameDocument(activity.contentResolver, uri, newDisplayName)
            checkNotNull(renamed) { "Could not rename recording. Check folder access." }
            EventBus.getDefault().post(Events.RecordingCompleted())
        } catch (e: Exception) {
            activity.showErrorToast(e)
        }
    }

    private fun renameRecordingLegacy(recording: Recording, newTitle: String) {
        val oldExtension = recording.title.getFilenameExtension()
        val oldPath = recording.path
        val newFilename = "${newTitle.removeSuffix(".$oldExtension")}.$oldExtension"
        val newPath = File(oldPath.getParentPath(), newFilename).absolutePath
        activity.renameFile(oldPath, newPath, false)
    }
}
