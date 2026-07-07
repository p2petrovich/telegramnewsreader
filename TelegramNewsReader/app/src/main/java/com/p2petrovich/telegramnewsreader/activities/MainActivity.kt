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
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.p2petrovich.telegramnewsreader.managers.DeduplicationController
import com.p2petrovich.telegramnewsreader.managers.PresetController
import com.p2petrovich.telegramnewsreader.viewmodels.MainViewModel
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.TelegramNewsApplication
import com.p2petrovich.telegramnewsreader.adapters.ChannelAdapter
import com.p2petrovich.telegramnewsreader.adapters.ProxyAdapter
import com.p2petrovich.telegramnewsreader.databinding.ActivityMainBinding
import com.p2petrovich.telegramnewsreader.fragments.AiSettingsDialogFragment
import com.p2petrovich.telegramnewsreader.fragments.ProxySettingsDialogFragment
import com.p2petrovich.telegramnewsreader.fragments.ThemeSelectionDialogFragment
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
import com.p2petrovich.telegramnewsreader.utils.AudioUtils
import com.p2petrovich.telegramnewsreader.utils.NewsCache
import com.p2petrovich.telegramnewsreader.utils.AiProcessor
import com.p2petrovich.telegramnewsreader.utils.UpdateChecker
import com.p2petrovich.telegramnewsreader.utils.DebugConfig
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import com.p2petrovich.telegramnewsreader.utils.PresetManager
import com.p2petrovich.telegramnewsreader.utils.SettingsBackup
import com.p2petrovich.telegramnewsreader.utils.Logx
import kotlinx.coroutines.Dispatchers
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
    private lateinit var presetController: PresetController
    private lateinit var deduplicationController: DeduplicationController

    private val viewModel: MainViewModel by viewModels()

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
    private var isClientReadyCallbackProcessed = false
    // [FIX] savedDurationInfo перенесён в MainViewModel, чтобы переживать recreate().
    private val pendingPhotos = mutableMapOf<Long, String>()

    private var activePresetId: String? = null

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
                if (DebugConfig.LOG_PLAYER_EVENTS) {
                    Logx.v("MainActivity") { "Player progress: cur=$cur, total=$total, isPlaying=$isPlaying" }
                }

                val text = if (total > 0 && cur in 1..total) {
                    if (isPlaying) getString(R.string.status_playing, cur, total)
                    else getString(R.string.status_ready, cur, total)
                } else ""

                // [FIX] Длительность читаем из ViewModel (переживает recreate).
                val savedDuration = viewModel.getSavedDurationInfoValue()
                val finalText = when {
                    savedDuration != null && text.isNotEmpty() -> "$text\n$savedDuration"
                    text.isEmpty() && savedDuration != null -> savedDuration
                    else -> text
                }
                binding.tvStatus.text = finalText

                if (total > 0) {
                    binding.llPlayer.visibility = View.VISIBLE
                    updatePlayerButtons(isPlaying)
                    PreferenceManager.savePlayerIndex(context, cur - 1)
                    PreferenceManager.savePlayerIsPlaying(context, isPlaying)

                    if (isPlaying) {
                        viewModel.markAsRead(context, cur - 1)
                    }
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

    private fun observeViewModel() {
        viewModel.isClientReady.observe(this) { ready ->
            if (ready == true) {
                updateUIForReadyClient()
                //     -  
                if (channelAdapter.getAllChannels().isEmpty()) {
                    loadChannels()
                }
            }
        }
        viewModel.lastTotalCollected.observe(this) { updatePipelineStatus() }
        viewModel.lastAfterDedup.observe(this) { updatePipelineStatus() }
        viewModel.lastAfterFilter.observe(this) { updatePipelineStatus() }
        viewModel.lastAfterAi.observe(this) { updatePipelineStatus() }
        viewModel.lastToSynthesize.observe(this) { updatePipelineStatus() }
        viewModel.lastSynthesized.observe(this) { updatePipelineStatus() }
        viewModel.lastSkippedDuplicates.observe(this) { updatePipelineStatus() }

        viewModel.isCollecting.observe(this) { isCollecting ->
            updateNewsCollectionButton()
            if (isCollecting == true) {
                showProgressPanels()
                binding.progressBar.visibility = View.VISIBLE
            } else {
                binding.progressBar.visibility = View.GONE
            }
        }

        viewModel.collectionStatus.observe(this) { status ->
            val selectedChannels = channelAdapter.getSelectedChannels()
            when (status) {
                "starting" -> {
                    updateStatus(getString(R.string.collecting_from_n_channels, selectedChannels.size))
                    updateDetailedProgress(getString(R.string.status_collection_starting), 0, 100)
                }
                "stopped" -> updateStatus(getString(R.string.status_collection_stopped))
                "cancelled" -> updateStatus(getString(R.string.status_collection_cancelled))
            }
        }

        viewModel.detailedStatus.observe(this) { status ->
            val cur = viewModel.currentProgressStep.value ?: -1
            val tot = viewModel.totalProgressSteps.value ?: 100

            if (status == "init") updateDetailedProgress(getString(R.string.status_collection_starting), 0, 100)
            else if (status == "done") updateDetailedProgress(getString(R.string.status_collection_done), 100, 100)
            else updateDetailedProgress(status, cur, tot)

            if (status.contains(getString(R.string.status_synthesis_starting)) || status.contains("")) {
                binding.cardCollectionProgress.visibility = View.VISIBLE
            }
        }

        viewModel.newsPreview.observe(this) { updateNewsPreview(it) }
        viewModel.channelProgress.observe(this) { updateChannelProgress(it) }

        viewModel.errorEvent.observe(this) { error ->
            error?.let {
                updateStatus(getString(R.string.error_prefix, it))
                Toast.makeText(this, getString(R.string.error_prefix, it), Toast.LENGTH_LONG).show()
                viewModel.clearEvents()
            }
        }

        viewModel.toastEvent.observe(this) { event ->
            event?.let {
                val parts = it.split("|")
                if (parts[0] == "truncated") {
                    val kept = parts[1].toIntOrNull() ?: 0
                    val total = parts[2].toIntOrNull() ?: 0
                    Toast.makeText(this, getString(R.string.news_truncated_warning, kept, total), Toast.LENGTH_LONG).show()
                }
                viewModel.clearEvents()
            }
        }

        viewModel.collectionFinishedEvent.observe(this) { result ->
            result?.let { (audio, durationMin) ->
                handleCollectionResult(audio, durationMin)
                viewModel.clearEvents()
            }
        }

        // [FIX] Реакция на изменение сохранённой длительности (важно после recreate).
        viewModel.savedDurationInfo.observe(this) {
            // Основной рендер длительности идёт в progressReceiver и handleCollectionResult.
            // Здесь наблюдатель оставлен как точка расширения при необходимости.
        }

        viewModel.formattedEta.observe(this) { eta ->
            binding.tvEta.text = eta
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
        observeViewModel()

        lastUsedVoice = PreferenceManager.getTtsVoiceName(this)

        //     GitHub (throttle:   )
        UpdateChecker.check(this)
    }

    // =====================   =====================
    private fun updateStatus(text: String) {
        binding.tvStatus.text = text
    }

    private fun updateNewsCollectionButton() {
        val selectedCount = channelAdapter.getSelectedChannels().size
        val isClientReady = viewModel.isClientReady.value ?: false
        binding.btnCollectNews.isEnabled = selectedCount > 0 && isClientReady
        binding.btnCollectNews.text = getString(R.string.collect_news)
    }

    // [DELETE] startTimer, stopTimer, updateETA moved to ViewModel

    private fun showProgressPanels() {
        binding.cardCollectionProgress.visibility = View.VISIBLE
        binding.llNewsPreview.visibility = View.VISIBLE
        binding.llChannelProgress.visibility = View.VISIBLE
    }

    private fun resetProgressCounters() {
        viewModel.resetProgressCounters()

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
        val lastTotalCollected = viewModel.lastTotalCollected.value ?: 0
        val lastAfterFilter = viewModel.lastAfterFilter.value ?: 0
        val lastAfterDedup = viewModel.lastAfterDedup.value ?: 0
        val lastAfterAi = viewModel.lastAfterAi.value ?: 0
        val lastToSynthesize = viewModel.lastToSynthesize.value ?: 0
        val lastSkippedDuplicates = viewModel.lastSkippedDuplicates.value ?: 0
        val lastSynthesized = viewModel.lastSynthesized.value ?: 0

        val parts = mutableListOf<String>()
        parts.add(getString(R.string.collected_count, lastTotalCollected))

        if (lastAfterFilter > 0) {
            val removed = lastTotalCollected - lastAfterFilter
            if (removed > 0) parts.add(getString(R.string.stat_spam, removed))
        }

        if (lastAfterDedup > 0 && lastAfterFilter > 0) {
            val removed = lastAfterFilter - lastAfterDedup
            if (removed > 0) parts.add(getString(R.string.stat_dupes, removed))
        }

        val baseForTrash = when {
            lastAfterDedup > 0 -> lastAfterDedup
            lastAfterFilter > 0 -> lastAfterFilter
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

    // [DELETE] updateETA moved to ViewModel

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

        presetController = PresetController(
            activity = this,
            binding = binding,
            channelAdapter = channelAdapter,
            timePeriods = timePeriods,
            onPresetApplied = { preset -> applyPreset(preset) },
            onPresetAndCollect = { preset -> applyPresetAndCollect(preset) },
            onSelectionSaved = { saveCurrentSelection() }
        )

        deduplicationController = DeduplicationController(
            activity = this,
            onSettingsSaved = { viewModel.resetDeduplicator() },
            onOpenSettings = { showSettingsDialog() }
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
            presetController.refreshPresetChips()
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

        binding.btnDeselectAll.setOnClickListener {
            deselectAllChannels()
        }

        presetController.setup { currentTimePeriodIndex }
    }

    private fun deselectAllChannels() {
        channelAdapter.deselectAll()

        activePresetId = null
        PresetManager.setActivePresetId(this, null)

        updateNewsCollectionButton()
        saveCurrentSelection()
        presetController.refreshPresetChips()

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
        
        // [FIX] Обновляем счетчики новостей для нового периода пресета
        loadInitialNewsForChannels(allChannels)

        updateNewsCollectionButton()
        PresetManager.saveLastSelection(this, preset.channelIds, preset.timePeriodIndex)
        presetController.refreshPresetChips()
        updateChannelStats()
        Toast.makeText(this, getString(R.string.preset_n_applied, preset.name), Toast.LENGTH_SHORT).show()
    }

    private fun applyPresetAndCollect(preset: ChannelPreset) {
        applyPreset(preset)
        binding.root.postDelayed({ collectNews() }, 300)
    }



    private fun initializeTelegramClient() {
        val readyCallback: () -> Unit = {
            if (!isClientReadyCallbackProcessed) {
                isClientReadyCallbackProcessed = true
                runOnUiThread {
                    viewModel.setClientReady(true)
                    telegramClient.onChannelPhotoUpdated = { channelId, path ->
                        runOnUiThread {
                            val idx = channelAdapter.getAllChannels().indexOfFirst { it.id == channelId }
                            if (idx >= 0) channelAdapter.updateChannelPhoto(channelId, path)
                            else pendingPhotos[channelId] = path
                        }
                    }
                }
            }
        }

        telegramClient = TelegramClientManager.getTelegramClient(this)
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
        val isClientReady = viewModel.isClientReady.value ?: false
        if (!isClientReady) return

        binding.btnCollectNews.isEnabled = channelAdapter.getSelectedChannels().isNotEmpty()
        if (binding.tvStatus.text.isEmpty()) {
            updateStatus(getString(R.string.status_client_ready))
            updateChannelStats()
        }
    }

    private fun setupUI() {
        binding.btnTimePeriod.setOnClickListener { showTimePeriodDialog() }
        updateTimePeriodButton()

        binding.btnCollectNews.setOnClickListener { collectNews() }

        binding.btnPlay.setOnClickListener {
            val currentPlaylist = viewModel.currentPlaylist.value ?: emptyList()
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
            viewModel.setSavedDurationInfo(null)
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
        val currentPlaylist = viewModel.currentPlaylist.value ?: emptyList()
        binding.btnPlay.isEnabled = !isPlaying && currentPlaylist.isNotEmpty()
        binding.btnPause.isEnabled = isPlaying
        binding.btnNext.isEnabled = true
    }

    private fun resetPlayerButtons() {
        binding.btnPlay.isEnabled = true
        binding.btnPause.isEnabled = false
        binding.btnNext.isEnabled = false
    }

    private fun loadChannels() {
        //          
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
                    presetController.refreshPresetChips()
                } else {
                    //       -      
                    if (viewModel.isClientReady.value == true) {
                        binding.root.postDelayed({ loadChannels() }, 3000)
                    }
                    updateStatus(getString(R.string.status_no_channels))
                }
            }
        }
    }

    private fun loadInitialNewsForChannels(channels: List<Channel>) {
        val isClientReady = viewModel.isClientReady.value ?: false
        if (!isClientReady) return

        // [FIX] Моментально сбрасываем старые счетчики, чтобы пользователь
        // не видел данные от предыдущего периода/набора во время загрузки.
        channels.forEach { it.newMessagesCount = -1 }
        channelAdapter.refreshVisibleItems()

        lifecycleScope.launch {
            try {
                // [FIX] Используем текущий выбранный период вместо жестких 30 минут
                val currentHours = if (currentTimePeriodIndex in timeValues.indices)
                    timeValues[currentTimePeriodIndex] else 0.5
                
                val newsCounts = newsService.getAllChannelsNewsCount(channels, currentHours)
                channels.forEach { it.newMessagesCount = newsCounts[it.id] ?: 0 }
                runOnUiThread { channelAdapter.refreshVisibleItems() }
            } catch (e: Exception) {
                Logx.e("MainActivity", "Error loading initial news count", e)
            }
        }
    }

    private fun collectNews() {
        val selectedChannels = channelAdapter.getSelectedChannels()
        if (selectedChannels.isEmpty()) {
            Toast.makeText(this, getString(R.string.select_one_channel), Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.startCollection(
            context = this,
            newsService = newsService,
            selectedChannels = selectedChannels,
            timeHours = timeValues[currentTimePeriodIndex]
        )
    }


    private fun confirmHideChannel(channel: Channel) {
        AlertDialog.Builder(this)
            .setTitle(R.string.hide_channel_title)
            .setMessage(getString(R.string.hide_channel_confirm, channel.title))
            .setPositiveButton(R.string.hide) { _, _ -> hideChannel(channel) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleCollectionResult(audio: NewsService.AudioPlaylist?, durationMin: Int) {
        binding.progressBar.visibility = View.GONE
        binding.btnCollectNews.text = getString(R.string.collect_news)
        binding.btnCollectNews.isEnabled = true
        updateDetailedProgress(getString(R.string.status_collection_done), 100, 100)

        if (audio != null && audio.files.isNotEmpty()) {
            viewModel.setPlaylistData(audio.files, audio.realNewsCount, audio.newsFileIndices, audio.fileToMsgIndex, audio.originalMessages)
            lastUsedVoice = PreferenceManager.getTtsVoiceName(this)
            viewModel.updateCounters(skipped = viewModel.getDeduplicator(this).getSkippedCount())

            val paths = audio.files.map { it.absolutePath }
            PreferenceManager.savePlaylistPaths(this, paths)
            PreferenceManager.saveLastCollectionMetadata(this, audio.realNewsCount, audio.newsFileIndices)

            val baseStatus = getString(R.string.found_news, audio.realNewsCount)
            if (durationMin > 0) {
                val durationInfo = getString(R.string.duration_info, durationMin)
                viewModel.setSavedDurationInfo(durationInfo)
                updateStatus("$baseStatus\n$durationInfo")
            } else {
                viewModel.setSavedDurationInfo(null)
                updateStatus(baseStatus)
            }

            val skipped = viewModel.lastSkippedDuplicates.value ?: 0
            if (skipped > 0) {
                Toast.makeText(
                    this,
                    getString(R.string.skipped_duplicates, skipped),
                    Toast.LENGTH_LONG
                ).show()
            }

            val arrayPaths = ArrayList(paths)
            startService(Intent(this, AudioPlayerService::class.java).apply {
                action = AudioPlayerService.ACTION_SET_PLAYLIST
                putStringArrayListExtra(AudioPlayerService.EXTRA_FILE_PATHS, arrayPaths)
                putExtra(AudioPlayerService.EXTRA_START_INDEX, 0)
                putExtra(AudioPlayerService.EXTRA_TITLE, getString(R.string.news_default_title))
                putExtra(AudioPlayerService.EXTRA_REAL_NEWS_COUNT, audio.realNewsCount)
                putExtra(AudioPlayerService.EXTRA_NEWS_FILE_INDICES, audio.newsFileIndices.toIntArray())
            })

            channelAdapter.refreshVisibleItems()
            binding.llPlayer.visibility = View.VISIBLE
            resetPlayerButtons()
            binding.btnPlay.isEnabled = true
            binding.btnNext.isEnabled = true

            val toastMsg = resources.getQuantityString(R.plurals.found_messages_plural, audio.realNewsCount, audio.realNewsCount)
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
        } else {
            //    ,   -    
            val skipped = viewModel.lastSkippedDuplicates.value ?: 0
            if (skipped > 0) {
                val msg = getString(R.string.no_new_news) + "\n" + getString(R.string.skipped_duplicates, skipped)
                updateStatus(msg)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } else {
                updateStatus(getString(R.string.no_new_news))
                Toast.makeText(this, getString(R.string.no_new_news), Toast.LENGTH_LONG).show()
            }

            // :   ,   ,      
            binding.llPlayer.visibility = View.GONE
        }

        viewModel.stopCollection()
    }

    private fun updateDetailedProgress(status: String, progress: Int, total: Int) {
        binding.tvDetailedStatus.text = status
        val percentage = if (total > 0) (progress * 100 / total).coerceIn(0, 100) else 0
        binding.progressBarDetailed.progress = percentage
        binding.tvProgressPercentage.text = getString(R.string.percentage, percentage)

        //      
        val startTime = viewModel.startTime.value ?: 0L
        if (startTime != 0L) {
            val elapsedMs = System.currentTimeMillis() - startTime
            val seconds = (elapsedMs / 1000) % 60
            val minutes = (elapsedMs / (1000 * 60)) % 60
            val hours = elapsedMs / (1000 * 60 * 60)

            val timeStr = if (hours > 0) {
                String.format(java.util.Locale.US, "%02d:%02d:%02d", hours.toInt(), minutes.toInt(), seconds.toInt())
            } else {
                String.format(java.util.Locale.US, "%02d:%02d", minutes.toInt(), seconds.toInt())
            }

            val currentText = binding.tvProgressPercentage.text.toString()
            if (!currentText.contains("  ")) {
                binding.tvProgressPercentage.text = "$currentText   $timeStr"
            } else {
                val base = currentText.substringBefore("  ")
                binding.tvProgressPercentage.text = "$base   $timeStr"
            }
        }
    }

    private fun updateChannelStats() {
        val total = channelAdapter.getAllChannels().size
        val selected = channelAdapter.getSelectedChannels().size
        val isFilterActive = channelAdapter.isFilterActive()
        val isClientReady = viewModel.isClientReady.value ?: false

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
                // [FIX] Пересчитываем новости сразу после смены периода
                loadInitialNewsForChannels(channelAdapter.getAllChannels())
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
            val fragment = AiSettingsDialogFragment()
            fragment.setOnDismissListener { showSettingsDialog() }
            fragment.show(supportFragmentManager, "ai_settings")
        }

        dialogView.findViewById<View>(R.id.btn_news_order)?.setOnClickListener {
            dialog.dismiss()
            showNewsOrderDialog()
        }

        dialogView.findViewById<View>(R.id.btn_color_theme)?.setOnClickListener {
            dialog.dismiss()
            val fragment = ThemeSelectionDialogFragment()
            fragment.setOnDismissListener { showSettingsDialog() }
            fragment.show(supportFragmentManager, "theme_selection")
        }

        dialogView.findViewById<View>(R.id.btn_manage_presets_settings)?.setOnClickListener {
            dialog.dismiss()
            presetController.showPresetsManagerDialog { showSettingsDialog() }
        }
        dialogView.findViewById<View>(R.id.btn_manage_hidden)?.setOnClickListener {
            dialog.dismiss()
            showHiddenManager()
        }
        dialogView.findViewById<View>(R.id.btn_proxy_settings)?.setOnClickListener {
            dialog.dismiss()
            val fragment = ProxySettingsDialogFragment()
            fragment.setTelegramClient(telegramClient)
            fragment.setOnDismissListener { showSettingsDialog() }
            fragment.show(supportFragmentManager, "proxy_settings")
        }
        dialogView.findViewById<View>(R.id.btn_voice_settings)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, VoiceSelectionActivity::class.java))
        }
        dialogView.findViewById<View>(R.id.btn_dedup_settings)?.setOnClickListener {
            dialog.dismiss()
            deduplicationController.showDedupSettingsDialog()
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
                    viewModel.resetDeduplicator()
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

    // [DELETE] showProxySettingsDialog extracted to ProxySettingsDialogFragment
    // [DELETE] showAddEditProxyDialog extracted to ProxySettingsDialogFragment
    // [DELETE] showAiSettingsDialog extracted to AiSettingsDialogFragment
    // [DELETE] showColorThemeDialog extracted to ThemeSelectionDialogFragment

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

        //   :  Boosty   .
        //   " "       .
        dialogView.findViewById<View>(R.id.btn_support_developer)?.setOnClickListener {
            dialog.dismiss()
            openDonationPage()
            showSettingsDialog()
        }

        dialog.show()
    }

    /**
     *       .
     *
     *   (ACTION_VIEW),    SDK:
     *  -     ;
     *  -    ( ,   );
     *  - Boosty   ,     .
     * fallback:      .
     */
    private fun openDonationPage() {
        val url = "https://boosty.to/telegramnewsreader" //  TODO:    Boosty
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

        // [FIX] Восстановление состояния после сворачивания.
        if (viewModel.isCollecting.value == true) {
            showProgressPanels()
            binding.cardCollectionProgress.visibility = View.VISIBLE
            binding.progressBar.visibility = View.VISIBLE
            binding.btnCollectNews.text = getString(R.string.btn_stop_collection)
        }

        if (::ttsManager.isInitialized) {
            val currentVoice = PreferenceManager.getTtsVoiceName(this)
            if (lastUsedVoice != null && lastUsedVoice != currentVoice) {
                updateStatus(getString(R.string.status_voice_changed))
            }
            ttsManager.refreshVoice()
        }

        val currentPlaylist = viewModel.currentPlaylist.value ?: emptyList()
        if (currentPlaylist.isEmpty()) {
            val savedPaths = PreferenceManager.getPlaylistPaths(this)
            if (savedPaths.isNotEmpty()) {
                val existingFiles = savedPaths.filter { File(it).exists() }
                if (existingFiles.isNotEmpty()) {
                    val files = existingFiles.map { File(it) }
                    binding.llPlayer.visibility = View.VISIBLE
                    binding.btnPlay.isEnabled = true
                    binding.btnNext.isEnabled = true

                    val savedIndex = PreferenceManager.getPlayerIndex(this)
                    val savedIsPlaying = PreferenceManager.getPlayerIsPlaying(this)

                    val realCount = PreferenceManager.getLastRealNewsCount(this)
                    val indices = PreferenceManager.getLastNewsFileIndices(this)
                    viewModel.setPlaylistData(
                        files = files,
                        count = realCount,
                        indices = indices
                    )

                    if (savedIsPlaying) {
                        updatePlayerButtons(true)
                        updateStatus(getString(R.string.resume_playing, savedIndex + 1, files.size))
                    } else {
                        updatePlayerButtons(false)
                        updateStatus(getString(R.string.resume_ready, savedIndex + 1, files.size))
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
        try { unregisterReceiver(progressReceiver) } catch (e: Exception) { Logx.v("MainActivity") { "progressReceiver not registered" } }
        try { unregisterReceiver(ttsErrorReceiver) } catch (e: Exception) { Logx.v("MainActivity") { "ttsErrorReceiver not registered" } }
    }
}
