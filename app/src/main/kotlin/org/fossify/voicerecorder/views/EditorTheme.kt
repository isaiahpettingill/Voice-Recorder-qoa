package org.fossify.voicerecorder.views

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.graphics.ColorUtils
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor

/** Programmatic controls must use the same colors as Fossify's themed XML widgets. */
fun View.applyEditorTheme() {
    val text = context.getProperTextColor()
    val primary = context.getProperPrimaryColor()
    val surface = context.getProperBackgroundColor()
    when (this) {
        is CompoundButton -> { setTextColor(text); buttonTintList = ColorStateList.valueOf(primary) }
        is Button -> {
            val prominent = this.text.toString() == "Save QOA copy"
            val background = if (prominent) primary else ColorUtils.blendARGB(surface, primary, .12f)
            setTextColor(if (prominent) {
                if (ColorUtils.calculateContrast(android.graphics.Color.WHITE, background) >=
                    ColorUtils.calculateContrast(android.graphics.Color.BLACK, background)) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            } else text)
            backgroundTintList = ColorStateList.valueOf(background)
            isAllCaps = false
        }
        is EditText -> { setTextColor(text); setHintTextColor(ColorUtils.setAlphaComponent(text, 170)); backgroundTintList = ColorStateList.valueOf(primary) }
        is TextView -> setTextColor(text)
        is SeekBar -> { progressTintList = ColorStateList.valueOf(primary); thumbTintList = ColorStateList.valueOf(primary) }
    }
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).applyEditorTheme()
}

class PresetAdapter(context: Context, labels: List<String>) : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, labels) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        super.getView(position, convertView, parent).also { it.applyEditorTheme() }
    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        super.getDropDownView(position, convertView, parent).also {
            it.setBackgroundColor(context.getProperBackgroundColor()); it.applyEditorTheme()
        }
}
