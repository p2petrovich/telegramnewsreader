package com.p2petrovich.telegramnewsreader.managers

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.adapters.ChannelAdapter
import com.p2petrovich.telegramnewsreader.adapters.PresetAdapter
import com.p2petrovich.telegramnewsreader.databinding.ActivityMainBinding
import com.p2petrovich.telegramnewsreader.models.Channel
import com.p2petrovich.telegramnewsreader.models.ChannelPreset
import com.p2petrovich.telegramnewsreader.utils.PresetManager

/**
 * Контроллер для управления пресетами каналов.
 * Выносит логику диалогов и ChipGroup из MainActivity.
 */
class PresetController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val channelAdapter: ChannelAdapter,
    private val timePeriods: Array<String>,
    private val onPresetApplied: (ChannelPreset) -> Unit,
    private val onPresetAndCollect: (ChannelPreset) -> Unit,
    private val onSelectionSaved: () -> Unit
) {
    private val TAG = "PresetController"

    fun setup(currentTimePeriodIndex: () -> Int) {
        binding.btnSavePreset.setOnClickListener {
            val selected = channelAdapter.getSelectedChannels()
            if (selected.isEmpty()) {
                Toast.makeText(activity, activity.getString(R.string.select_channels_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showCreatePresetDialog(selected, currentTimePeriodIndex())
        }

        binding.btnManagePresets.setOnClickListener {
            showPresetsManagerDialog()
        }

        refreshPresetChips()
    }

    fun refreshPresetChips() {
        val chipGroup = binding.chipGroupPresets
        chipGroup.removeAllViews()

        val presets = PresetManager.getAllPresets(activity)
        if (presets.isEmpty()) {
            binding.cardQuickLaunch.visibility = View.GONE
            return
        }

        binding.cardQuickLaunch.visibility = View.VISIBLE
        val activeId = PresetManager.getActivePresetId(activity)

        presets.forEach { preset ->
            val chip = Chip(activity).apply {
                text = preset.name
                isCheckable = true
                isChecked = preset.id == activeId
                isCloseIconVisible = false

                setOnClickListener {
                    onPresetApplied(preset)
                }
                setOnLongClickListener {
                    onPresetAndCollect(preset)
                    true
                }
            }
            chipGroup.addView(chip)
        }
    }

    fun showCreatePresetDialog(selectedChannels: List<Channel>, currentTimePeriodIndex: Int) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_create_preset, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_preset_name)
        val spinnerTime = dialogView.findViewById<Spinner>(R.id.spinner_preset_time)
        val tvInfo = dialogView.findViewById<TextView>(R.id.tv_selected_info)

        val timeAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, timePeriods)
        timeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTime.adapter = timeAdapter
        spinnerTime.setSelection(currentTimePeriodIndex)

        val channelNames = selectedChannels.take(5).joinToString(", ") { it.title }
        val suffix = if (selectedChannels.size > 5) activity.getString(R.string.and_more_n, selectedChannels.size - 5) else ""
        tvInfo.text = activity.getString(
            R.string.preset_info,
            selectedChannels.size,
            channelNames,
            suffix,
            timePeriods[currentTimePeriodIndex]
        )

        AlertDialog.Builder(activity)
            .setTitle(R.string.save_preset)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = etName.text?.toString()?.trim()
                if (name.isNullOrEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.enter_name), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val timePeriod = spinnerTime.selectedItemPosition
                val channelIds = selectedChannels.map { it.id }.toSet()

                val preset = PresetManager.createPreset(activity, name, channelIds, timePeriod)
                PresetManager.setActivePresetId(activity, preset.id)

                refreshPresetChips()
                onSelectionSaved()
                Toast.makeText(activity, activity.getString(R.string.preset_n_saved, name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun showPresetsManagerDialog(onOpenSettings: (() -> Unit)? = null) {
        val presets = PresetManager.getAllPresets(activity)

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_manage_presets, null)
        val recycler = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_presets)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tv_presets_empty)
        recycler.layoutManager = LinearLayoutManager(activity)

        if (presets.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }

        val channelNames = channelAdapter.getAllChannels().associate { it.id to it.title }
        val activeId = PresetManager.getActivePresetId(activity)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.channel_presets)
            .setView(dialogView)
            .setNegativeButton(android.R.string.ok, null)
            .create()

        recycler.adapter = PresetAdapter(
            presets = presets,
            activePresetId = activeId,
            channelNames = channelNames,
            timePeriods = timePeriods,
            onPresetSelected = { preset ->
                dialog.dismiss()
                onPresetApplied(preset)
                onOpenSettings?.invoke()
            },
            onPresetDelete = { preset ->
                AlertDialog.Builder(activity)
                    .setMessage(activity.getString(R.string.preset_delete_confirm, preset.name))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        PresetManager.deletePreset(activity, preset.id)
                        dialog.dismiss()
                        refreshPresetChips()
                        Toast.makeText(activity, activity.getString(R.string.preset_deleted), Toast.LENGTH_SHORT).show()
                        showPresetsManagerDialog(onOpenSettings)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
            onPresetEdit = { preset ->
                dialog.dismiss()
                showEditPresetDialog(preset, onOpenSettings)
            }
        )

        dialog.setOnCancelListener { onOpenSettings?.invoke() }
        dialog.show()
    }

    private fun showEditPresetDialog(preset: ChannelPreset, onOpenSettings: (() -> Unit)?) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_create_preset, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_preset_name)
        val spinnerTime = dialogView.findViewById<Spinner>(R.id.spinner_preset_time)
        val tvInfo = dialogView.findViewById<TextView>(R.id.tv_selected_info)

        val timeAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, timePeriods)
        timeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTime.adapter = timeAdapter
        spinnerTime.setSelection(preset.timePeriodIndex)

        etName.setText(preset.name)

        val channelNames = channelAdapter.getAllChannels()
            .filter { it.id in preset.channelIds }
            .joinToString(", ") { it.title }
        tvInfo.text = activity.getString(R.string.preset_info_edit, preset.channelIds.size, channelNames)

        val currentSelected = channelAdapter.getSelectedChannels()
        val hasNewSelection = currentSelected.isNotEmpty() &&
                currentSelected.map { it.id }.toSet() != preset.channelIds

        val cbUpdateChannels = CheckBox(activity).apply {
            text = activity.getString(R.string.preset_update_channels, currentSelected.size)
            isChecked = false
            visibility = if (hasNewSelection) View.VISIBLE else View.GONE
        }

        val container = dialogView.findViewById<LinearLayout>(R.id.preset_dialog_container)
            ?: dialogView.findViewById<ViewGroup>(android.R.id.content)
            ?: findTopLevelViewGroup(dialogView)
        container.addView(cbUpdateChannels)

        AlertDialog.Builder(activity)
            .setTitle(R.string.edit)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = etName.text?.toString()?.trim()
                if (name.isNullOrEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.enter_name), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newChannelIds = if (cbUpdateChannels.isChecked && hasNewSelection)
                    currentSelected.map { it.id }.toSet()
                else preset.channelIds

                val newTimePeriod = spinnerTime.selectedItemPosition

                val updated = preset.copy(
                    name = name,
                    channelIds = newChannelIds,
                    timePeriodIndex = newTimePeriod
                )
                PresetManager.savePreset(activity, updated)
                refreshPresetChips()
                Toast.makeText(activity, activity.getString(R.string.preset_n_updated, name), Toast.LENGTH_SHORT).show()
                showPresetsManagerDialog(onOpenSettings)
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnCancelListener { showPresetsManagerDialog(onOpenSettings) }
            .show()
    }

    private fun findTopLevelViewGroup(view: View): ViewGroup {
        if (view is ViewGroup) return view
        val parent = view.parent
        if (parent is ViewGroup) return parent
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
    }
}
