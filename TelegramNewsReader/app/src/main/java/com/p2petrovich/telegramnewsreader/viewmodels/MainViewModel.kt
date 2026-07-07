package com.p2petrovich.telegramnewsreader.viewmodels

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.models.Channel
import com.p2petrovich.telegramnewsreader.services.NewsService
import com.p2petrovich.telegramnewsreader.services.ProgressCallback
import com.p2petrovich.telegramnewsreader.utils.AudioUtils
import com.p2petrovich.telegramnewsreader.utils.DebugConfig
import com.p2petrovich.telegramnewsreader.utils.Deduplicator
import com.p2petrovich.telegramnewsreader.utils.Logx
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _isClientReady = MutableLiveData<Boolean?>(null)
    val isClientReady: LiveData<Boolean> get() = _isClientReady as LiveData<Boolean>

    private val _isAuthorized = MutableLiveData<Boolean?>(null)
    val isAuthorized: LiveData<Boolean> get() = _isAuthorized as LiveData<Boolean>

    fun setClientReady(ready: Boolean) {
        if (_isClientReady.value != ready) {
            _isClientReady.postValue(ready)
        }
    }

    fun setAuthorized(authorized: Boolean) {
        if (_isAuthorized.value != authorized) {
            _isAuthorized.postValue(authorized)
        }
    }

    // --- News Collection Counters ---
    private val _lastTotalCollected = MutableLiveData<Int?>(null)
    val lastTotalCollected: LiveData<Int> get() = _lastTotalCollected as LiveData<Int>

    private val _lastAfterDedup = MutableLiveData<Int?>(null)
    val lastAfterDedup: LiveData<Int> get() = _lastAfterDedup as LiveData<Int>

    private val _lastAfterFilter = MutableLiveData<Int?>(null)
    val lastAfterFilter: LiveData<Int> get() = _lastAfterFilter as LiveData<Int>

    private val _lastToSynthesize = MutableLiveData<Int?>(null)
    val lastToSynthesize: LiveData<Int> get() = _lastToSynthesize as LiveData<Int>

    private val _lastSynthesized = MutableLiveData<Int?>(null)
    val lastSynthesized: LiveData<Int> get() = _lastSynthesized as LiveData<Int>

    private val _lastSkippedDuplicates = MutableLiveData<Int?>(null)
    val lastSkippedDuplicates: LiveData<Int> get() = _lastSkippedDuplicates as LiveData<Int>

    private val _lastAfterAi = MutableLiveData<Int?>(null)
    val lastAfterAi: LiveData<Int> get() = _lastAfterAi as LiveData<Int>

    fun updateCounters(
        total: Int? = null, 
        afterFilter: Int? = null, 
        afterDedup: Int? = null,
        toSynth: Int? = null,
        doneSynth: Int? = null,
        skipped: Int? = null,
        afterAi: Int? = null
    ) {
        total?.let { _lastTotalCollected.postValue(it) }
        afterFilter?.let { _lastAfterFilter.postValue(it) }
        afterDedup?.let { _lastAfterDedup.postValue(it) }
        toSynth?.let { _lastToSynthesize.postValue(it) }
        doneSynth?.let { _lastSynthesized.postValue(it) }
        skipped?.let { _lastSkippedDuplicates.postValue(it) }
        afterAi?.let { _lastAfterAi.postValue(it) }
    }

    fun resetProgressCounters() {
        updateCounters(0, 0, 0, 0, 0, 0, 0)
    }

    // --- Playlist State ---
    private val _currentPlaylist = MutableLiveData<List<File>>(emptyList())
    val currentPlaylist: LiveData<List<File>> get() = _currentPlaylist

    private val _currentRealNewsCount = MutableLiveData<Int?>(null)
    val currentRealNewsCount: LiveData<Int> get() = _currentRealNewsCount as LiveData<Int>

    private val _currentNewsFileIndices = MutableLiveData<Set<Int>>(emptySet())
    val currentNewsFileIndices: LiveData<Set<Int>> get() = _currentNewsFileIndices

    private var lastFileToMsgIndex: IntArray = intArrayOf()
    private var lastOriginalMessages: List<String> = emptyList()

    fun setPlaylistData(files: List<File>, count: Int, indices: Set<Int>, mapping: IntArray, originals: List<String>) {
        _currentPlaylist.postValue(files)
        _currentRealNewsCount.postValue(count)
        _currentNewsFileIndices.postValue(indices)
        lastFileToMsgIndex = mapping
        lastOriginalMessages = originals
    }

    fun markAsRead(context: Context, fileIndex: Int) {
        if (fileIndex < 0 || fileIndex >= lastFileToMsgIndex.size) return
        val msgIdx = lastFileToMsgIndex[fileIndex]
        if (msgIdx < 0 || msgIdx >= lastOriginalMessages.size) return

        val text = lastOriginalMessages[msgIdx]
        if (!NewsService.isChannelHeader(text)) {
            if (DebugConfig.LOG_PLAYER_EVENTS) {
                Logx.d(TAG) { "markAsRead: marking news at $fileIndex (msg $msgIdx) as read" }
            }
            getDeduplicator(context).addToHistory(text)
        }
    }

    // --- Timer and ETA Logic (Moved from MainActivity) ---
    private val _startTime = MutableLiveData(0L)
    val startTime: LiveData<Long> get() = _startTime

    private val _formattedEta = MutableLiveData<String?>(null)
    val formattedEta: LiveData<String?> get() = _formattedEta

    private var timerJob: Job? = null

    private val _currentProgressStep = MutableLiveData<Int?>(0)
    val currentProgressStep: LiveData<Int> get() = _currentProgressStep as LiveData<Int>

    private val _totalProgressSteps = MutableLiveData<Int?>(0)
    val totalProgressSteps: LiveData<Int> get() = _totalProgressSteps as LiveData<Int>

    fun setCurrentProgressStep(step: Int) { _currentProgressStep.postValue(step) }
    fun setTotalProgressSteps(steps: Int) { _totalProgressSteps.postValue(steps) }

    private fun startTimer(context: Context) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                updateFormattedEta(context)
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun updateFormattedEta(context: Context) {
        val start = _startTime.value ?: 0L
        if (start == 0L) return

        val elapsedMs = System.currentTimeMillis() - start
        val elapsedSec = elapsedMs / 1000

        val elapsedText = when {
            elapsedSec < 60 -> context.getString(R.string.elapsed_seconds, elapsedSec.toInt())
            else -> {
                val min = (elapsedSec / 60).toInt()
                val sec = (elapsedSec % 60).toInt()
                context.getString(R.string.elapsed_minutes, min, sec)
            }
        }

        val currentStep = _currentProgressStep.value ?: 0
        val totalSteps = _totalProgressSteps.value ?: 0

        if (elapsedSec < 3 || currentStep <= 0 || totalSteps <= 0) {
            _formattedEta.postValue(context.getString(R.string.collection_status_combined, elapsedText, context.getString(R.string.eta_calculating)))
            return
        }

        val remainingSteps = totalSteps - currentStep
        if (remainingSteps <= 0) {
            _formattedEta.postValue(context.getString(R.string.collection_status_combined, elapsedText, context.getString(R.string.eta_finishing)))
            return
        }

        val msPerStep = elapsedMs.toDouble() / currentStep
        val remainingSec = (msPerStep * remainingSteps / 1000).toLong()

        val etaText = when {
            remainingSec <= 0 -> context.getString(R.string.eta_finishing)
            remainingSec < 60 -> context.getString(R.string.eta_seconds, remainingSec.toInt())
            remainingSec < 3600 -> {
                val min = (remainingSec / 60).toInt()
                val sec = (remainingSec % 60).toInt()
                context.getString(R.string.eta_minutes, min, sec)
            }
            else -> {
                val hours = (remainingSec / 3600).toInt()
                val min = ((remainingSec % 3600) / 60).toInt()
                context.getString(R.string.eta_hours, hours, min)
            }
        }

        _formattedEta.postValue(context.getString(R.string.collection_status_combined, elapsedText, etaText))
    }

    // --- News Collection Status ---
    private val _savedDurationInfo = MutableLiveData<String?>(null)
    val savedDurationInfo: LiveData<String?> get() = _savedDurationInfo

    fun setSavedDurationInfo(info: String?) { _savedDurationInfo.postValue(info) }
    fun getSavedDurationInfoValue(): String? = _savedDurationInfo.value

    private val _collectionStatus = MutableLiveData<String>("idle")
    val collectionStatus: LiveData<String> get() = _collectionStatus

    private val _detailedStatus = MutableLiveData<String>("")
    val detailedStatus: LiveData<String> get() = _detailedStatus

    private val _isCollecting = MutableLiveData<Boolean?>(false)
    val isCollecting: LiveData<Boolean> get() = _isCollecting as LiveData<Boolean>

    private val _newsPreview = MutableLiveData<List<String>>(emptyList())
    val newsPreview: LiveData<List<String>> get() = _newsPreview

    private val _channelProgress = MutableLiveData<List<Channel>>(emptyList())
    val channelProgress: LiveData<List<Channel>> get() = _channelProgress

    private val _errorEvent = MutableLiveData<String?>(null)
    val errorEvent: LiveData<String?> get() = _errorEvent

    private val _collectionFinishedEvent = MutableLiveData<Pair<NewsService.AudioPlaylist?, Int>?>(null)
    val collectionFinishedEvent: LiveData<Pair<NewsService.AudioPlaylist?, Int>?> get() = _collectionFinishedEvent

    private val _toastEvent = MutableLiveData<String?>(null)
    val toastEvent: LiveData<String?> get() = _toastEvent

    fun clearEvents() {
        _errorEvent.value = null
        _collectionFinishedEvent.value = null
        _toastEvent.value = null
    }

    private var newsCollectionJob: Job? = null
    fun isCollectionActive(): Boolean = newsCollectionJob?.isActive == true

    fun startCollection(
        context: Context,
        newsService: NewsService,
        selectedChannels: List<Channel>,
        timeHours: Double
    ) {
        if (newsCollectionJob?.isActive == true) {
            stopCollection()
            _collectionStatus.postValue("stopped")
            return
        }

        resetProgressCounters()
        _isCollecting.postValue(true)
        val startTimeMs = System.currentTimeMillis()
        _startTime.postValue(startTimeMs)
        _formattedEta.postValue(null)
        startTimer(context)

        val currentDeduplicator = getDeduplicator(context)
        currentDeduplicator.resetSkippedCount()
        Logx.d(TAG) { "Collection START. Deduplicator status: enabled=${currentDeduplicator.isEnabled}" }

        selectedChannels.forEach { it.newMessagesCount = -1 }
        _channelProgress.postValue(selectedChannels)

        newsCollectionJob = viewModelScope.launch {
            try {
                _collectionStatus.postValue("starting")
                _detailedStatus.postValue("init")

                val audio = newsService.collectAndSynthesizePlaylist(
                    channels = selectedChannels,
                    timeHours = timeHours,
                    progressCallback = createProgressCallback(selectedChannels, context),
                    deduplicator = getDeduplicator(context)
                )

                val durationMin = withContext(Dispatchers.IO) {
                    audio?.let { AudioUtils.calcDurationMinutes(it.files) } ?: 0
                }

                updateCounters(skipped = getDeduplicator(context).getSkippedCount())
                _collectionFinishedEvent.postValue(audio to durationMin)

            } catch (e: CancellationException) {
                Logx.d(TAG) { "News collection cancelled" }
                _collectionStatus.postValue("cancelled")
            } catch (e: Exception) {
                Logx.e(TAG, "Error collecting news", e)
                _errorEvent.postValue(e.message)
            } finally {
                _isCollecting.postValue(false)
                _startTime.postValue(0L)
                stopTimer()
                newsCollectionJob = null
            }
        }
    }

    fun stopCollection() {
        newsCollectionJob?.cancel()
        stopTimer()
    }

    private fun createProgressCallback(selectedChannels: List<Channel>, context: Context): ProgressCallback {
        return object : ProgressCallback {
            override fun onUpdateProgress(status: String, current: Int, total: Int) {
                _detailedStatus.postValue(status)
                setCurrentProgressStep(current)
                setTotalProgressSteps(total)
            }
            override fun onUpdateCounters(total: Int, toSynth: Int, doneSynth: Int) {
                updateCounters(total = total, toSynth = toSynth, doneSynth = doneSynth)
            }
            override fun onUpdateNewsPreview(preview: List<String>) {
                _newsPreview.postValue(preview)
            }
            override fun onUpdateChannelProgress(list: List<Channel>) {
                _channelProgress.postValue(list)
            }
            override fun onChannelProcessed(channel: Channel, count: Int) {
                val current = _channelProgress.value?.toMutableList() ?: return
                current.find { it.id == channel.id }?.newMessagesCount = count
                _channelProgress.postValue(current)
            }
            override fun onDeduplicationComplete(total: Int, filtered: Int) {
                updateCounters(afterDedup = filtered)
            }
            override fun onMessageFiltered(total: Int, filtered: Int) {
                updateCounters(afterFilter = filtered)
            }
            override fun onNewsTruncated(kept: Int, dropped: Int) {
                // optional notification
            }
            override fun onAiProcessingComplete(before: Int, after: Int) {
                updateCounters(afterAi = after)
            }
            override fun onOverallProgress(status: String, percentage: Int) {
                _detailedStatus.postValue(status)
            }
            override fun onSynthesisStarted(messageCount: Int) {
                _detailedStatus.postValue(context.getString(R.string.speech_synthesis_status))
            }
            override fun onSynthesisProgress(current: Int, total: Int) {
                setCurrentProgressStep(current)
                setTotalProgressSteps(total)
            }
            override fun onSynthesisCompleted() {
                _detailedStatus.postValue(context.getString(R.string.synthesis_completed_status))
            }
        }
    }

    // --- Deduplication (Business Object) ---
    private var deduplicator: Deduplicator? = null

    fun getDeduplicator(context: Context): Deduplicator {
        if (deduplicator == null) {
            deduplicator = Deduplicator(
                context = context.applicationContext,
                isEnabled = PreferenceManager.isDedupEnabled(context),
                matchThreshold = PreferenceManager.getDedupThreshold(context),
                historySize = PreferenceManager.getDedupHistorySize(context),
                timeWindowMinutes = PreferenceManager.getDedupTimeWindow(context)
            )
        }
        return deduplicator!!
    }

    fun resetDeduplicator() {
        Logx.d(TAG) { "resetDeduplicator called" }
        deduplicator?.reset()
        deduplicator = null
    }
}
