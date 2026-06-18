package com.p2petrovich.telegramnewsreader.managers

import android.app.Activity
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager

/**
 * Контроллер для настройки дедупликации.
 * Выносит логику диалога настройки иSeekBar из MainActivity.
 */
class DeduplicationController(
    private val activity: Activity,
    private val onSettingsSaved: () -> Unit,
    private val onOpenSettings: () -> Unit
) {

    fun showDedupSettingsDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_dedup_settings, null)
        val cbEnabled = dialogView.findViewById<CheckBox>(R.id.cb_dedup_enabled)
        val sbThreshold = dialogView.findViewById<SeekBar>(R.id.sb_dedup_threshold)
        val tvThresholdValue = dialogView.findViewById<TextView>(R.id.tv_dedup_threshold_value)
        val sbHistorySize = dialogView.findViewById<SeekBar>(R.id.sb_dedup_history_size)
        val tvHistoryValue = dialogView.findViewById<TextView>(R.id.tv_dedup_history_value)
        val sbTimeWindow = dialogView.findViewById<SeekBar>(R.id.sb_dedup_time_window)
        val tvTimeValue = dialogView.findViewById<TextView>(R.id.tv_dedup_time_value)

        val currentEnabled = PreferenceManager.isDedupEnabled(activity)
        val currentThreshold = PreferenceManager.getDedupThreshold(activity)
        val currentHistorySize = PreferenceManager.getDedupHistorySize(activity)
        val currentTimeWindowMinutes = PreferenceManager.getDedupTimeWindow(activity)
        val currentTimeWindowHours = (currentTimeWindowMinutes / 60).coerceIn(0, 24)

        cbEnabled.isChecked = currentEnabled
        sbThreshold.progress = (currentThreshold * 100).toInt()
        tvThresholdValue.text = activity.getString(R.string.dedup_threshold_percent, (currentThreshold * 100).toInt())
        sbHistorySize.progress = currentHistorySize
        tvHistoryValue.text = activity.getString(R.string.dedup_history_count, currentHistorySize)
        sbTimeWindow.progress = currentTimeWindowHours
        tvTimeValue.text = activity.getString(R.string.dedup_time_hours, currentTimeWindowHours)

        sbThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvThresholdValue.text = activity.getString(R.string.dedup_threshold_percent, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbHistorySize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvHistoryValue.text = activity.getString(R.string.dedup_history_count, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbTimeWindow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTimeValue.text = activity.getString(R.string.dedup_time_hours, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dedup_settings_title))
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                PreferenceManager.setDedupEnabled(activity, cbEnabled.isChecked)
                PreferenceManager.setDedupThreshold(activity, sbThreshold.progress / 100f)
                PreferenceManager.setDedupHistorySize(activity, sbHistorySize.progress)
                PreferenceManager.setDedupTimeWindow(activity, sbTimeWindow.progress * 60)
                
                onSettingsSaved()
                Toast.makeText(activity, activity.getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                onOpenSettings()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onOpenSettings() }
            .setOnCancelListener { onOpenSettings() }
            .show()
    }
}
