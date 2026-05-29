package com.p2petrovich.telegramnewsreader.activities

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.TelegramNewsApplication
import com.p2petrovich.telegramnewsreader.adapters.ChannelAdapter
import com.p2petrovich.telegramnewsreader.adapters.PresetAdapter
import com.p2petrovich.telegramnewsreader.databinding.ActivityMainBinding
import com.p2petrovich.telegramnewsreader.models.Channel
import com.p2petrovich.telegramnewsreader.models.ChannelPreset
import com.p2petrovich.telegramnewsreader.services.AudioPlayerService
import com.p2petrovich.telegramnewsreader.services.NewsService
import com.p2petrovich.telegramnewsreader.services.ProgressCallback
import com.p2petrovich.telegramnewsreader.telegram.TelegramClient
import com.p2petrovich.telegramnewsreader.telegram.TelegramClientManager
import com.p2petrovich.telegramnewsreader.tts.TTSManager
import com.p2petrovich.telegramnewsreader.tts.TTSManagerSingleton
import com.p2petrovich.telegramnewsreader.utils.Deduplicator
import com.p2petrovich.telegramnewsreader.utils.NewsCache
import com.p2petrovich.telegramnewsreader.utils.AiProcessor
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import com.p2petrovich.telegramnewsreader.utils.PresetManager
import com.p2petrovich.telegramnewsreader.utils.SettingsBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var telegramClient: TelegramClient
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var ttsManager: TTSManager
    private lateinit var newsService: NewsService

    // Поле для выбора файла через системный проводник
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(it)?.use { stream ->
                            val jsonString = stream.bufferedReader().use { reader -> reader.readText() }
                            SettingsBackup.importFromJson(this@MainActivity, jsonString)
                        } ?: false
                    } catch (_: Exception) {
                        false
                    }
                }
                if (success) {
                    Toast.makeText(this@MainActivity, "Настройки восстановлены", Toast.LENGTH_SHORT).show()
                    recreate()
                } else {
                    Toast.makeText(this@MainActivity, "Ошибка при чтении файла", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var lastUsedVoice: String? = null
    private var isClientReady = false
    private var isClientReadyCallbackProcessed = false
    private var currentPlaylist: List<File> = emptyList()
    private var currentRealNewsCount: Int = 0
    private var currentNewsFileIndices: Set<Int> = emptySet()
    private var savedDurationInfo: String? = null
    private val pendingPhotos = mutableMapOf<Long, String>()

    private var progressExecutor: ScheduledExecutorService? = null
    private var startTime: Long = 0
    private var totalProgressSteps: Int = 0
    private var currentProgressStep: Int = 0
    private var newsCollectionJob: Job? = null

    private var lastTotalCollected = 0
    private var lastAfterDedup = 0
    private var lastAfterFilter = 0
    private var lastToSynthesize = 0
    private var lastSynthesized = 0
    private var lastSkippedDuplicates = 0
    private var lastAfterAi = 0

    private var activePresetId: String? = null

    // Deduplicator для фильтрации дублей
    private var deduplicator: Deduplicator? = null

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
        private const val PREFS_NAME = "telegram_news_prefs"
    }

    private val timePeriods = arrayOf(
        "10 минут", "20 минут", "30 минут", "1 час", "2 часа",
        "3 часа", "6 часов", "12 часов", "24 часа"
    )
    private val timeValues = arrayOf(0.16, 0.33, 0.5, 1.0, 2.0, 3.0, 6.0, 12.0, 24.0)
    private var currentTimePeriodIndex = 2

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioPlayerService.ACTION_PROGRESS) {
                val cur = intent.getIntExtra(AudioPlayerService.EXTRA_CURRENT_ITEM, 0)
                val total = intent.getIntExtra(AudioPlayerService.EXTRA_TOTAL_ITEMS, 0)
                val isPlaying = intent.getBooleanExtra(AudioPlayerService.EXTRA_IS_PLAYING, false)

                val text = if (total > 0 && cur in 1..total) {
                    if (isPlaying) getString(R.string.status_playing, cur, total)
                    else getString(R.string.status_ready, cur, total)
                } else ""

                val finalText = when {
                    savedDurationInfo != null && text.isNotEmpty() -> "$text\n$savedDurationInfo"
                    text.isEmpty() && savedDurationInfo != null -> savedDurationInfo ?: ""
                    else -> text
                }
                binding.tvStatus.text = finalText

                if (total > 0) {
                    binding.llPlayer.visibility = View.VISIBLE
                    updatePlayerButtons(isPlaying)
                    PreferenceManager.savePlayerIndex(context, cur - 1)
                    PreferenceManager.savePlayerIsPlaying(context, isPlaying)
                }
            }
        }
    }

    private val ttsErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TTSManager.ACTION_TTS_ERROR) {
                val message = intent.getStringExtra(TTSManager.EXTRA_ERROR_MESSAGE) ?: "Ошибка TTS"
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    val currentStatus = binding.tvStatus.text.toString()
                    binding.tvStatus.text = "$message\n$currentStatus"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeResId = TelegramNewsApplication.getThemeResId(this)
        setTheme(themeResId)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.tvStatus.movementMethod = ScrollingMovementMethod.getInstance()

        requestNotificationPermission()
        setDefaultVoiceOnFirstLaunch()

        if (!PreferenceManager.isAuthorized(this)) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        initComponents()
        setupUI()
        setupPresets()
        initializeTelegramClient()

        lastUsedVoice = PreferenceManager.getTtsVoiceName(this)
    }

    // ===================== Deduplication =====================
    private fun getDeduplicator(): Deduplicator {
        if (deduplicator == null) {
            deduplicator = Deduplicator(
                isEnabled = PreferenceManager.isDedupEnabled(this),
                matchThreshold = PreferenceManager.getDedupThreshold(this),
                historySize = PreferenceManager.getDedupHistorySize(this),
                timeWindowMinutes = PreferenceManager.getDedupTimeWindow(this)
            )
        }
        return deduplicator!!
    }

    private fun resetDeduplicator() {
        deduplicator?.reset()
        deduplicator = null
    }

    private fun showDedupSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_dedup_settings, null)
        val cbEnabled = dialogView.findViewById<CheckBox>(R.id.cb_dedup_enabled)
        val sbThreshold = dialogView.findViewById<SeekBar>(R.id.sb_dedup_threshold)
        val tvThresholdValue = dialogView.findViewById<TextView>(R.id.tv_dedup_threshold_value)
        val sbHistorySize = dialogView.findViewById<SeekBar>(R.id.sb_dedup_history_size)
        val tvHistoryValue = dialogView.findViewById<TextView>(R.id.tv_dedup_history_value)
        val sbTimeWindow = dialogView.findViewById<SeekBar>(R.id.sb_dedup_time_window)
        val tvTimeValue = dialogView.findViewById<TextView>(R.id.tv_dedup_time_value)

        val currentEnabled = PreferenceManager.isDedupEnabled(this)
        val currentThreshold = PreferenceManager.getDedupThreshold(this)
        val currentHistorySize = PreferenceManager.getDedupHistorySize(this)
        val currentTimeWindow = PreferenceManager.getDedupTimeWindow(this)

        cbEnabled.isChecked = currentEnabled
        sbThreshold.progress = (currentThreshold * 100).toInt()
        tvThresholdValue.text = getString(R.string.dedup_threshold_percent, (currentThreshold * 100).toInt())
        sbHistorySize.progress = currentHistorySize
        tvHistoryValue.text = getString(R.string.dedup_history_count, currentHistorySize)
        sbTimeWindow.progress = currentTimeWindow
        tvTimeValue.text = getString(R.string.dedup_time_minutes, currentTimeWindow)

        sbThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvThresholdValue.text = getString(R.string.dedup_threshold_percent, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbHistorySize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvHistoryValue.text = getString(R.string.dedup_history_count, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbTimeWindow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTimeValue.text = getString(R.string.dedup_time_minutes, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dedup_settings_title))
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                PreferenceManager.setDedupEnabled(this, cbEnabled.isChecked)
                PreferenceManager.setDedupThreshold(this, sbThreshold.progress / 100f)
                PreferenceManager.setDedupHistorySize(this, sbHistorySize.progress)
                PreferenceManager.setDedupTimeWindow(this, sbTimeWindow.progress)
                // Сбрасываем дедупликатор, чтобы применить новые настройки
                resetDeduplicator()
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                showSettingsDialog()
            }
            .setNegativeButton("Отмена") { _, _ -> showSettingsDialog() }
            .setOnCancelListener { showSettingsDialog() }
            .show()
    }

    // ===================== Вспомогательные методы =====================
    private fun updateStatus(text: String) {
        binding.tvStatus.text = text
    }

    private fun updateNewsCollectionButton() {
        val selectedCount = channelAdapter.getSelectedChannels().size
        binding.btnCollectNews.isEnabled = selectedCount > 0 && isClientReady
        binding.btnCollectNews.text = getString(R.string.collect_news)
    }

    private fun startTimer() {
        stopTimer()
        startTime = System.currentTimeMillis()
        progressExecutor = Executors.newSingleThreadScheduledExecutor()
        progressExecutor?.scheduleWithFixedDelay({
            runOnUiThread { updateETA() }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun stopTimer() {
        progressExecutor?.shutdown()
        progressExecutor = null
    }

    private fun resetCollectionState() {
        newsCollectionJob?.cancel()
        newsCollectionJob = null
        stopTimer()
        resetProgressCounters()
        updateNewsCollectionButton()
        updateUIForReadyClient()

        currentPlaylist = emptyList()
        currentRealNewsCount = 0
        currentNewsFileIndices = emptySet()
        savedDurationInfo = null
        resetDeduplicator()
    }

    private fun showProgressPanels() {
        binding.cardCollectionProgress.visibility = View.VISIBLE
        binding.llNewsPreview.visibility = View.VISIBLE
        binding.llChannelProgress.visibility = View.VISIBLE
    }

    private fun resetProgressCounters() {
        lastTotalCollected = 0
        lastAfterDedup = 0
        lastAfterFilter = 0
        lastToSynthesize = 0
        lastAfterAi = 0
        lastSynthesized = 0
        lastSkippedDuplicates = 0
        currentProgressStep = 0
        totalProgressSteps = 0
        startTime = 0

        binding.progressBarDetailed.progress = 0
        binding.tvProgressPercentage.text = getString(R.string.percentage, 0)
        binding.tvDetailedStatus.text = ""
        binding.tvPipelineStatus.text = ""
        binding.llPipelineStatus.visibility = View.GONE
        binding.tvEta.text = getString(R.string.eta_calculating)
        binding.tvNewsPreview.text = getString(R.string.news_not_collected)
        binding.llChannelProgressList.removeAllViews()
        binding.tvSynthesisStatus.visibility = View.GONE
    }

    private fun updatePipelineStatus() {
        binding.llPipelineStatus.visibility = View.VISIBLE
        val parts = mutableListOf<String>()
        parts.add("Собрано: $lastTotalCollected")

        if (lastAfterDedup > 0) {
            val removed = lastTotalCollected - lastAfterDedup
            if (removed > 0) parts.add("дубли: -$removed")
        }

        if (lastAfterFilter > 0 && lastAfterDedup > 0) {
            val removed = lastAfterDedup - lastAfterFilter
            if (removed > 0) parts.add("спам: -$removed")
        }

        val baseForTrash = when {
            lastAfterFilter > 0 -> lastAfterFilter
            lastAfterDedup > 0 -> lastAfterDedup
            else -> lastTotalCollected
        }

        if (lastToSynthesize > 0) {
            val trashRemoved = baseForTrash - lastToSynthesize
            if (trashRemoved > 0) parts.add("мусор: -$trashRemoved")

            if (lastAfterAi > 0) {
                val aiRemoved = lastToSynthesize - lastAfterAi
                if (aiRemoved > 0) parts.add("ИИ: -$aiRemoved")
                parts.add("к озвучке: $lastAfterAi")
            } else {
                parts.add("к озвучке: $lastToSynthesize")
            }
        }

        if (lastSkippedDuplicates > 0) {
            parts.add("пропущено: $lastSkippedDuplicates")
        }

        binding.tvPipelineStatus.text = parts.joinToString(" → ")

        val finalTarget = when {
            lastAfterAi > 0 -> lastAfterAi
            lastToSynthesize > 0 -> lastToSynthesize
            else -> lastAfterFilter
        }

        if (finalTarget > 0) {
            binding.tvSynthesisStatus.visibility = View.VISIBLE
            binding.tvSynthesisStatus.text = getString(R.string.synthesis_progress, lastSynthesized, finalTarget)
        } else {
            binding.tvSynthesisStatus.visibility = View.GONE
        }
    }

    private fun updateNewsPreview(newsList: List<String>) {
        if (newsList.isEmpty()) {
            binding.tvNewsPreview.text = getString(R.string.news_not_collected)
            return
        }
        val previewText = newsList.take(3).joinToString("\n• ") {
            it.replace(Regex("^\\d{2}:\\d{2}\\s*—\\s*"), "").take(60) + "..."
        }
        binding.tvNewsPreview.text = getString(R.string.news_preview_bullet, previewText)
    }

    private fun updateChannelProgress(channels: List<Channel>) {
        val processedCount = channels.count { it.newMessagesCount >= 0 }
        binding.tvChannelProgress.text = getString(R.string.channels_progress, processedCount, channels.size)

        binding.llChannelProgressList.removeAllViews()
        channels.forEach { channel ->
            val text = if (channel.newMessagesCount >= 0)
                getString(R.string.channel_news_count, channel.newMessagesCount)
            else
                getString(R.string.channel_processing)
            binding.llChannelProgressList.addView(
                TextView(this).apply {
                    this.text = getString(R.string.channel_line, channel.title, text)
                    textSize = 12f
                    setPadding(0, 4, 0, 4)
                }
            )
        }
    }

    private fun updateETA() {
        val elapsedMs = System.currentTimeMillis() - startTime
        val elapsedSec = elapsedMs / 1000

        val elapsedText = when {
            elapsedSec < 60 -> getString(R.string.elapsed_seconds, elapsedSec.toInt())
            else -> {
                val min = (elapsedSec / 60).toInt()
                val sec = (elapsedSec % 60).toInt()
                getString(R.string.elapsed_minutes, min, sec)
            }
        }

        if (elapsedSec < 3 || currentProgressStep <= 0 || totalProgressSteps <= 0) {
            binding.tvEta.text = getString(R.string.collection_status_combined, elapsedText, getString(R.string.eta_calculating))
            return
        }

        val remainingSteps = totalProgressSteps - currentProgressStep

        if (remainingSteps <= 0) {
            binding.tvEta.text = getString(R.string.collection_status_combined, elapsedText, getString(R.string.eta_finishing))
            return
        }

        val msPerStep = elapsedMs.toDouble() / currentProgressStep
        val remainingSec = (msPerStep * remainingSteps / 1000).toLong()

        val etaText = when {
            remainingSec <= 0 -> getString(R.string.eta_finishing)
            remainingSec < 60 -> getString(R.string.eta_seconds, remainingSec.toInt())
            remainingSec < 3600 -> {
                val min = (remainingSec / 60).toInt()
                val sec = (remainingSec % 60).toInt()
                getString(R.string.eta_minutes, min, sec)
            }
            else -> {
                val hours = (remainingSec / 3600).toInt()
                val min = ((remainingSec % 3600) / 60).toInt()
                getString(R.string.eta_hours, hours, min)
            }
        }
        
        binding.tvEta.text = getString(R.string.collection_status_combined, elapsedText, etaText)
    }

    // ===================== Инициализация =====================
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    private fun initComponents() {
        telegramClient = TelegramClientManager.getTelegramClient(this)
        ttsManager = TTSManagerSingleton.getInstance(this)
        newsService = NewsService(telegramClient, ttsManager)

        channelAdapter = ChannelAdapter(
            this,
            onSelectionChanged = { _, _ ->
                updateNewsCollectionButton()
                saveCurrentSelection()
            },
            onHideRequest = { channel -> confirmHideChannel(channel) }
        )

        binding.recyclerChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerChannels.adapter = channelAdapter
    }

    private fun saveCurrentSelection() {
        val selectedIds = channelAdapter.getSelectedChannels().map { it.id }.toSet()
        PresetManager.saveLastSelection(this, selectedIds, currentTimePeriodIndex)

        val activePreset = PresetManager.getActivePreset(this)
        if (activePreset != null && selectedIds != activePreset.channelIds) {
            PresetManager.setActivePresetId(this, null)
            activePresetId = null
            refreshPresetChips()
        }
        updateChannelStats()
    }

    private fun restoreLastSelection() {
        val activePreset = PresetManager.getActivePreset(this)
        if (activePreset != null) {
            activePresetId = activePreset.id
            currentTimePeriodIndex = activePreset.timePeriodIndex
        } else {
            currentTimePeriodIndex = PresetManager.getLastTimePeriodIndex(this)
        }
        updateTimePeriodButton()
    }

    private fun setupPresets() {
        restoreLastSelection()

        binding.btnSavePreset.setOnClickListener {
            val selected = channelAdapter.getSelectedChannels()
            if (selected.isEmpty()) {
                Toast.makeText(this, "Сначала выберите каналы", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showCreatePresetDialog(selected)
        }

        binding.btnManagePresets.setOnClickListener {
            showPresetsManagerDialog()
        }

        binding.btnDeselectAll.setOnClickListener {
            deselectAllChannels()
        }

        refreshPresetChips()
    }

    private fun deselectAllChannels() {
        val allChannels = channelAdapter.getAllChannels()
        val hadSelection = allChannels.any { it.isSelected }
        val hadFilter = channelAdapter.isFilterActive()

        if (!hadSelection && !hadFilter) {
            Toast.makeText(this, "Каналы не выбраны", Toast.LENGTH_SHORT).show()
            return
        }

        allChannels.forEach { it.isSelected = false }
        channelAdapter.clearFilter()

        activePresetId = null
        PresetManager.setActivePresetId(this, null)

        updateNewsCollectionButton()
        saveCurrentSelection()
        refreshPresetChips()

        Toast.makeText(this, "Выбор сброшен", Toast.LENGTH_SHORT).show()
    }

    private fun applyPreset(preset: ChannelPreset) {
        activePresetId = preset.id
        PresetManager.setActivePresetId(this, preset.id)

        currentTimePeriodIndex = preset.timePeriodIndex
        updateTimePeriodButton()

        val allChannels = channelAdapter.getAllChannels()
        allChannels.forEach { ch ->
            ch.isSelected = ch.id in preset.channelIds
        }

        channelAdapter.filterByPreset(preset.channelIds)

        updateNewsCollectionButton()
        PresetManager.saveLastSelection(this, preset.channelIds, preset.timePeriodIndex)
        refreshPresetChips()
        updateChannelStats()
        Toast.makeText(this, getString(R.string.preset_n_applied, preset.name), Toast.LENGTH_SHORT).show()
    }

    private fun applyPresetAndCollect(preset: ChannelPreset) {
        applyPreset(preset)
        binding.root.postDelayed({ collectNews() }, 300)
    }

    private fun refreshPresetChips() {
        val chipGroup = binding.chipGroupPresets
        chipGroup.removeAllViews()

        val presets = PresetManager.getAllPresets(this)
        if (presets.isEmpty()) {
            binding.cardQuickLaunch.visibility = View.GONE
            return
        }

        binding.cardQuickLaunch.visibility = View.VISIBLE
        val activeId = PresetManager.getActivePresetId(this)

        presets.forEach { preset ->
            val chip = Chip(this).apply {
                text = preset.name
                isCheckable = true
                isChecked = preset.id == activeId
                isCloseIconVisible = false

                setOnClickListener {
                    applyPreset(preset)
                }
                setOnLongClickListener {
                    applyPresetAndCollect(preset)
                    true
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun showCreatePresetDialog(selectedChannels: List<Channel>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_preset, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_preset_name)
        val cbSaveTime = dialogView.findViewById<CheckBox>(R.id.cb_save_time_period)
        val tvInfo = dialogView.findViewById<TextView>(R.id.tv_selected_info)

        val channelNames = selectedChannels.take(5).joinToString(", ") { it.title }
        val suffix = if (selectedChannels.size > 5) " и ещё ${selectedChannels.size - 5}" else ""
        tvInfo.text = getString(
            R.string.preset_info,
            selectedChannels.size,
            channelNames,
            suffix,
            timePeriods[currentTimePeriodIndex]
        )

        AlertDialog.Builder(this)
            .setTitle("Сохранить набор каналов")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = etName.text?.toString()?.trim()
                if (name.isNullOrEmpty()) {
                    Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val timePeriod = if (cbSaveTime.isChecked) currentTimePeriodIndex else 2
                val channelIds = selectedChannels.map { it.id }.toSet()

                val preset = PresetManager.createPreset(this, name, channelIds, timePeriod)
                PresetManager.setActivePresetId(this, preset.id)
                activePresetId = preset.id

                refreshPresetChips()
                Toast.makeText(this, getString(R.string.preset_n_saved, name), Toast.LENGTH_SHORT).show()
                showSettingsDialog()
            }
            .setNegativeButton("Отмена") { _, _ -> showSettingsDialog() }
            .setOnCancelListener { showSettingsDialog() }
            .show()
    }

    private fun showPresetsManagerDialog() {
        val presets = PresetManager.getAllPresets(this)

        val dialogView = layoutInflater.inflate(R.layout.dialog_manage_presets, null)
        val recycler = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_presets)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tv_presets_empty)
        recycler.layoutManager = LinearLayoutManager(this)

        if (presets.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }

        val channelNames = channelAdapter.getAllChannels().associate { it.id to it.title }
        val activeId = PresetManager.getActivePresetId(this)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Наборы каналов")
            .setView(dialogView)
            .setNegativeButton("Закрыть", null)
            .create()

        recycler.adapter = PresetAdapter(
            presets = presets,
            activePresetId = activeId,
            channelNames = channelNames,
            timePeriods = timePeriods,
            onPresetSelected = { preset ->
                dialog.dismiss()
                applyPreset(preset)
                showSettingsDialog()
            },
            onPresetDelete = { preset ->
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.preset_delete_confirm, preset.name))
                    .setPositiveButton("Удалить") { _, _ ->
                        PresetManager.deletePreset(this, preset.id)
                        dialog.dismiss()
                        refreshPresetChips()
                        Toast.makeText(this, "Набор удалён", Toast.LENGTH_SHORT).show()
                        showPresetsManagerDialog()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            },
            onPresetEdit = { preset ->
                dialog.dismiss()
                showEditPresetDialog(preset)
            }
        )

        dialog.setOnCancelListener { showSettingsDialog() }

        dialog.show()
    }

    private fun showEditPresetDialog(preset: ChannelPreset) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_preset, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_preset_name)
        val cbSaveTime = dialogView.findViewById<CheckBox>(R.id.cb_save_time_period)
        val tvInfo = dialogView.findViewById<TextView>(R.id.tv_selected_info)

        etName.setText(preset.name)
        cbSaveTime.text = "Обновить период на текущий"
        cbSaveTime.isChecked = false

        val channelNames = channelAdapter.getAllChannels()
            .filter { it.id in preset.channelIds }
            .joinToString(", ") { it.title }
        tvInfo.text = getString(R.string.preset_info_edit, preset.channelIds.size, channelNames)

        val currentSelected = channelAdapter.getSelectedChannels()
        val hasNewSelection = currentSelected.isNotEmpty() &&
                currentSelected.map { it.id }.toSet() != preset.channelIds

        val cbUpdateChannels = CheckBox(this).apply {
            text = getString(R.string.preset_update_channels, currentSelected.size)
            isChecked = false
            visibility = if (hasNewSelection) View.VISIBLE else View.GONE
        }

        val container = dialogView.findViewById(R.id.preset_dialog_container)
            ?: dialogView.findViewById(android.R.id.content)
            ?: findTopLevelViewGroup(dialogView)
        container.addView(cbUpdateChannels)

        AlertDialog.Builder(this)
            .setTitle("Редактировать набор")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = etName.text?.toString()?.trim()
                if (name.isNullOrEmpty()) {
                    Toast.makeText(this, "Введите название", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newChannelIds = if (cbUpdateChannels.isChecked && hasNewSelection)
                    currentSelected.map { it.id }.toSet()
                else preset.channelIds

                val newTimePeriod = if (cbSaveTime.isChecked) currentTimePeriodIndex
                else preset.timePeriodIndex

                val updated = preset.copy(
                    name = name,
                    channelIds = newChannelIds,
                    timePeriodIndex = newTimePeriod
                )
                PresetManager.savePreset(this, updated)
                refreshPresetChips()
                Toast.makeText(this, getString(R.string.preset_n_updated, name), Toast.LENGTH_SHORT).show()
                showPresetsManagerDialog()
            }
            .setNegativeButton("Отмена") { _, _ -> showPresetsManagerDialog() }
            .setOnCancelListener { showPresetsManagerDialog() }
            .show()
    }

    /** Рекурсивный поиск корневого ViewGroup в иерархии view */
    private fun findTopLevelViewGroup(view: View): ViewGroup {
        if (view is ViewGroup) return view
        val parent = view.parent
        if (parent is ViewGroup) return parent
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
    }

    private fun initializeTelegramClient() {
        val readyCallback: () -> Unit = {
            if (!isClientReadyCallbackProcessed) {
                isClientReadyCallbackProcessed = true
                isClientReady = true
                runOnUiThread {
                    telegramClient.onChannelPhotoUpdated = { channelId, path ->
                        runOnUiThread {
                            val idx = channelAdapter.getAllChannels().indexOfFirst { it.id == channelId }
                            if (idx >= 0) channelAdapter.updateChannelPhoto(channelId, path)
                            else pendingPhotos[channelId] = path
                        }
                    }
                    loadChannels()
                    updateUIForReadyClient()
                }
            }
        }

        telegramClient.onClientReady = readyCallback

        telegramClient.onFatalError = { message ->
            runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Ошибка безопасности")
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("Выход") { _, _ -> finish() }
                    .show()
            }
        }

        if (telegramClient.checkAuthState()) {
            readyCallback()
        } else {
            updateStatus("Инициализация Telegram клиента...")
        }
    }

    private fun updateUIForReadyClient() {
        binding.btnCollectNews.isEnabled = channelAdapter.getSelectedChannels().isNotEmpty()
        if (binding.tvStatus.text.isEmpty()) {
            updateStatus("Клиент готов. Выберите каналы.")
        }
    }

    private fun setupUI() {
        binding.btnTimePeriod.setOnClickListener { showTimePeriodDialog() }
        updateTimePeriodButton()

        binding.btnCollectNews.setOnClickListener { collectNews() }

        binding.btnPlay.setOnClickListener {
            if (currentPlaylist.isEmpty()) {
                Toast.makeText(this, "Сначала соберите новости", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startService(
                Intent(this, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_PLAY)
            )
            updatePlayerButtons(true)
        }

        binding.btnPause.setOnClickListener {
            startService(
                Intent(this, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_PAUSE)
            )
            updatePlayerButtons(false)
        }

        binding.btnStop.setOnClickListener {
            startService(
                Intent(this, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_STOP)
            )
            resetPlayerButtons()
            binding.llPlayer.visibility = View.GONE
            binding.tvStatus.text = ""
            savedDurationInfo = null
        }

        binding.btnNext.setOnClickListener {
            startService(
                Intent(this, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_NEXT)
            )
        }

        binding.btnCollectNews.isEnabled = false
        binding.llPlayer.visibility = View.GONE
        binding.btnPlay.isEnabled = false
        binding.btnNext.isEnabled = false
        binding.btnPause.isEnabled = false
    }

    private fun updatePlayerButtons(isPlaying: Boolean) {
        binding.btnPlay.isEnabled = !isPlaying
        binding.btnPause.isEnabled = isPlaying
        binding.btnNext.isEnabled = true
    }

    private fun resetPlayerButtons() {
        binding.btnPlay.isEnabled = true
        binding.btnPause.isEnabled = false
        binding.btnNext.isEnabled = false
    }

    private fun loadChannels() {
        if (!isClientReady) return

        binding.progressBar.visibility = View.VISIBLE
        updateStatus("Загружаем каналы...")

        telegramClient.loadChannels { channels ->
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (channels.isNotEmpty()) {
                    val hiddenUsernames = PreferenceManager.getHiddenUsernames(this)
                    val hiddenIds = PreferenceManager.getHiddenIds(this)
                    val filtered = channels.filterNot { ch ->
                        val username = ch.username
                        (!username.isNullOrBlank() && hiddenUsernames.contains(username)) ||
                                hiddenIds.contains(ch.id.toString())
                    }

                    val activePreset = PresetManager.getActivePreset(this)
                    val savedSelectedIds = activePreset?.channelIds
                        ?: PresetManager.getLastSelectedIds(this)

                    if (savedSelectedIds.isNotEmpty()) {
                        filtered.forEach { ch ->
                            ch.isSelected = ch.id in savedSelectedIds
                        }
                    }

                    channelAdapter.updateChannels(filtered)

                    if (activePreset != null) {
                        channelAdapter.filterByPreset(activePreset.channelIds)
                    }

                    telegramClient.redownloadPendingPhotos()

                    if (pendingPhotos.isNotEmpty()) {
                        pendingPhotos.forEach { (id, p) -> channelAdapter.updateChannelPhoto(id, p) }
                        pendingPhotos.clear()
                    }

                    updateChannelStats()
                    updateNewsCollectionButton()
                    loadInitialNewsForChannels(filtered)
                    refreshPresetChips()
                } else {
                    updateStatus("Каналы не найдены")
                }
            }
        }
    }

    private fun loadInitialNewsForChannels(channels: List<Channel>) {
        if (!isClientReady) return

        lifecycleScope.launch {
            try {
                val newsCounts = newsService.getAllChannelsNewsCount(channels, 0.5)
                channels.forEach { it.newMessagesCount = newsCounts[it.id] ?: 0 }
                runOnUiThread { channelAdapter.refreshVisibleItems() }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading initial news count", e)
            }
        }
    }

    private fun collectNews() {
        if (!isClientReady || !telegramClient.checkAuthState()) {
            Toast.makeText(this, "Telegram клиент не готов", Toast.LENGTH_LONG).show()
            return
        }

        val selectedChannels = channelAdapter.getSelectedChannels()
        if (selectedChannels.isEmpty()) {
            Toast.makeText(this, "Выберите хотя бы один канал", Toast.LENGTH_SHORT).show()
            return
        }

        if (newsCollectionJob?.isActive == true) {
            newsCollectionJob?.cancel()
            updateStatus("Сбор новостей остановлен")
            binding.progressBar.visibility = View.GONE
            binding.btnCollectNews.text = getString(R.string.collect_news)
            binding.btnCollectNews.isEnabled = true
            stopTimer()
            return
        }

        val timeHours = timeValues[currentTimePeriodIndex]
        resetCollectionState()
        showProgressPanels()
        selectedChannels.forEach { it.newMessagesCount = -1 }
        updateChannelProgress(selectedChannels)

        resetProgressCounters()

        // Инициализируем дедупликатор с текущими настройками
        getDeduplicator()

        binding.progressBar.visibility = View.VISIBLE
        binding.btnCollectNews.text = "Остановить"
        binding.btnCollectNews.isEnabled = true
        startTimer()

        newsCollectionJob = lifecycleScope.launch {
            try {
                updateStatus("Собираем новости из ${selectedChannels.size} каналов...")
                updateDetailedProgress("Начинаем сбор новостей...", 0, 100)

                val audio = newsService.collectAndSynthesizePlaylist(
                    channels = selectedChannels,
                    timeHours = timeHours,
                    progressCallback = createProgressCallback(selectedChannels),
                    deduplicator = getDeduplicator()
                )

                stopTimer()

                val durationMin = withContext(Dispatchers.IO) {
                    audio?.let { calcDurationMinutes(it.files) } ?: 0
                }

                lastSkippedDuplicates = getDeduplicator().getSkippedCount()

                runOnUiThread { handleCollectionResult(audio, durationMin) }
            } catch (_: CancellationException) {
                Log.d("MainActivity", "News collection cancelled")
                stopTimer()
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCollectNews.text = getString(R.string.collect_news)
                    binding.btnCollectNews.isEnabled = true
                    updateStatus("Сбор новостей отменен")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error collecting news", e)
                stopTimer()
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCollectNews.text = getString(R.string.collect_news)
                    binding.btnCollectNews.isEnabled = true
                    updateStatus("Ошибка: ${e.message}")
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Вычисление длительности — вызывается ТОЛЬКО из Dispatchers.IO */
    private fun calcDurationMinutes(files: List<File>): Int {
        var totalMs = 0L
        val retriever = android.media.MediaMetadataRetriever()
        files.forEach { file ->
            try {
                retriever.setDataSource(file.absolutePath)
                val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                totalMs += durationStr?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                Log.w("MainActivity", "Error getting duration of ${file.name}", e)
            }
        }
        try { retriever.release() } catch (_: Exception) {}
        return (totalMs / 1000 / 60).toInt()
    }

    private fun createProgressCallback(selectedChannels: List<Channel>): ProgressCallback {
        return object : ProgressCallback {
            override fun onUpdateProgress(status: String, progress: Int, total: Int) {
                runOnUiThread { updateDetailedProgress(status, progress, total) }
            }

            override fun onUpdateCounters(collected: Int, filtered: Int, synthesized: Int) {
                runOnUiThread {
                    lastTotalCollected = collected
                    lastToSynthesize = filtered
                    lastSynthesized = synthesized
                    updatePipelineStatus()
                }
            }

            override fun onUpdateNewsPreview(newsList: List<String>) {
                runOnUiThread { updateNewsPreview(newsList) }
            }

            override fun onUpdateChannelProgress(channels: List<Channel>) {
                runOnUiThread { updateChannelProgress(channels) }
            }

            override fun onChannelProcessed(channel: Channel, messagesCount: Int) {
                runOnUiThread {
                    channel.newMessagesCount = messagesCount
                    updateChannelProgress(selectedChannels)
                }
            }

            override fun onDeduplicationComplete(beforeCount: Int, afterCount: Int) {
                runOnUiThread {
                    lastAfterDedup = afterCount
                    updatePipelineStatus()
                }
            }

            override fun onMessageFiltered(originalCount: Int, filteredCount: Int) {
                runOnUiThread {
                    lastAfterFilter = filteredCount
                    updatePipelineStatus()
                }
            }

            override fun onAiProcessingComplete(beforeCount: Int, afterCount: Int) {
                runOnUiThread {
                    lastToSynthesize = beforeCount
                    lastAfterAi = afterCount
                    updatePipelineStatus()
                }
            }

            override fun onSynthesisStarted(messageCount: Int) {
                runOnUiThread {
                    updateDetailedProgress("Начинаем синтез речи...", 0, 100)
                    totalProgressSteps = messageCount
                    currentProgressStep = 0
                    startTime = System.currentTimeMillis()
                }
            }

            override fun onSynthesisProgress(current: Int, total: Int) {
                runOnUiThread {
                    updateDetailedProgress(getString(R.string.synthesis_started, current, total), current, total)
                    currentProgressStep = current
                    totalProgressSteps = total
                    updateETA()
                }
            }

            override fun onSynthesisCompleted() {
                runOnUiThread {
                    updateDetailedProgress("Синтез завершен", 100, 100)
                    binding.btnCollectNews.isEnabled = true
                    binding.btnCollectNews.text = getString(R.string.collect_news)
                }
            }
        }
    }

    private fun handleCollectionResult(audio: NewsService.AudioPlaylist?, durationMin: Int) {
        binding.progressBar.visibility = View.GONE
        binding.btnCollectNews.text = getString(R.string.collect_news)
        binding.btnCollectNews.isEnabled = true
        updateDetailedProgress("Сбор завершен", 100, 100)

        if (audio != null && audio.files.isNotEmpty()) {
            currentPlaylist = audio.files
            currentRealNewsCount = audio.realNewsCount
            currentNewsFileIndices = audio.newsFileIndices
            lastUsedVoice = PreferenceManager.getTtsVoiceName(this)

            val paths = audio.files.map { it.absolutePath }
            PreferenceManager.savePlaylistPaths(this, paths)

            val baseStatus = getString(R.string.found_news, audio.realNewsCount)
            if (durationMin > 0) {
                savedDurationInfo = getString(R.string.duration_info, durationMin)
                updateStatus("$baseStatus\n$savedDurationInfo")
            } else {
                updateStatus(baseStatus)
            }

            if (lastSkippedDuplicates > 0) {
                Toast.makeText(
                    this,
                    getString(R.string.skipped_duplicates, lastSkippedDuplicates),
                    Toast.LENGTH_LONG
                ).show()
            }

            val arrayPaths = ArrayList(audio.files.map { it.absolutePath })
            startService(Intent(this, AudioPlayerService::class.java).apply {
                action = AudioPlayerService.ACTION_SET_PLAYLIST
                putStringArrayListExtra(AudioPlayerService.EXTRA_FILE_PATHS, arrayPaths)
                putExtra(AudioPlayerService.EXTRA_START_INDEX, 0)
                putExtra(AudioPlayerService.EXTRA_TITLE, "Новости")
                putExtra(AudioPlayerService.EXTRA_REAL_NEWS_COUNT, currentRealNewsCount)
                putExtra(AudioPlayerService.EXTRA_NEWS_FILE_INDICES, currentNewsFileIndices.toIntArray())
            })

            channelAdapter.refreshVisibleItems()
            binding.llPlayer.visibility = View.VISIBLE
            resetPlayerButtons()
            binding.btnPlay.isEnabled = true
            binding.btnNext.isEnabled = true

            Toast.makeText(this, getString(R.string.found_messages, audio.realNewsCount), Toast.LENGTH_SHORT).show()
        } else {
            updateStatus("Новых новостей не найдено")
            Toast.makeText(this, "Новые новости не найдены", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateDetailedProgress(status: String, progress: Int, total: Int) {
        binding.tvDetailedStatus.text = status
        val percentage = if (total > 0) (progress * 100 / total).coerceIn(0, 100) else 0
        binding.progressBarDetailed.progress = percentage
        binding.tvProgressPercentage.text = getString(R.string.percentage, percentage)
    }

    private fun updateChannelStats() {
        val total = channelAdapter.getAllChannels().size
        val selected = channelAdapter.getSelectedChannels().size
        val isFilterActive = channelAdapter.isFilterActive()

        val msg = buildString {
            append(getString(R.string.channels_summary_base, total))
            if (selected > 0) append(getString(R.string.channels_summary_selected, selected))
            if (isFilterActive) append(getString(R.string.channels_summary_filter))
        }

        val statusText = if (isClientReady)
            getString(R.string.select_channels_msg, msg)
        else
            getString(R.string.initializing_msg, msg)
        updateStatus(statusText)
    }

    private fun showTimePeriodDialog() {
        AlertDialog.Builder(this)
            .setTitle("Выберите период времени")
            .setItems(timePeriods) { _, which ->
                currentTimePeriodIndex = which
                updateTimePeriodButton()
                saveCurrentSelection()
            }.show()
    }

    private fun updateTimePeriodButton() {
        binding.btnTimePeriod.text = getString(R.string.time_period, timePeriods[currentTimePeriodIndex])
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btn_ai_settings)?.setOnClickListener {
            dialog.dismiss()
            showAiSettingsDialog()
        }

        dialogView.findViewById<View>(R.id.btn_color_theme)?.setOnClickListener {
            dialog.dismiss()
            showColorThemeDialog()
        }

        dialogView.findViewById<View>(R.id.btn_manage_presets_settings)?.setOnClickListener {
            dialog.dismiss()
            showPresetsManagerDialog()
        }
        dialogView.findViewById<View>(R.id.btn_manage_hidden)?.setOnClickListener {
            dialog.dismiss()
            showHiddenManager()
        }
        dialogView.findViewById<View>(R.id.btn_proxy_settings)?.setOnClickListener {
            dialog.dismiss()
            showProxySettingsDialog()
        }
        dialogView.findViewById<View>(R.id.btn_voice_settings)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, VoiceSelectionActivity::class.java))
        }
        dialogView.findViewById<View>(R.id.btn_dedup_settings)?.setOnClickListener {
            dialog.dismiss()
            showDedupSettingsDialog()
        }
        dialogView.findViewById<View>(R.id.btn_clear_cache)?.setOnClickListener {
            dialog.dismiss()
            val (count, bytes) = NewsCache.getStats(this)
            val sizeMb = bytes / (1024 * 1024)
            AlertDialog.Builder(this)
                .setTitle("Очистить кэш аудио")
                .setMessage(getString(R.string.cache_info, count, sizeMb.toInt()))
                .setPositiveButton("Очистить") { _, _ ->
                    NewsCache.clearAll(this)
                    Toast.makeText(this, "Кэш очищен", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        dialogView.findViewById<View>(R.id.btn_reset_auth)?.setOnClickListener {
            dialog.dismiss()
            showResetAuthConfirmation()
        }
        dialogView.findViewById<View>(R.id.btn_about)?.setOnClickListener {
            dialog.dismiss()
            showAboutDialog()
        }

        dialogView.findViewById<View>(R.id.btn_backup_settings)?.setOnClickListener {
            dialog.dismiss()
            showBackupMenuDialog()
        }

        dialog.show()
    }

    private fun showBackupMenuDialog() {
        val options = arrayOf("Экспортировать настройки (Экспорт)", "Импортировать настройки (Импорт)")

        AlertDialog.Builder(this)
            .setTitle("Резервное копирование")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        lifecycleScope.launch {
                            val createdFileName = withContext(Dispatchers.IO) {
                                SettingsBackup.saveBackupToFile(this@MainActivity)
                            }
                            Toast.makeText(
                                this@MainActivity,
                                if (createdFileName != null) getString(R.string.backup_saved, createdFileName)
                                else "Ошибка при сохранении",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    1 -> {
                        importLauncher.launch(arrayOf("application/json"))
                    }
                }
            }
            .setNegativeButton("Назад") { _, _ -> showSettingsDialog() }
            .show()
    }

    private fun showProxySettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_proxy_settings, null)
        val swEnabled = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_proxy_enabled)
        val etHost = dialogView.findViewById<EditText>(R.id.et_proxy_host)
        val etPort = dialogView.findViewById<EditText>(R.id.et_proxy_port)
        val etSecret = dialogView.findViewById<EditText>(R.id.et_proxy_secret)
        val btnTest = dialogView.findViewById<Button>(R.id.btn_test_proxy)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tv_proxy_status)

        swEnabled.isChecked = PreferenceManager.isProxyEnabled(this)
        etHost.setText(PreferenceManager.getProxyHost(this))
        val port = PreferenceManager.getProxyPort(this)
        etPort.setText(if (port > 0) port.toString() else "")
        etSecret.setText(PreferenceManager.getProxySecret(this))

        val performTest = { h: String, p: Int, s: String ->
            tvStatus.text = getString(R.string.proxy_status_checking)
            tvStatus.setTextColor(Color.GRAY)
            btnTest.isEnabled = false

            telegramClient.testProxy(h, p, s) { ping, error ->
                runOnUiThread {
                    btnTest.isEnabled = true
                    if (ping != null) {
                        tvStatus.text = if (ping == 0.0)
                            getString(R.string.proxy_status_available)
                        else
                            getString(R.string.proxy_status_available_ping, (ping * 1000).toInt())
                        tvStatus.setTextColor("#4CAF50".toColorInt())
                    } else {
                        tvStatus.text = getString(R.string.proxy_status_error, error ?: "timeout")
                        tvStatus.setTextColor(Color.RED)
                    }
                }
            }
        }

        if (swEnabled.isChecked) {
            val h = etHost.text.toString().trim()
            val s = etSecret.text.toString().trim()
            if (h.isNotEmpty() && port > 0 && s.isNotEmpty()) {
                performTest(h, port, s)
            }
        }

        btnTest.setOnClickListener {
            val h = etHost.text.toString().trim()
            val pStr = etPort.text.toString().trim()
            val s = etSecret.text.toString().trim()

            if (h.isEmpty() || pStr.isEmpty() || s.isEmpty()) {
                tvStatus.text = getString(R.string.proxy_status_fill_all)
                tvStatus.setTextColor(Color.RED)
                return@setOnClickListener
            }

            performTest(h, pStr.toIntOrNull() ?: 0, s)
        }

        AlertDialog.Builder(this)
            .setTitle("Настройки MTProto")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                PreferenceManager.setProxyEnabled(this, swEnabled.isChecked)
                PreferenceManager.setProxyHost(this, etHost.text.toString().trim())
                PreferenceManager.setProxyPort(this, etPort.text.toString().toIntOrNull() ?: 0)
                PreferenceManager.setProxySecret(this, etSecret.text.toString().trim())

                telegramClient.applyProxySettings()
                Toast.makeText(this, "Настройки прокси обновлены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена") { _, _ ->
                showSettingsDialog()
            }
            .setOnCancelListener { showSettingsDialog() }
            .show()
    }

    private fun showAiSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ai_settings, null)
        val switchEnabled = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_ai_enabled)
        val spinnerModel = dialogView.findViewById<android.widget.Spinner>(R.id.spinner_ai_model)
        val spinnerStyle = dialogView.findViewById<android.widget.Spinner>(R.id.spinner_ai_style)
        val btnTestManual = dialogView.findViewById<android.widget.Button>(R.id.btn_test_ai_model)
        val tvStatusManual = dialogView.findViewById<android.widget.TextView>(R.id.tv_ai_test_status)

        // Кнопку ручной проверки скрываем, так как проверка будет автоматической
        btnTestManual?.visibility = View.GONE
        tvStatusManual?.visibility = View.GONE

        switchEnabled.isChecked = PreferenceManager.isAiSummaryEnabled(this)

        val models = listOf(
            "z-ai/glm-4.5-air:free" to "GLM-4.5 Air (Хороший русский) — FREE",
            "openai/gpt-oss-120b:free" to "GPT-OSS 120B (Сильный) — FREE",
            "nvidia/nemotron-3-super-120b-a12b:free" to "Nemotron-3 Super (Стабильный) — FREE",
            "deepseek/deepseek-v4-flash:free" to "DeepSeek V4 Flash — FREE",
            "google/gemini-flash-1.5-free" to "Gemini 1.5 Flash (Google) — FREE",
            "meta-llama/llama-3.3-70b-instruct:free" to "Llama 3.3 70B — FREE"
        )

        val modelStatuses = mutableMapOf<String, String>()
        models.forEach { modelStatuses[it.first] = "⏳" }

        val modelAdapter = object : android.widget.ArrayAdapter<Pair<String, String>>(
            this, R.layout.item_model_status, models
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return createViewFromResource(position, convertView, parent, R.layout.item_model_status)
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return createViewFromResource(position, convertView, parent, R.layout.item_model_status)
            }
            private fun createViewFromResource(position: Int, convertView: View?, parent: ViewGroup, resource: Int): View {
                val view = convertView ?: layoutInflater.inflate(resource, parent, false)
                val item = getItem(position)
                val tvName = view.findViewById<TextView>(R.id.tv_model_name)
                val tvStatus = view.findViewById<TextView>(R.id.tv_model_status)
                
                tvName.text = item?.second
                val status = modelStatuses[item?.first] ?: "⏳"
                tvStatus.text = status
                
                when (status) {
                    "✅" -> tvStatus.setTextColor(Color.GREEN)
                    "❌" -> tvStatus.setTextColor(Color.RED)
                    else -> tvStatus.setTextColor(Color.GRAY)
                }
                return view
            }
        }
        spinnerModel.adapter = modelAdapter

        // Запускаем фоновую проверку всех моделей сразу
        lifecycleScope.launch {
            models.forEach { modelPair ->
                val result = AiProcessor.testModelAvailability(modelPair.first, this@MainActivity)
                modelStatuses[modelPair.first] = if (result.first) "✅" else "❌"
                modelAdapter.notifyDataSetChanged()
            }
        }

        val styles = listOf(
            "minimal" to "Только чистка от мусора",
            "balanced" to "Сбалансированное сжатие (в 2 раза)",
            "extreme" to "Радио-молния (1 предложение)"
        )

        val styleAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, styles.map { it.second })
        styleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerStyle.adapter = styleAdapter

        val currentModel = PreferenceManager.getAiModel(this)
        val currentStyle = PreferenceManager.getAiStyle(this)

        val modelIdx = models.indexOfFirst { it.first == currentModel }.coerceAtLeast(0)
        spinnerModel.setSelection(modelIdx)

        val styleIdx = styles.indexOfFirst { it.first == currentStyle }.coerceAtLeast(0)
        spinnerStyle.setSelection(styleIdx)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                PreferenceManager.setAiSummaryEnabled(this, switchEnabled.isChecked)
                val selectedModel = models[spinnerModel.selectedItemPosition].first
                val selectedStyle = styles[spinnerStyle.selectedItemPosition].first
                PreferenceManager.setAiModel(this, selectedModel)
                PreferenceManager.setAiStyle(this, selectedStyle)
                Toast.makeText(this, "Настройки ИИ сохранены", Toast.LENGTH_SHORT).show()
                showSettingsDialog()
            }
            .setNegativeButton("Отмена") { _, _ -> showSettingsDialog() }
            .setOnCancelListener { showSettingsDialog() }
            .show()
    }

    private fun showColorThemeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_color_theme, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val rgColorTheme = dialogView.findViewById<android.widget.RadioGroup>(R.id.rg_color_theme)

        val currentTheme = PreferenceManager.getColorTheme(this)
        when (currentTheme) {
            "teal" -> rgColorTheme?.check(R.id.rb_theme_teal)
            "light" -> rgColorTheme?.check(R.id.rb_theme_light)
            else -> rgColorTheme?.check(R.id.rb_theme_purple)
        }

        rgColorTheme?.setOnCheckedChangeListener { _, checkedId ->
            val selectedTheme = when (checkedId) {
                R.id.rb_theme_teal -> "teal"
                R.id.rb_theme_light -> "light"
                else -> "purple"
            }
            PreferenceManager.saveColorTheme(this, selectedTheme)
            dialog.dismiss()
            recreate()
        }

        dialog.setOnCancelListener { showSettingsDialog() }

        dialog.show()
    }

    private fun showAboutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "1.0" }
        dialogView.findViewById<TextView>(R.id.tvVersion).text = getString(R.string.version, versionName)
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Закрыть") { _, _ -> showSettingsDialog() }
            .setOnCancelListener { showSettingsDialog() }
            .show()
    }

    private fun showResetAuthConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Сброс авторизации")
            .setMessage("Все данные будут удалены. Продолжить?")
            .setPositiveButton("Да") { _, _ -> resetAuthorization() }
            .setNegativeButton("Отмена") { _, _ -> showSettingsDialog() }
            .setOnCancelListener { showSettingsDialog() }
            .show()
    }

    private fun resetAuthorization() {
        binding.btnCollectNews.isEnabled = false
        updateStatus("Сброс авторизации...")

        TelegramClientManager.logoutAndClearDb(this) {
            PreferenceManager.clearAll(this)
            TTSManagerSingleton.clearInstance()
            runOnUiThread {
                Toast.makeText(this, "Авторизация сброшена", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, AuthActivity::class.java))
                finish()
            }
        }
    }

    private fun confirmHideChannel(channel: Channel) {
        AlertDialog.Builder(this)
            .setTitle("Скрыть канал")
            .setMessage(getString(R.string.hide_channel_confirm, channel.title))
            .setPositiveButton("Скрыть") { _, _ -> hideChannel(channel) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun hideChannel(channel: Channel) {
        val username = channel.username
        if (!username.isNullOrBlank()) {
            val set = PreferenceManager.getHiddenUsernames(this)
            set.add(username)
            PreferenceManager.saveHiddenUsernames(this, set)
        } else {
            val set = PreferenceManager.getHiddenIds(this)
            set.add(channel.id.toString())
            PreferenceManager.saveHiddenIds(this, set)
            PreferenceManager.saveHiddenTitleForId(this, channel.id, channel.title)
        }

        channelAdapter.updateChannels(
            channelAdapter.getAllChannels().filterNot { it.id == channel.id }
        )
        updateChannelStats()
        Toast.makeText(this, "Канал скрыт", Toast.LENGTH_SHORT).show()
    }

    private fun showHiddenManager() {
        val hiddenUsernames = PreferenceManager.getHiddenUsernames(this)
        val hiddenIds = PreferenceManager.getHiddenIds(this)

        val items = mutableListOf<String>()
        val meta = mutableListOf<Pair<String, String>>()

        hiddenUsernames.forEach { u -> items.add("@$u"); meta.add("u" to u) }
        hiddenIds.forEach { idStr ->
            val title = idStr.toLongOrNull()
                ?.let { PreferenceManager.getHiddenTitleForId(this, it) } ?: "Канал"
            items.add(title); meta.add("i" to idStr)
        }

        if (items.isEmpty()) {
            Toast.makeText(this, "Скрытых каналов нет", Toast.LENGTH_SHORT).show()
            return
        }

        val checked = BooleanArray(items.size)
        AlertDialog.Builder(this)
            .setTitle("Скрытые каналы")
            .setMultiChoiceItems(items.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Вернуть выбранные") { _, _ ->
                val toRestoreU = mutableSetOf<String>()
                val toRestoreI = mutableSetOf<String>()
                meta.forEachIndexed { i, (type, key) ->
                    if (checked[i]) {
                        if (type == "u") toRestoreU.add(key) else toRestoreI.add(key)
                    }
                }
                if (toRestoreU.isEmpty() && toRestoreI.isEmpty()) return@setPositiveButton
                hiddenUsernames.removeAll(toRestoreU)
                hiddenIds.removeAll(toRestoreI)
                PreferenceManager.saveHiddenUsernames(this, hiddenUsernames)
                PreferenceManager.saveHiddenIds(this, hiddenIds)
                loadChannels()
                Toast.makeText(this, "Каналы возвращены", Toast.LENGTH_SHORT).show()
                showSettingsDialog()
            }
            .setNegativeButton("Отмена") { _, _ -> showSettingsDialog() }
            .setOnCancelListener { showSettingsDialog() }
            .show()
    }

    private fun setDefaultVoiceOnFirstLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean("is_first_app_launch", true)) {
            if (PreferenceManager.getTtsVoiceName(this) == null) {
                PreferenceManager.saveTtsVoiceName(this, "ru-ru-x-ruf-network")
            }
            prefs.edit { putBoolean("is_first_app_launch", false) }
        }
    }


    override fun onResume() {
        super.onResume()
        if (::ttsManager.isInitialized) {
            val currentVoice = PreferenceManager.getTtsVoiceName(this)
            if (lastUsedVoice != null && lastUsedVoice != currentVoice) {
                updateStatus("Голос изменен. Пересоберите новости.")
            }
            ttsManager.refreshVoice()
        }

        if (currentPlaylist.isEmpty()) {
            val savedPaths = PreferenceManager.getPlaylistPaths(this)
            if (savedPaths.isNotEmpty()) {
                val existingFiles = savedPaths.filter { File(it).exists() }
                if (existingFiles.isNotEmpty()) {
                    currentPlaylist = existingFiles.map { File(it) }
                    binding.llPlayer.visibility = View.VISIBLE
                    binding.btnPlay.isEnabled = true
                    binding.btnNext.isEnabled = true

                    val savedIndex = PreferenceManager.getPlayerIndex(this)
                    val savedIsPlaying = PreferenceManager.getPlayerIsPlaying(this)

                    if (savedIsPlaying) {
                        updatePlayerButtons(true)
                        updateStatus(getString(R.string.resume_playing, savedIndex + 1, currentPlaylist.size))
                    } else {
                        updatePlayerButtons(false)
                        updateStatus(getString(R.string.resume_ready, savedIndex + 1, currentPlaylist.size))
                    }
                } else {
                    PreferenceManager.clearPlayerState(this)
                }
            }
        }

        try {
            startService(Intent(this, AudioPlayerService::class.java).apply {
                action = AudioPlayerService.ACTION_REQUEST_STATUS
            })
        } catch (_: Exception) {}
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, progressReceiver,
            IntentFilter(AudioPlayerService.ACTION_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, ttsErrorReceiver,
            IntentFilter(TTSManager.ACTION_TTS_ERROR),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(progressReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(ttsErrorReceiver) } catch (_: Exception) {}
        stopTimer()
    }
}
