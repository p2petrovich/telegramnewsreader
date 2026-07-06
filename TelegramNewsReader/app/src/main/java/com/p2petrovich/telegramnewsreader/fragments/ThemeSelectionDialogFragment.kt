package com.p2petrovich.telegramnewsreader.fragments

import android.app.Dialog
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager

class ThemeSelectionDialogFragment : DialogFragment() {

    private var onDismissListener: (() -> Unit)? = null

    fun setOnDismissListener(listener: () -> Unit) {
        onDismissListener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_color_theme, null)
        val dialog = AlertDialog.Builder(activity).setView(dialogView).create()

        val rgColorTheme = dialogView.findViewById<RadioGroup>(R.id.rg_color_theme)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save_theme)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel_theme)

        val currentTheme = PreferenceManager.getColorTheme(activity)
        when (currentTheme) {
            "teal" -> rgColorTheme?.check(R.id.rb_theme_teal)
            "light" -> rgColorTheme?.check(R.id.rb_theme_light)
            else -> rgColorTheme?.check(R.id.rb_theme_purple)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val selectedTheme = when (rgColorTheme?.checkedRadioButtonId) {
                R.id.rb_theme_teal -> "teal"
                R.id.rb_theme_light -> "light"
                else -> "purple"
            }
            PreferenceManager.saveColorTheme(activity, selectedTheme)
            dialog.dismiss()
            activity.recreate()
        }

        dialog.setOnCancelListener { onDismissListener?.invoke() }

        return dialog
    }
}
