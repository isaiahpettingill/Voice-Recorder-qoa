package org.fossify.voicerecorder.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import org.fossify.voicerecorder.extensions.config
import org.fossify.commons.extensions.toast
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.helpers.REPOSITORY_NAME

open class SimpleActivity : BaseSimpleActivity() {
    private var folderPickerPending = false
    private var folderCallback: ((String?) -> Unit)? = null
    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        folderPickerPending = false
        val callback = folderCallback
        folderCallback = null
        val data = result.data
        val uri = data?.data
        if (result.resultCode != RESULT_OK || uri == null) {
            callback?.invoke(null)
        } else {
            try {
                require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 && flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                contentResolver.takePersistableUriPermission(uri, flags)
                val folder = requireNotNull(DocumentFile.fromTreeUri(this, uri))
                require(folder.isDirectory && folder.canRead() && folder.canWrite())
                if (callback != null) callback(uri.toString())
                else toast("Folder access granted. Select the folder again to change where recordings are saved.") // Never bypass migration confirmation after recreation.
            } catch (error: Exception) {
                toast("Cannot use this folder. Choose a writable folder in shared storage.")
                callback?.invoke(null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderPickerPending = savedInstanceState?.getBoolean("recordingFolderPickerPending") ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("recordingFolderPickerPending", folderPickerPending)
        super.onSaveInstanceState(outState)
    }

    fun chooseRecordingFolder(current: String, callback: (String?) -> Unit) {
        folderCallback = callback
        if (folderPickerPending) return // Reattach after recreation; do not launch a second picker.
        folderPickerPending = true
        folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            if (current.startsWith("content://")) putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(current))
        })
    }

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher_red,
        R.mipmap.ic_launcher_pink,
        R.mipmap.ic_launcher_purple,
        R.mipmap.ic_launcher_deep_purple,
        R.mipmap.ic_launcher_indigo,
        R.mipmap.ic_launcher_blue,
        R.mipmap.ic_launcher_light_blue,
        R.mipmap.ic_launcher_cyan,
        R.mipmap.ic_launcher_teal,
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_light_green,
        R.mipmap.ic_launcher_lime,
        R.mipmap.ic_launcher_yellow,
        R.mipmap.ic_launcher_amber,
        R.mipmap.ic_launcher_orange,
        R.mipmap.ic_launcher_deep_orange,
        R.mipmap.ic_launcher_brown,
        R.mipmap.ic_launcher_blue_grey,
        R.mipmap.ic_launcher_grey_black
    )

    override fun getAppLauncherName() = getString(R.string.qoa_app_name)

    override fun getRepositoryName() = REPOSITORY_NAME
}
