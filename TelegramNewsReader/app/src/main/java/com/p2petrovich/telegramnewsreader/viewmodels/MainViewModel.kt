package com.p2petrovich.telegramnewsreader.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.io.File

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
            _isClientReady.value = ready
        }
    }

    fun setAuthorized(auth: Boolean) {
        if (_isAuthorized.value != auth) {
            Log.d(TAG, "isAuthorized changed: $auth")
            _isAuthorized.value = auth
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
        total?.let { _lastTotalCollected.value = it }
        afterDedup?.let { _lastAfterDedup.value = it }
        afterFilter?.let { _lastAfterFilter.value = it }
        toSynth?.let { _lastToSynthesize.value = it }
        synth?.let { _lastSynthesized.value = it }
        skipped?.let { _lastSkippedDuplicates.value = it }
        afterAi?.let { _lastAfterAi.value = it }
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

    fun setPlaylistData(files: List<File>, newsCount: Int, indices: Set<Int>) {
        Log.d(TAG, "Playlist updated: ${files.size} files, real news: $newsCount")
        _currentPlaylist.value = files
        _currentRealNewsCount.value = newsCount
        _currentNewsFileIndices.value = indices
    }

    // --- Состояние таймера и прогресса ---
    private val _startTime = MutableLiveData(0L)
    val startTime: LiveData<Long> = _startTime

    private val _currentProgressStep = MutableLiveData(0)
    val currentProgressStep: LiveData<Int> = _currentProgressStep

    fun setStartTime(time: Long) {
        _startTime.value = time
    }

    fun setCurrentProgressStep(step: Int) {
        _currentProgressStep.value = step
    }
}
