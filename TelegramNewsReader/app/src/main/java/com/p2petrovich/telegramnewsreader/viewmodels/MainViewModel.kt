package com.p2petrovich.telegramnewsreader.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.p2petrovich.telegramnewsreader.models.Channel
import com.p2petrovich.telegramnewsreader.services.NewsService
import com.p2petrovich.telegramnewsreader.services.ProgressCallback
import com.p2petrovich.telegramnewsreader.utils.AudioUtils
import com.p2petrovich.telegramnewsreader.utils.Deduplicator
import com.p2petrovich.telegramnewsreader.utils.DebugConfig
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * ViewModel для управления состоянием MainActivity.
 * Хранит счетчики сбора новостей, состояние плейлиста и статусы клиента.
 * Все логи из MainActivity перенесены сюда для сохранения истории операций.
 */
class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    // --- Статусы клиента ---
    private val _isClientReady = MutableLiveData(false)
    val isClientReady: LiveData<Boolean> = _isClientReady

    private val _isAuthorized = MutableLiveData(false)
    val isAuthorized: LiveData<Boolean> = _isAuthorized

    fun setClientReady(ready: Boolean) {
        if (_isClientReady.value != ready) {
            Log.d(TAG, "isClientReady changed: $ready")
            _isClientReady.postValue(ready)
        }
    }

    fun setAuthorized(auth: Boolean) {
        if (_isAuthorized.value != auth) {
            Log.d(TAG, "isAuthorized changed: $auth")
            _isAuthorized.postValue(auth)
        }
    }

    // --- Счетчики сбора новостей (Pipeline Counters) ---
    private val _lastTotalCollected = MutableLiveData(0)
    val lastTotalCollected: LiveData<Int> = _lastTotalCollected

    private val _lastAfterDedup = MutableLiveData(0)
    val lastAfterDedup: LiveData<Int> = _lastAfterDedup

    private val _lastAfterFilter = MutableLiveData(0)
    val lastAfterFilter: LiveData<Int> = _lastAfterFilter

    private val _lastToSynthesize = MutableLiveData(0)
    val lastToSynthesize: LiveData<Int> = _lastToSynthesize

    private val _lastSynthesized = MutableLiveData(0)
    val lastSynthesized: LiveData<Int> = _lastSynthesized

    private val _lastSkippedDuplicates = MutableLiveData(0)
    val lastSkippedDuplicates: LiveData<Int> = _lastSkippedDuplicates

    private val _lastAfterAi = MutableLiveData(0)
    val lastAfterAi: LiveData<Int> = _lastAfterAi

    fun updateCounters(
        total: Int? = null,
        afterDedup: Int? = null,
        afterFilter: Int? = null,
        toSynth: Int? = null,
        synth: Int? = null,
        skipped: Int? = null,
        afterAi: Int? = null
    ) {
        total?.let { _lastTotalCollected.postValue(it) }
        afterDedup?.let { _lastAfterDedup.postValue(it) }
        afterFilter?.let { _lastAfterFilter.postValue(it) }
        toSynth?.let { _lastToSynthesize.postValue(it) }
        synth?.let { _lastSynthesized.postValue(it) }
        skipped?.let { _lastSkippedDuplicates.postValue(it) }
        afterAi?.let { _lastAfterAi.postValue(it) }
    }

    fun resetProgressCounters() {
        Log.d(TAG, "resetProgressCounters called")
        updateCounters(0, 0, 0, 0, 0, 0, 0)
    }

    // --- Плейлист и аудио ---
    private val _currentPlaylist = MutableLiveData<List<File>>(emptyList())
    val currentPlaylist: LiveData<List<File>> = _currentPlaylist

    private val _currentRealNewsCount = MutableLiveData(0)
    val currentRealNewsCount: LiveData<Int> = _currentRealNewsCount

    private val _currentNewsFileIndices = MutableLiveData<Set<Int>>(emptySet())
    val currentNewsFileIndices: LiveData<Set<Int>> = _currentNewsFileIndices

    private var lastFileToMsgIndex: IntArray = intArrayOf()
    private var lastOriginalMessages: List<String> = emptyList()

    fun setPlaylistData(files: List<File>, newsCount: Int, indices: Set<Int>, mapping: IntArray = intArrayOf(), messages: List<String> = emptyList()) {
        Log.d(TAG, "Playlist updated: ${files.size} files, real news: $newsCount")
        _currentPlaylist.postValue(files)
        _currentRealNewsCount.postValue(newsCount)
        _currentNewsFileIndices.postValue(indices)
        lastFileToMsgIndex = mapping
        lastOriginalMessages = messages
    }

    fun markAsRead(context: Context, fileIndex: Int) {
        if (fileIndex < 0 || fileIndex >= lastFileToMsgIndex.size) {
            if (DebugConfig.LOG_PLAYER_EVENTS) {
                Log.v(TAG, "markAsRead: invalid fileIndex $fileIndex (size=${lastFileToMsgIndex.size})")
            }
            return
        }
        val msgIdx = lastFileToMsgIndex[fileIndex]
        if (msgIdx < 0 || msgIdx >= lastOriginalMessages.size) {
            if (DebugConfig.LOG_PLAYER_EVENTS) {
                Log.v(TAG, "markAsRead: invalid msgIdx $msgIdx (size=${lastOriginalMessages.size})")
            }
            return
        }
        
        val text = lastOriginalMessages[msgIdx]
        if (!NewsService.isChannelHeader(text)) {
            if (DebugConfig.LOG_PLAYER_EVENTS) {
                Log.d(TAG, "markAsRead: marking news at $fileIndex (msg $msgIdx) as read")
            }
            getDeduplicator(context).addToHistory(text)
        }
    }

    // --- Состояние таймера и прогресса ---
    private val _startTime = MutableLiveData(0L)
    val startTime: LiveData<Long> = _startTime

    private val _currentProgressStep = MutableLiveData(0)
    val currentProgressStep: LiveData<Int> = _currentProgressStep

    private val _totalProgressSteps = MutableLiveData(0)
    val totalProgressSteps: LiveData<Int> = _totalProgressSteps


    fun setCurrentProgressStep(step: Int) {
        _currentProgressStep.postValue(step)
    }

    fun setTotalProgressSteps(steps: Int) {
        _totalProgressSteps.postValue(steps)
    }

    // --- Состояния процесса сбора ---
    private val _collectionStatus = MutableLiveData<String>("")
    val collectionStatus: LiveData<String> = _collectionStatus

    private val _detailedStatus = MutableLiveData<String>("")
    val detailedStatus: LiveData<String> = _detailedStatus

    private val _isCollecting = MutableLiveData(false)
    val isCollecting: LiveData<Boolean> = _isCollecting

    private val _newsPreview = MutableLiveData<List<String>>(emptyList())
    val newsPreview: LiveData<List<String>> = _newsPreview

    private val _channelProgress = MutableLiveData<List<Channel>>(emptyList())
    val channelProgress: LiveData<List<Channel>> = _channelProgress

    // События (Single-shot)
    private val _errorEvent = MutableLiveData<String?>(null)
    val errorEvent: LiveData<String?> = _errorEvent

    private val _collectionFinishedEvent = MutableLiveData<Pair<NewsService.AudioPlaylist?, Int>?>(null)
    val collectionFinishedEvent: LiveData<Pair<NewsService.AudioPlaylist?, Int>?> = _collectionFinishedEvent

    private val _toastEvent = MutableLiveData<String?>(null)
    val toastEvent: LiveData<String?> = _toastEvent

    fun clearEvents() {
        _errorEvent.postValue(null)
        _collectionFinishedEvent.postValue(null)
        _toastEvent.postValue(null)
    }

    // --- Процесс сбора ---
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
        _startTime.postValue(System.currentTimeMillis())

        val currentDeduplicator = getDeduplicator(context)
        currentDeduplicator.resetSkippedCount() // Сбрасываем счетчик перед началом нового сбора
        Log.d(TAG, "Collection START. Deduplicator status: enabled=${currentDeduplicator.isEnabled}, history_size=${currentDeduplicator.getHistorySize()}")

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
                Log.d(TAG, "News collection cancelled")
                _collectionStatus.postValue("cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting news", e)
                _errorEvent.postValue(e.message)
            } finally {
                _isCollecting.postValue(false)
                _startTime.postValue(0L)
                newsCollectionJob = null
            }
        }
    }

    fun stopCollection() {
        newsCollectionJob?.cancel()
        newsCollectionJob = null
        _isCollecting.postValue(false)
    }

    private fun createProgressCallback(selectedChannels: List<Channel>, context: Context): ProgressCallback {
        return object : ProgressCallback {
            override fun onUpdateProgress(status: String, progress: Int, total: Int) {
                _detailedStatus.postValue(status)
                _currentProgressStep.postValue(progress)
                _totalProgressSteps.postValue(total)
            }

            override fun onUpdateCounters(collected: Int, filtered: Int, synthesized: Int) {
                updateCounters(total = collected, toSynth = filtered, synth = synthesized)
            }

            override fun onUpdateNewsPreview(newsList: List<String>) {
                _newsPreview.postValue(newsList)
            }

            override fun onUpdateChannelProgress(channels: List<Channel>) {
                _channelProgress.postValue(channels)
            }

            override fun onChannelProcessed(channel: Channel, messagesCount: Int) {
                channel.newMessagesCount = messagesCount
                _channelProgress.postValue(selectedChannels.toList())
            }

            override fun onDeduplicationComplete(beforeCount: Int, afterCount: Int) {
                updateCounters(afterDedup = afterCount)
            }

            override fun onMessageFiltered(originalCount: Int, filteredCount: Int) {
                updateCounters(afterFilter = filteredCount)
            }

            override fun onNewsTruncated(kept: Int, dropped: Int) {
                _toastEvent.postValue("truncated|$kept|${kept + dropped}")
            }

            override fun onAiProcessingComplete(beforeCount: Int, afterCount: Int) {
                updateCounters(toSynth = beforeCount, afterAi = afterCount)
            }

            override fun onOverallProgress(status: String, percentage: Int) {
                _detailedStatus.postValue(status)
                _currentProgressStep.postValue(percentage)
                _totalProgressSteps.postValue(100)
            }

            override fun onSynthesisStarted(messageCount: Int) {
                updateCounters(synth = 0)
                _totalProgressSteps.postValue(messageCount)
                _currentProgressStep.postValue(0)
                _detailedStatus.postValue(context.getString(com.p2petrovich.telegramnewsreader.R.string.status_synthesis_starting))
            }

            override fun onSynthesisProgress(current: Int, total: Int) {
                updateCounters(synth = current)
                _currentProgressStep.postValue(current)
                _totalProgressSteps.postValue(total)
                val msg = context.getString(com.p2petrovich.telegramnewsreader.R.string.synthesis_started, current, total)
                _detailedStatus.postValue(msg)
            }

            override fun onSynthesisCompleted() {
                _detailedStatus.postValue("done")
            }
        }
    }

    // --- Дедупликация (бизнес-объект) ---
    private var deduplicator: Deduplicator? = null

    fun getDeduplicator(context: Context): Deduplicator {
        if (deduplicator == null) {
            deduplicator = Deduplicator(
                isEnabled = PreferenceManager.isDedupEnabled(context),
                matchThreshold = PreferenceManager.getDedupThreshold(context),
                historySize = PreferenceManager.getDedupHistorySize(context),
                timeWindowMinutes = PreferenceManager.getDedupTimeWindow(context)
            )
        }
        return deduplicator!!
    }

    fun resetDeduplicator() {
        Log.d(TAG, "resetDeduplicator called")
        deduplicator?.reset()
        deduplicator = null
    }
}
