package com.example.telegramnewsreader.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * 🔍 Класс для детального отслеживания всех операций с TTS настройками
 * Поможет найти причину ускорения речи при переключении каналов
 */
object TTSDebugTracker {

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val events = mutableListOf<TTSEvent>()

    data class TTSEvent(
        val timestamp: Long,
        val type: EventType,
        val details: String,
        val stackTrace: List<String>
    )

    enum class EventType {
        PITCH_CHANGE,
        RATE_CHANGE,
        VOICE_CHANGE,
        TTS_INIT,
        TTS_REFRESH,
        CHANNEL_SWITCH,
        USER_ACTION,
        SYSTEM_ACTION
    }

    /**
     * Записать событие изменения pitch
     */
    fun trackPitchChange(newPitch: Float, source: String) {
        val stackTrace = Thread.currentThread().stackTrace.take(15).map {
            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }

        val event = TTSEvent(
            timestamp = System.currentTimeMillis(),
            type = EventType.PITCH_CHANGE,
            details = "PITCH: $newPitch (from: $source)",
            stackTrace = stackTrace
        )

        events.add(event)
        logEvent(event)

        // Проверяем на подозрительные изменения
        if (events.size > 1) {
            val lastEvent = events[events.size - 2]
            val timeDiff = event.timestamp - lastEvent.timestamp
            if (timeDiff < 1000 && lastEvent.type == EventType.PITCH_CHANGE) {
                Log.w("TTSDebugTracker", "⚠️ ПОДОЗРИТЕЛЬНО: Два изменения pitch за ${timeDiff}мс!")
                Log.w("TTSDebugTracker", "   Предыдущее: ${lastEvent.details}")
                Log.w("TTSDebugTracker", "   Текущее: ${event.details}")
            }
        }
    }

    /**
     * Записать событие изменения rate
     */
    fun trackRateChange(newRate: Float, source: String) {
        val stackTrace = Thread.currentThread().stackTrace.take(15).map {
            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }

        val event = TTSEvent(
            timestamp = System.currentTimeMillis(),
            type = EventType.RATE_CHANGE,
            details = "RATE: $newRate (from: $source)",
            stackTrace = stackTrace
        )

        events.add(event)
        logEvent(event)

        // Проверяем на подозрительные изменения
        if (events.size > 1) {
            val lastEvent = events[events.size - 2]
            val timeDiff = event.timestamp - lastEvent.timestamp
            if (timeDiff < 1000 && lastEvent.type == EventType.RATE_CHANGE) {
                Log.w("TTSDebugTracker", "⚠️ ПОДОЗРИТЕЛЬНО: Два изменения rate за ${timeDiff}мс!")
                Log.w("TTSDebugTracker", "   Предыдущее: ${lastEvent.details}")
                Log.w("TTSDebugTracker", "   Текущее: ${event.details}")
            }
        }
    }

    /**
     * Записать событие изменения голоса
     */
    fun trackVoiceChange(voiceName: String, source: String) {
        val stackTrace = Thread.currentThread().stackTrace.take(15).map {
            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }

        val event = TTSEvent(
            timestamp = System.currentTimeMillis(),
            type = EventType.VOICE_CHANGE,
            details = "VOICE: $voiceName (from: $source)",
            stackTrace = stackTrace
        )

        events.add(event)
        logEvent(event)
    }

    /**
     * Записать событие инициализации TTS
     */
    fun trackTTSInit(details: String) {
        val stackTrace = Thread.currentThread().stackTrace.take(15).map {
            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }

        val event = TTSEvent(
            timestamp = System.currentTimeMillis(),
            type = EventType.TTS_INIT,
            details = "INIT: $details",
            stackTrace = stackTrace
        )

        events.add(event)
        logEvent(event)
    }

    /**
     * Записать событие обновления TTS
     */
    fun trackTTSRefresh(details: String) {
        val stackTrace = Thread.currentThread().stackTrace.take(15).map {
            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }

        val event = TTSEvent(
            timestamp = System.currentTimeMillis(),
            type = EventType.TTS_REFRESH,
            details = "REFRESH: $details",
            stackTrace = stackTrace
        )

        events.add(event)
        logEvent(event)
    }

    /**
     * Записать событие переключения канала
     */
    fun trackChannelSwitch(channelName: String) {
        val stackTrace = Thread.currentThread().stackTrace.take(15).map {
            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }

        val event = TTSEvent(
            timestamp = System.currentTimeMillis(),
            type = EventType.CHANNEL_SWITCH,
            details = "CHANNEL: $channelName",
            stackTrace = stackTrace
        )

        events.add(event)
        logEvent(event)

        // После переключения канала логируем последние события
        Log.d("TTSDebugTracker", "📺 === ПЕРЕКЛЮЧЕНИЕ КАНАЛА: $channelName ===")
        printRecentEvents(5)
    }

