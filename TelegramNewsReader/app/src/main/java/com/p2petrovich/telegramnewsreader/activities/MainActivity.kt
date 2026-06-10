package com.p2petrovich.telegramnewsreader.activities

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.TelegramNewsApplication
import com.p2petrovich.telegramnewsreader.adapters.ChannelAdapter
import com.p2petrovich.telegramnewsreader.adapters.PresetAdapter
import com.p2petrovich.telegramnewsreader.adapters.ProxyAdapter
import com.p2petrovich.telegramnewsreader.databinding.ActivityMainBinding
import com.p2petrovich.telegramnewsreader.models.Channel
import com.p2petrovich.telegramnewsreader.models.ChannelPreset
import com.p2petrovich.telegramnewsreader.models.ProxyEntry
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
import com.p2petrovich.telegramnewsreader.utils.UpdateChecker
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import com.p2petrovich.telegramnewsreader.utils.PresetManager
import com.p2petrovich.telegramnewsreader.utils.SettingsBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            lifecycleScope.launch {
                val success = try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(it)?.use { stream ->
                            val jsonString = stream.bufferedReader().use { reader -> reader.readText() }
                            SettingsBackup.importFromJson(this@MainActivity, jsonString)
                        } ?: false
                    }
                } catch (_: Exception) {
                    false
                }
                if (success) {
                    Toast.makeText(this@MainActivity, getString(R.string.settings_restored), Toast.LENGTH_SHORT).show()
                    recreate()
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.file_read_error), Toast.LENGTH_SHORT).show()
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

    // Deduplicator
    private var deduplicator: Deduplicator? = null

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
        private const val PREFS_NAME = "telegram_news_prefs"
    }

    private val timePeriods by lazy {
        arrayOf(
            getString(R.string.time_10m), getString(R.string.time_20m), getString(R.string.time_30m),
            getString(R.string.time_1h), getString(R.string.time_2h), getString(R.string.time_3h),
            getString(R.string.time_6h), getString(R.string.time_12h), getString(R.string.time_24h)
        )
    }
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
                val message = intent.getStringExtra(TTSManager.EXTRA_ERROR_MESSAGE) ?: getString(R.string.tts_error)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    val currentStatus = binding.tvStatus.text.toString()
                    binding.tvStatus.text = getString(R.string.status_combined, message, currentStatus)
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

        //     GitHub (throttle:   )
        UpdateChecker.check(this)
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
        Log.d("MainActivity", "resetDeduplicator called")
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
        val currentTimeWindowMinutes = PreferenceManager.getDedupTimeWindow(this)
        val currentTimeWindowHours = (currentTimeWindowMinutes / 60).coerceIn(0, 24)

        cbEnabled.isChecked = currentEnabled
        sbThreshold.progress = (currentThreshold * 100).toInt()
        tvThresholdValue.text = getString(R.string.dedup_threshold_percent, (currentThreshold * 100).toInt())
        sbHistorySize.progress = currentHistorySize
        tvHistoryValue.text = getString(R.string.dedup_history_count, currentHistorySize)
        sbTimeWindow.progress = currentTimeWindowHours
        tvTimeValue.text = getString(R.string.dedup_time_hours, currentTimeWindowHours)

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
                tvTimeValue.text = getString(R.string.dedup_time_hours, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dedup_settings_title))
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                PreferenceManager.setDedupEnabled(this, cbEnabled.isChecked)
                PreferenceManager.setDedupThreshold(this, sbThreshold.progress / 100f)
                PreferenceManager.setDedupHistorySize(this, sbHistorySize.progress)
                PreferenceManager.setDedupTimeWindow(this, sbTimeWindow.progress * 60)
                //  ,    
                resetDeduplicator()
                Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                showSettingsDialog()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> showSettingsDialog() }
            .setOnCancelListener { showSettingsDialog() }
            .show()
    }

    // =====================   =====================
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
        // NOTE: deduplicator намеренно НЕ сбрасывается здесь — история должна
        // сохраняться между повторными нажатиями «Собрать новости», чтобы уже
        // обработанные посты не попадали в плейлист снова.
        // Явный сброс происходит только при изменении настроек дедупликатора
        // (showDedupSettingsDialog) и при очистке кэша (showSettingsDialog).
        deduplicator?.resetSkippedCount()   // ← добавить
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
        parts.add(getString(R.string.collected_count, lastTotalCollected))

        if (lastAfterDedup > 0) {
            val removed = lastTotalCollected - lastAfterDedup
            if (removed > 0) parts.add(getString(R.string.stat_dupes, removed))
        }

        if (lastAfterFilter > 0 && lastAfterDedup > 0) {
            val removed = lastAfterDedup - lastAfterFilter
            if (removed > 0) parts.add(getString(R.string.stat_spam, removed))
        }

        val baseForTrash = when {
            lastAfterFilter > 0 -> lastAfterFilter
            lastAfterDedup > 0 -> lastAfterDedup
            else -> lastTotalCollected
        }

        if (lastToSynthesize > 0) {
            val trashRemoved = baseForTrash - lastToSynthesize
            if (trashRemoved > 0) parts.add(getString(R.string.stat_trash, trashRemoved))

            if (lastAfterAi > 0) {
                val aiRemoved = lastToSynthesize - lastAfterAi
                if (aiRemoved > 0) parts.add(getString(R.string.stat_ai, aiRemoved))
                parts.add(getString(R.string.stat_to_synth, lastAfterAi))
            } else {
                parts.add(getString(R.string.stat_to_synth, lastToSynthesize))
            }
        }

        if (lastSkippedDuplicates > 0) {
            parts.add(getString(R.string.stat_skipped, lastSkippedDuplicates))
        }

        binding.tvPipelineStatus.text = parts.joinToString("  ")

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
        val previewText = newsList.take(3).joinToString("\n ") {
            it.replace(Regex("^\\d{2}:\\d{2}\\s*\\s*"), "").take(60) + "..."
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

    // =====================  =====================
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
                Toast.makeText(this, getString(R.string.select_channels_first), Toast.LENGTH_SHORT).show()
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
        channelAdapter.deselectAll()

        activePresetId = null
        PresetManager.setActivePresetId(this, null)

        updateNewsCollectionButton()
        saveCurrentSelection()
        refreshPresetChips()

        Toast.makeText(this, getString(R.string.selection_cleared), Toast.LENGTH_SHORT).show()
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
        channelAdapter.refreshVisibleItems()

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
        val spinnerTime = dialogView.findViewById<Spinner>(R.id.spinner_preset_time)
        val tvInfo = dialogView.findViewById<TextView>(R.id.tv_selected_info)

        val timeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, timePeriods)
        timeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTime.adapter = timeAdapter
        spinnerTime.setSelection(currentTimePeriodIndex)

        val channelNames = selectedChannels.take(5).joinToString(", ") { it.title }
        val suffix = if (selectedChannels.size > 5) getString(R.string.and_more_n, selectedChannels.size - 5) else ""
        tvInfo.text = getString(
            R.string.preset_info,
            selectedChannels.size,
            channelNames,
            suffix,
            timePeriods[currentTimePeriodIndex]
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.save_preset)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = etName.text?.toString()?.trim()
                if (name.isNullOrEmpty()) {
                    Toast.makeText(this, getString(R.string.enter_name), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val timePeriod = spinnerTime.selectedItemPosition
                val channelIds = selectedChannels.map { it.id }.toSet()

                val preset = PresetManager.createPreset(this, name, channelIds, timePeriod)
                PresetManager.setActivePresetId(this, preset.id)
                activePresetId = preset.id

                refreshPresetChips()
                Toast.makeText(this, getString(R.string.preset_n_saved, name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
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
                applyPreset(preset)
                showSettingsDialog()
            },
            onPresetDelete = { preset ->
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.preset_delete_confirm, preset.name))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        PresetManager.deletePreset(this, preset.id)
                        dialog.dismiss()
                        refreshPresetChips()
                        Toast.makeText(this, getString(R.string.preset_deleted), Toast.LENGTH_SHORT).show()
                        showPresetsManagerDialog()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
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
        val spinnerTime = dialogView.findViewById<Spinner>(R.id.spinner_preset_time)
        val tvInfo = dialogView.findViewById<TextView>(R.id.tv_selected_info)

        val timeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, timePeriods)
        timeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTime.adapter = timeAdapter
        spinnerTime.setSelection(preset.timePeriodIndex)

        etName.setText(preset.name)

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
            .setTitle(R.string.edit)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = etName.text?.toString()?.trim()
                if (name.isNullOrEmpty()) {
                    Toast.makeText(this, getString(R.string.enter_name), Toast.LENGTH_SHORT).show()
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
                PresetManager.savePreset(this, updated)
                refreshPresetChips()
                Toast.makeText(this, getString(R.string.preset_n_updated, name), Toast.LENGTH_SHORT).show()
                showPresetsManagerDialog()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> showPresetsManagerDialog() }
            .setOnCancelListener { showPresetsManagerDialog() }
            .show()
    }

    /**    ViewGroup   view */
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
                    .setTitle(R.string.security_error)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.exit) { _, _ -> finish() }
                    .show()
            }
        }

        if (telegramClient.checkAuthState()) {
            readyCallback()
        } else {
            updateStatus(getString(R.string.status_tg_init))
        }
    }

    private fun updateUIForReadyClient() {
        binding.btnCollectNews.isEnabled = channelAdapter.getSelectedChannels().isNotEmpty()
        if (binding.tvStatus.text.isEmpty()) {
            updateStatus(getString(R.string.status_client_ready))
        }
    }

    private fun setupUI() {
        binding.btnTimePeriod.setOnClickListener { showTimePeriodDialog() }
        updateTimePeriodButton()

        binding.btnCollectNews.setOnClickListener { collectNews() }

        binding.btnPlay.setOnClickListener {
            if (currentPlaylist.isEmpty()) {
                Toast.makeText(this, getString(R.string.collect_news_first), Toast.LENGTH_SHORT).show()
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
        updateStatus(getString(R.string.status_loading_channels))

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
                    updateStatus(getString(R.string.status_no_channels))
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
            Toast.makeText(this, getString(R.string.client_not_ready), Toast.LENGTH_LONG).show()
            return
        }

        val selectedChannels = channelAdapter.getSelectedChannels()
        if (selectedChannels.isEmpty()) {
            Toast.makeText(this, getString(R.string.select_one_channel), Toast.LENGTH_SHORT).show()
            return
        }

        if (newsCollectionJob?.isActive == true) {
            newsCollectionJob?.cancel()
            updateStatus(getString(R.string.status_collection_stopped))
            binding.progressBar.visibility = View.GONE
            binding.btnCollectNews.text = getString(R.string.collect_news)
            binding.btnCollectNews.isEnabled = true
            stopTimer()
            return
        }

        val timeHours = timeValues[currentTimePeriodIndex]
        startTime = System.currentTimeMillis()
        resetCollectionState()
        showProgressPanels()
        selectedChannels.forEach { it.newMessagesCount = -1 }
        updateChannelProgress(selectedChannels)

        resetProgressCounters()

        //     
        getDeduplicator()

        binding.progressBar.visibility = View.VISIBLE
        binding.btnCollectNews.text = getString(R.string.btn_stop_collection)
        binding.btnCollectNews.isEnabled = true
        startTimer()

        newsCollectionJob = lifecycleScope.launch {
            try {
                updateStatus(getString(R.string.collecting_from_n_channels, selectedChannels.size))
                updateDetailedProgress(getString(R.string.status_collection_starting), 0, 100)

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
                    updateStatus(getString(R.string.status_collection_cancelled))
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error collecting news", e)
                stopTimer()
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCollectNews.text = getString(R.string.collect_news)
                    binding.btnCollectNews.isEnabled = true
                    updateStatus(getString(R.string.error_prefix, e.message))
                    Toast.makeText(this@MainActivity, getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**       Dispatchers.IO */
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

            override fun onNewsTruncated(kept: Int, dropped: Int) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.news_truncated_warning, kept, kept + dropped),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onAiProcessingComplete(beforeCount: Int, afterCount: Int) {
                runOnUiThread {
                    lastToSynthesize = beforeCount
                    lastAfterAi = afterCount
                    updatePipelineStatus()
                }
            }

            override fun onOverallProgress(status: String, percentage: Int) {
                runOnUiThread {
                    updateDetailedProgress(status, percentage, 100)
                    updateElapsedTime()
                    updateETA()
                }
            }

            override fun onSynthesisStarted(messageCount: Int) {
                runOnUiThread {
                    updateDetailedProgress(getString(R.string.status_synthesis_starting), 50, 100)
                    totalProgressSteps = messageCount
                    currentProgressStep = 0
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
                    updateDetailedProgress(getString(R.string.synthesis_completed_status), 100, 100)
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
        updateDetailedProgress(getString(R.string.status_collection_done), 100, 100)

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
                putExtra(AudioPlayerService.EXTRA_TITLE, getString(R.string.news_default_title))
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
            updateStatus(getString(R.string.no_new_news))
            Toast.makeText(this, getString(R.string.no_new_news), Toast.LENGTH_LONG).show()
        }
    }

    private fun updateDetailedProgress(status: String, progress: Int, total: Int) {
        binding.tvDetailedStatus.text = status
        val percentage = if (total > 0) (progress * 100 / total).coerceIn(0, 100) else 0
        binding.progressBarDetailed.progress = percentage
        binding.tvProgressPercentage.text = getString(R.string.percentage, percentage)
    }

    private fun updateElapsedTime() {
        if (startTime == 0L) return
        val elapsedMs = System.currentTimeMillis() - startTime
        val seconds = (elapsedMs / 1000) % 60
        val minutes = (elapsedMs / (1000 * 60)) % 60
        val hours = elapsedMs / (1000 * 60 * 60)
        
        val timeStr = if (hours > 0) {
            String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
        
        //          ,   .
        //    tvProgressPercentage   .
        val currentText = binding.tvProgressPercentage.text.toString()
        if (!currentText.contains("")) {
            binding.tvProgressPercentage.text = "$currentText   $timeStr"
        } else {
            val base = currentText.substringBefore("  ")
            binding.tvProgressPercentage.text = "$base   $timeStr"
        }
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
            .setTitle(R.string.select_time_period)
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

        dialogView.findViewById<View>(R.id.btn_news_order)?.setOnClickListener {
            dialog.dismiss()
            showNewsOrderDialog()
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
                .setTitle(R.string.clear_cache_title)
                .setMessage(getString(R.string.cache_info, count, sizeMb.toInt()))
                .setPositiveButton(R.string.clear) { _, _ ->
                    NewsCache.clearAll(this)
                    resetDeduplicator()
                    //      Telegram 
                    telegramClient.clearTtsRelatedCache { success ->
                        runOnUiThread {
                            val msg = if (success) getString(R.string.cache_full_cleared) else getString(R.string.cache_partial_cleared)
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
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

        dialogView.findViewById<View>(R.id.btn_check_updates)?.setOnClickListener {
            dialog.dismiss()
            UpdateChecker.check(this, force = true)
        }

        dialogView.findViewById<View>(R.id.btn_backup_settings)?.setOnClickListener {
            dialog.dismiss()
            showBackupMenuDialog()
        }

        dialog.show()
    }

    private fun showBackupMenuDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_backup_menu, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btn_create_backup)?.setOnClickListener {
            dialog.dismiss()
            lifecycleScope.launch {
                val createdFileName = SettingsBackup.saveBackupToFile(this@MainActivity)
                Toast.makeText(
                    this@MainActivity,
                    if (createdFileName != null) getString(R.string.backup_saved, createdFileName)
                    else getString(R.string.backup_save_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        dialogView.findViewById<View>(R.id.btn_restore_by_date)?.setOnClickListener {
            dialog.dismiss()
            showRestoreByDateDialog()
        }

        dialogView.findViewById<View>(R.id.btn_import_manual)?.setOnClickListener {
            dialog.dismiss()
            importLauncher.launch(arrayOf("application/json"))
        }

        dialogView.findViewById<View>(R.id.btn_back_to_settings)?.setOnClickListener {
            dialog.dismiss()
            showSettingsDialog()
        }

        dialog.show()
    }

    private fun showRestoreByDateDialog() {
        lifecycleScope.launch {
            val backups = SettingsBackup.listBackups(this@MainActivity)
            if (backups.isEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.no_backups_found), Toast.LENGTH_SHORT).show()
                showBackupMenuDialog()
                return@launch
            }

            val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
            val labels = backups.map { sdf.format(java.util.Date(it.dateMillis)) }.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.select_restore_date)
                .setItems(labels) { _, index ->
                    val chosen = backups[index]
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.restore_confirm_title)
                        .setMessage(getString(R.string.restore_confirm_msg, labels[index]))
                        .setPositiveButton(R.string.restore) { _, _ ->
                            lifecycleScope.launch {
                                val ok = SettingsBackup.restoreFromUri(this@MainActivity, chosen.uri)
                                if (ok) {
                                    Toast.makeText(this@MainActivity, getString(R.string.settings_restored), Toast.LENGTH_SHORT).show()
                                    telegramClient.applyProxySettings() //   
                                    recreate()
                                } else {
                                    Toast.makeText(this@MainActivity, getString(R.string.restore_error), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .setNegativeButton(R.string.cancel) { _, _ -> showRestoreByDateDialog() }
                        .show()
                }
                .setNegativeButton(R.string.back) { _, _ -> showBackupMenuDialog() }
                .show()
        }
    }

    private fun showProxySettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_proxy_settings, null)
        val swEnabled = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_proxy_enabled)
        val recycler = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_proxies)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tv_proxies_empty)
        val btnAdd = dialogView.findViewById<Button>(R.id.btn_add_proxy)
        val layoutAuto = dialogView.findViewById<LinearLayout>(R.id.layout_auto_switch_settings)
        val swAuto = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_auto_proxy)
        val spinnerInterval = dialogView.findViewById<Spinner>(R.id.spinner_proxy_interval)

        val proxies = PreferenceManager.getProxyList(this).toMutableList()

        val updateEmptyState = {
            if (proxies.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                recycler.visibility = View.GONE
                layoutAuto.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                layoutAuto.visibility = if (proxies.size >= 2) View.VISIBLE else View.GONE
            }
        }

        var adapter: ProxyAdapter? = null

        val testAllProxies = {
            proxies.forEach { proxy ->
                telegramClient.testProxy(proxy.host, proxy.port, proxy.secret) { ping, error ->
                    runOnUiThread {
                        if (ping != null) {
                            val pingMs = (ping * 1000).toInt()
                            val status = getString(R.string.proxy_connected_ping, pingMs)
                            val color = 0xFF4CAF50.toInt()
                            adapter?.updatePing(proxy.id, status, color)
                        } else {
                            val status = if (error != null) getString(R.string.proxy_unavailable_reason, error) else getString(R.string.proxy_unavailable)
                            val color = 0xFFFF5252.toInt()
                            adapter?.updatePing(proxy.id, status, color)
                        }
                    }
                }
            }
        }

        swEnabled.isChecked = PreferenceManager.isProxyEnabled(this)
        swAuto.isChecked = PreferenceManager.isProxyAutoSwitchEnabled(this)

        val intervals = listOf(5, 10, 15, 30, 60)
        val intervalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervals.map { "$it ${getString(R.string.min_short)}" })
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerInterval.adapter = intervalAdapter
        spinnerInterval.setSelection(intervals.indexOf(PreferenceManager.getProxySwitchInterval(this)).coerceAtLeast(0))

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.mtproto_title)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                PreferenceManager.setProxyEnabled(this, swEnabled.isChecked)
                PreferenceManager.setProxyAutoSwitchEnabled(this, swAuto.isChecked)
                PreferenceManager.setProxySwitchInterval(this, intervals[spinnerInterval.selectedItemPosition])

                telegramClient.applyProxySettings()
                Toast.makeText(this, getString(R.string.proxy_updated), Toast.LENGTH_SHORT).show()
                showSettingsDialog()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> showSettingsDialog() }
            .setOnCancelListener { showSettingsDialog() }
            .create()

        adapter = ProxyAdapter(
            proxies = proxies,
            onProxySelected = { selected ->
                proxies.forEach { it.isEnabled = it.id == selected.id }
                PreferenceManager.saveProxyList(this, proxies)
                adapter?.updateData(proxies)
                telegramClient.applyProxySettings()
            },
            onProxyEdit = { proxy ->
                dialog.dismiss()
                showAddEditProxyDialog(proxy) { updated ->
                    val idx = proxies.indexOfFirst { it.id == updated.id }
                    if (idx >= 0) {
                        proxies[idx] = updated
                        PreferenceManager.saveProxyList(this, proxies)
                        showProxySettingsDialog()
                    }
                }
            },
            onProxyTest = { _, _ -> }
        )

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        updateEmptyState()
        testAllProxies()

        btnAdd.setOnClickListener {
            dialog.dismiss()
            showAddEditProxyDialog(null) { newProxy ->
                if (proxies.isEmpty()) newProxy.isEnabled = true
                proxies.add(newProxy)
                PreferenceManager.saveProxyList(this, proxies)
                showProxySettingsDialog()
            }
        }

        dialog.show()
    }

    private fun showAddEditProxyDialog(proxy: ProxyEntry?, onSaved: (ProxyEntry) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_proxy_add, null)
        val etHost = dialogView.findViewById<EditText>(R.id.et_proxy_host)
        val etPort = dialogView.findViewById<EditText>(R.id.et_proxy_port)
        val etSecret = dialogView.findViewById<EditText>(R.id.et_proxy_secret)
        val btnPaste = dialogView.findViewById<Button>(R.id.btn_paste_inline)
        val btnDelete = dialogView.findViewById<Button>(R.id.btn_delete_proxy_inline)

        if (proxy != null) {
            etHost.setText(proxy.host)
            etPort.setText(proxy.port.toString())
            etSecret.setText(proxy.secret)
            btnDelete.visibility = View.VISIBLE
        }

        val alertDialog = AlertDialog.Builder(this)
            .setTitle(R.string.proxy_server)
            .setView(dialogView)
            .setPositiveButton(R.string.done) { _, _ ->
                val host = etHost.text.toString().trim()
                val port = etPort.text.toString().toIntOrNull() ?: 0
                val secret = etSecret.text.toString().trim()

                if (host.isNotEmpty() && port > 0 && secret.isNotEmpty()) {
                    val updated = proxy?.copy(host = host, port = port, secret = secret)
                        ?: ProxyEntry(host = host, port = port, secret = secret)
                    onSaved(updated)
                } else {
                    Toast.makeText(this, getString(R.string.save_proxy_error), Toast.LENGTH_SHORT).show()
                    showProxySettingsDialog()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                showProxySettingsDialog()
            }
            .setOnCancelListener {
                showProxySettingsDialog()
            }
            .create()

        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (text.startsWith("tg://proxy?") || text.startsWith("https://t.me/proxy?")) {
                val uri = Uri.parse(text.replace("tg://proxy", "https://t.me/proxy"))
                etHost.setText(uri.getQueryParameter("server") ?: "")
                etPort.setText(uri.getQueryParameter("port") ?: "")
                etSecret.setText(uri.getQueryParameter("secret") ?: "")
            } else {
                Toast.makeText(this, getString(R.string.clipboard_no_proxy), Toast.LENGTH_SHORT).show()
            }
        }

        btnDelete.setOnClickListener {
            if (proxy != null) {
                val proxies = PreferenceManager.getProxyList(this).toMutableList()
                proxies.removeAll { it.id == proxy.id }
                PreferenceManager.saveProxyList(this, proxies)
                alertDialog.dismiss()
                showProxySettingsDialog()
            }
        }

        alertDialog.show()
    }

    private fun showAiSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ai_settings, null)
        val switchEnabled = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_ai_enabled)
        val spinnerProvider = dialogView.findViewById<android.widget.Spinner>(R.id.spinner_ai_provider)
        val spinnerModel = dialogView.findViewById<android.widget.Spinner>(R.id.spinner_ai_model)
        val spinnerStyle = dialogView.findViewById<android.widget.Spinner>(R.id.spinner_ai_style)
        val btnTestManual = dialogView.findViewById<android.widget.Button>(R.id.btn_test_ai_model)
        val tvStatusManual = dialogView.findViewById<android.widget.TextView>(R.id.tv_ai_test_status)

        // Включаем кнопки проверки
        btnTestManual?.visibility = View.VISIBLE
        tvStatusManual?.visibility = View.VISIBLE

        switchEnabled.isChecked = PreferenceManager.isAiSummaryEnabled(this)

        // API-:    
        val tilOpenRouterKey = dialogView.findViewById<TextInputLayout>(R.id.til_openrouter_key)
        val etOpenRouterKey  = dialogView.findViewById<TextInputEditText>(R.id.et_openrouter_key)
        val tilGroqKey       = dialogView.findViewById<TextInputLayout>(R.id.til_groq_key)
        val etGroqKey        = dialogView.findViewById<TextInputEditText>(R.id.et_groq_key)
        etOpenRouterKey?.setText(PreferenceManager.getOpenRouterApiKey(this))
        etGroqKey?.setText(PreferenceManager.getGroqApiKey(this))

        fun updateKeyFieldVisibility(provider: String) {
            tilOpenRouterKey?.visibility = if (provider != "groq") View.VISIBLE else View.GONE
            tilGroqKey?.visibility       = if (provider == "groq")  View.VISIBLE else View.GONE
        }

        val providers = listOf(
            "openrouter" to "OpenRouter",
            "groq"       to "Groq"
        )
        val providerAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, providers.map { it.second })
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerProvider.adapter = providerAdapter

        val currentProvider = PreferenceManager.getAiProvider(this)
        spinnerProvider.setSelection(providers.indexOfFirst { it.first == currentProvider }.coerceAtLeast(0))
        updateKeyFieldVisibility(currentProvider)

        fun getModelsForProvider(provider: String): List<Pair<String, String>> = when (provider) {
            "groq" -> listOf(
                "llama-3.3-70b-versatile"                  to getString(R.string.ai_model_llama_fast),
                "llama-3.1-8b-instant"                     to getString(R.string.ai_model_llama_instant),
                "meta-llama/llama-4-scout-17b-16e-instruct" to getString(R.string.ai_model_llama_new)
            )
            else -> listOf(
                "z-ai/glm-4.5-air:free"                  to getString(R.string.ai_model_glm_free),
                "openai/gpt-oss-120b:free"               to getString(R.string.ai_model_gpt_free),
                "nvidia/nemotron-3-super-120b-a12b:free" to getString(R.string.ai_model_nemotron_free)
            )
        }

        val currentModels = getModelsForProvider(currentProvider).toMutableList()
        val modelStatuses = mutableMapOf<String, String>()
        
        val modelAdapter = object : android.widget.ArrayAdapter<Pair<String, String>>(
            this, R.layout.item_model_status, currentModels
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return createViewFromResource(position, convertView, parent, R.layout.item_model_status)
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return createViewFromResource(position, convertView, parent, R.layout.item_model_status)
            }
            private fun createViewFromResource(position: Int, convertView: View?, parent: ViewGroup, resource: Int): View {
                val view = convertView ?: layoutInflater.inflate(resource, parent, false)
                val item = if (position < count) getItem(position) else null
                val tvName = view.findViewById<TextView>(R.id.tv_model_name)
                val tvStatus = view.findViewById<TextView>(R.id.tv_model_status)
                
                tvName.text = item?.second ?: ""
                val status = modelStatuses[item?.first] ?: ""
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

        fun updateModelsAndCheck(provider: String) {
            currentModels.clear()
            currentModels.addAll(getModelsForProvider(provider))
            modelAdapter.notifyDataSetChanged()
            
            val savedModel = PreferenceManager.getAiModel(this)
            val modelIdx = currentModels.indexOfFirst { it.first == savedModel }.coerceAtLeast(0)
            spinnerModel.setSelection(modelIdx)

            lifecycleScope.launch {
                currentModels.forEach { modelPair ->
                    modelStatuses[modelPair.first] = ""
                }
                modelAdapter.notifyDataSetChanged()
                
                currentModels.map { modelPair ->
                    async {
                        val result = AiProcessor.testModelAvailability(modelPair.first, this@MainActivity)
                        modelStatuses[modelPair.first] = if (result.first) "✅" else "❌"
                        withContext(Dispatchers.Main) { modelAdapter.notifyDataSetChanged() }
                    }
                }.awaitAll()
            }
        }

        spinnerProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newProvider = providers[position].first
                updateKeyFieldVisibility(newProvider)
                if (newProvider != PreferenceManager.getAiProvider(this@MainActivity)) {
                    PreferenceManager.setAiProvider(this@MainActivity, newProvider)
                    PreferenceManager.setAiModel(this@MainActivity, PreferenceManager.getDefaultModelForProvider(newProvider))
                    updateModelsAndCheck(newProvider)
                } else {
                    updateModelsAndCheck(currentProvider)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        btnTestManual?.setOnClickListener {
            val selectedModel = currentModels[spinnerModel.selectedItemPosition].first
            
            // Сохраняем ключи перед проверкой, чтобы AiProcessor их увидел
            PreferenceManager.saveOpenRouterApiKey(this, etOpenRouterKey?.text?.toString()?.trim() ?: "")
            PreferenceManager.saveGroqApiKey(this, etGroqKey?.text?.toString()?.trim() ?: "")

            tvStatusManual?.visibility = View.VISIBLE
            tvStatusManual?.text = getString(R.string.proxy_status_checking)
            tvStatusManual?.setTextColor(Color.GRAY)

            lifecycleScope.launch {
                val result = AiProcessor.testModelAvailability(selectedModel, this@MainActivity)
                runOnUiThread {
                    tvStatusManual?.text = if (result.first) getString(R.string.ai_model_available) else result.second
                    tvStatusManual?.setTextColor(if (result.first) Color.GREEN else Color.RED)
                    
                    // Обновляем и иконку в списке
                    modelStatuses[selectedModel] = if (result.first) "✅" else "❌"
                    modelAdapter.notifyDataSetChanged()
                }
            }
        }

        val styles = listOf(
            "minimal" to getString(R.string.ai_style_minimal),
            "balanced" to getString(R.string.ai_style_balanced),
            "extreme" to getString(R.string.ai_style_extreme)
        )

        val styleAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, styles.map { it.second })
        styleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerStyle.adapter = styleAdapter

        val currentStyle = PreferenceManager.getAiStyle(this)

        val styleIdx = styles.indexOfFirst { it.first == currentStyle }.coerceAtLeast(0)
        spinnerStyle.setSelection(styleIdx)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                //  API-   
                PreferenceManager.saveOpenRouterApiKey(this, etOpenRouterKey?.text?.toString()?.trim() ?: "")
                PreferenceManager.saveGroqApiKey(this, etGroqKey?.text?.toString()?.trim() ?: "")
                PreferenceManager.setAiSummaryEnabled(this, switchEnabled.isChecked)
                val selectedModel = currentModels[spinnerModel.selectedItemPosition].first
                val selectedStyle = styles[spinnerStyle.selectedItemPosition].first
                PreferenceManager.setAiModel(this, selectedModel)
                PreferenceManager.setAiStyle(this, selectedStyle)
                Toast.makeText(this, getString(R.string.ai_settings_saved), Toast.LENGTH_SHORT).show()
                showSettingsDialog()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> showSettingsDialog() }
            .setOnCancelListener { showSettingsDialog() }
            .show()
    }

    private fun showColorThemeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_color_theme, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val rgColorTheme = dialogView.findViewById<android.widget.RadioGroup>(R.id.rg_color_theme)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save_theme)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel_theme)

        val currentTheme = PreferenceManager.getColorTheme(this)
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
            PreferenceManager.saveColorTheme(this, selectedTheme)
            dialog.dismiss()
            recreate()
        }

        dialog.setOnCancelListener { showSettingsDialog() }

        dialog.show()
    }

    private fun showNewsOrderDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_news_order, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val rgOrder = dialogView.findViewById<android.widget.RadioGroup>(R.id.rg_news_order)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save_order)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel_order)

        val currentOrder = PreferenceManager.getNewsOrder(this)
        when (currentOrder) {
            0 -> rgOrder?.check(R.id.rb_order_by_channel_new_first)
            1 -> rgOrder?.check(R.id.rb_order_by_channel_old_first)
            2 -> rgOrder?.check(R.id.rb_order_chronological_new_first)
            3 -> rgOrder?.check(R.id.rb_order_chronological_old_first)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            showSettingsDialog()
        }

        btnSave.setOnClickListener {
            val selectedOrder = when (rgOrder?.checkedRadioButtonId) {
                R.id.rb_order_by_channel_new_first -> 0
                R.id.rb_order_by_channel_old_first -> 1
                R.id.rb_order_chronological_new_first -> 2
                R.id.rb_order_chronological_old_first -> 3
                else -> 0
            }
            PreferenceManager.setNewsOrder(this, selectedOrder)
            dialog.dismiss()
            showSettingsDialog()
            Toast.makeText(this, getString(R.string.playback_order_changed), Toast.LENGTH_SHORT).show()
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

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ -> showSettingsDialog() }
            .setOnCancelListener { showSettingsDialog() }
            .create()

        // Кнопка поддержки разработчика: открывает Boosty во внешнем браузере.
        // Закрываем диалог "О программе" и возвращаемся в настройки для единообразной навигации.
        dialogView.findViewById<View>(R.id.btn_support_developer)?.setOnClickListener {
            dialog.dismiss()
            openDonationPage()
            showSettingsDialog()
        }

        dialog.show()
    }

    /**
     * Открывает страницу поддержки разработчика во внешнем браузере.
     *
     * Внешняя ссылка (ACTION_VIEW), а не платёжный SDK:
     *  - не требует разрешений и зависимостей;
     *  - соответствует правилам сторов (добровольная поддержка, не покупка функций);
     *  - Boosty принимает карты РФ, СБП и рубли без ИП.
     * fallback: если браузера нет — короткое уведомление.
     */
    private fun openDonationPage() {
        val url = "https://boosty.to/telegramnewsreader" // ← TODO: подставить реальный адрес Boosty
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.file_read_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showResetAuthConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_auth_title)
            .setMessage(getString(R.string.reset_auth_confirm))
            .setPositiveButton(R.string.yes) { _, _ -> resetAuthorization() }
            .setNegativeButton(R.string.cancel) { _, _ -> showSettingsDialog() }
            .setOnCancelListener { showSettingsDialog() }
            .show()
    }

    private fun resetAuthorization() {
        binding.btnCollectNews.isEnabled = false
        updateStatus(getString(R.string.status_resetting_auth))

        // [FIX reset]    :   START_STICKY
        //      (    )
        //       .
        try {
            startService(
                Intent(this, AudioPlayerService::class.java)
                    .setAction(AudioPlayerService.ACTION_STOP)
            )
        } catch (_: Exception) {}

        TelegramClientManager.logoutAndClearDb(this) {
            PreferenceManager.clearAll(this)
            TTSManagerSingleton.clearInstance()
            runOnUiThread {
                // [FIX reset]     Activity   
                //     /  .
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, getString(R.string.auth_reset_done), Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, AuthActivity::class.java))
                    finish()
                }
            }
        }
    }

    private fun confirmHideChannel(channel: Channel) {
        AlertDialog.Builder(this)
            .setTitle(R.string.hide_channel_title)
            .setMessage(getString(R.string.hide_channel_confirm, channel.title))
            .setPositiveButton(R.string.hide) { _, _ -> hideChannel(channel) }
            .setNegativeButton(R.string.cancel, null)
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
        Toast.makeText(this, getString(R.string.channel_hidden), Toast.LENGTH_SHORT).show()
    }

    private fun showHiddenManager() {
        val hiddenUsernames = PreferenceManager.getHiddenUsernames(this)
        val hiddenIds = PreferenceManager.getHiddenIds(this)

        val items = mutableListOf<String>()
        val meta = mutableListOf<Pair<String, String>>()

        hiddenUsernames.forEach { u -> items.add("@$u"); meta.add("u" to u) }
        hiddenIds.forEach { idStr ->
            val title = idStr.toLongOrNull()
                ?.let { PreferenceManager.getHiddenTitleForId(this, it) } ?: getString(R.string.channel_default_name)
            items.add(title); meta.add("i" to idStr)
        }

        if (items.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_hidden_channels), Toast.LENGTH_SHORT).show()
            return
        }

        val checked = BooleanArray(items.size)
        AlertDialog.Builder(this)
            .setTitle(R.string.hidden_channels_title)
            .setMultiChoiceItems(items.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.restore_selected) { _, _ ->
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
                Toast.makeText(this, getString(R.string.channels_restored), Toast.LENGTH_SHORT).show()
                showSettingsDialog()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> showSettingsDialog() }
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
                updateStatus(getString(R.string.status_voice_changed))
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
