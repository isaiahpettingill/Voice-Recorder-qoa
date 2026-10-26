package org.fossify.voicerecorder.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fossify.voicerecorder.activities.EditSoundActivity
import org.fossify.voicerecorder.models.Recording

/** A visible entry point to editing; never mutates or removes the selected recording. */
class EditFragment(context: Context, attrs: AttributeSet) : MyViewPagerFragment(context, attrs) {
    private val recordings = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var disposed = false
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    init {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            addView(TextView(context).apply { text = "Edit sound"; textSize = 24f })
            addView(TextView(context).apply { text = "Choose a recording. Edits save as a new copy."; textSize = 16f })
            addView(Button(context).apply {
                text = "Open audio file…"; minHeight = dp(48)
                setOnClickListener {
                    (context as? Activity)?.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/octet-stream"))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, 42)
                }
            })
            addView(recordings)
        }
        addView(ScrollView(context).apply { addView(content) }, LayoutParams(-1, -1))
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); onResume() }
    override fun onResume() { disposed = false; loadRecordings() }
    override fun onDestroy() { disposed = true }
    override fun onLoadingEnd(recordings: ArrayList<Recording>) {
        if (disposed) return
        this.recordings.removeAllViews()
        if (recordings.isEmpty()) this.recordings.addView(TextView(context).apply {
            text = "No recordings yet. Record a sound or open an audio file."; textSize = 16f
        })
        recordings.forEach { recording ->
            this.recordings.addView(Button(context).apply {
                text = recording.title; isAllCaps = false; minHeight = dp(56)
                contentDescription = "Edit ${recording.title}"
                setOnClickListener {
                    context.startActivity(Intent(context, EditSoundActivity::class.java)
                        .putExtra("path", recording.path).putExtra("title", recording.title))
                }
            })
        }
    }
}