    /**
     * Записать пользовательское действие
     */
    fun trackUserAction(action: String) {
        val stackTrace = Thread.currentThread().stackTrace.take(10).map {
            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }

        val event = TTSEvent(
            timestamp = System.currentTimeMillis(),
            type = EventType.USER_ACTION,
            details = "USER: $action",
            stackTrace = stackTrace
        )

        events.add(event)
        logEvent(event)
    }

    /**
     * Записать системное действие
     */
    fun trackSystemAction(action: String) {
        val stackTrace = Thread.currentThread().stackTrace.take(10).map {
            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }

        val event = TTSEvent(
            timestamp = System.currentTimeMillis(),
            type = EventType.SYSTEM_ACTION,
            details = "SYSTEM: $action",
            stackTrace = stackTrace
        )

        events.add(event)
        logEvent(event)
    }

    /**
     * Логировать событие
     */
    private fun logEvent(event: TTSEvent) {
        val time = dateFormat.format(Date(event.timestamp))
        Log.d("TTSDebugTracker", "🔍 [$time] ${event.type}: ${event.details}")

        // Показываем стек только для важных событий
        if (event.type in listOf(EventType.PITCH_CHANGE, EventType.RATE_CHANGE, EventType.TTS_REFRESH)) {
            Log.d("TTSDebugTracker", "📍 Стек:")
            event.stackTrace.take(8).forEach { frame ->
                Log.d("TTSDebugTracker", "   $frame")
            }
        }
    }

    /**
     * Напечатать последние события
     */
    fun printRecentEvents(count: Int = 10) {
        Log.d("TTSDebugTracker", "📊 === ПОСЛЕДНИЕ $count СОБЫТИЙ ===")
        events.takeLast(count).forEach { event ->
            val time = dateFormat.format(Date(event.timestamp))
            Log.d("TTSDebugTracker", "   [$time] ${event.type}: ${event.details}")
        }
        Log.d("TTSDebugTracker", "📊 === КОНЕЦ СПИСКА СОБЫТИЙ ===")
    }

    /**
     * Найти все изменения pitch/rate за последние N миллисекунд
     */
    fun findRecentParameterChanges(timeWindowMs: Long = 5000): List<TTSEvent> {
        val now = System.currentTimeMillis()
        val recentChanges = events.filter { event ->
            event.timestamp > (now - timeWindowMs) &&
                    event.type in listOf(EventType.PITCH_CHANGE, EventType.RATE_CHANGE)
        }

        if (recentChanges.isNotEmpty()) {
            Log.d("TTSDebugTracker", "🔍 Найдено ${recentChanges.size} изменений параметров за последние ${timeWindowMs}мс:")
            recentChanges.forEach { event ->
                val time = dateFormat.format(Date(event.timestamp))
                Log.d("TTSDebugTracker", "   [$time] ${event.details}")
            }
        }

        return recentChanges
    }

    /**
     * Анализ паттернов - поиск подозрительной активности
     */
    fun analyzePatterns() {
        Log.d("TTSDebugTracker", "🔬 === АНАЛИЗ ПАТТЕРНОВ ===")

        val now = System.currentTimeMillis()
        val recentEvents = events.filter { it.timestamp > (now - 10000) } // последние 10 секунд

        // Группируем по типам
        val byType = recentEvents.groupBy { it.type }
        byType.forEach { (type, eventList) ->
            Log.d("TTSDebugTracker", "   $type: ${eventList.size} событий")
        }

        // Ищем быстрые изменения одного параметра
        val pitchChanges = byType[EventType.PITCH_CHANGE] ?: emptyList()
        val rateChanges = byType[EventType.RATE_CHANGE] ?: emptyList()

        if (pitchChanges.size > 2) {
            Log.w("TTSDebugTracker", "⚠️ Много изменений PITCH за короткое время: ${pitchChanges.size}")
            pitchChanges.forEach { event ->
                val time = dateFormat.format(Date(event.timestamp))
                Log.w("TTSDebugTracker", "     [$time] ${event.details}")
            }
        }

        if (rateChanges.size > 2) {
            Log.w("TTSDebugTracker", "⚠️ Много изменений RATE за короткое время: ${rateChanges.size}")
            rateChanges.forEach { event ->
                val time = dateFormat.format(Date(event.timestamp))
                Log.w("TTSDebugTracker", "     [$time] ${event.details}")
            }
        }

        Log.d("TTSDebugTracker", "🔬 === АНАЛИЗ ЗАВЕРШЕН ===")
    }

    /**
     * Очистить историю событий
     */
    fun clearHistory() {
        Log.d("TTSDebugTracker", "🗑️ Очистка истории событий (было ${events.size} событий)")
        events.clear()
    }

    /**
     * Получить статистику
     */
    fun getStats(): Map<EventType, Int> {
        return events.groupBy { it.type }.mapValues { it.value.size }
    }
}