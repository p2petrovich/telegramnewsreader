package com.example.telegramnewsreader.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log // Рекомендуется для логирования
import com.example.telegramnewsreader.managers.SpamFilter
import com.example.telegramnewsreader.managers.DuplicateFilter
import com.example.telegramnewsreader.models.TelegramChannel // Убедитесь, что это ваш Parcelable TelegramChannel
import com.example.telegramnewsreader.telegram.TelegramClient
import com.example.telegramnewsreader.tts.TTSManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext // Для вызова suspend функций из getMessages

// Убедитесь, что TelegramClient.getMessages была адаптирована для корутин,
// как мы обсуждали ранее (например, созданием suspend обертки)

class NewsCollectorService : Service() {

    private lateinit var ttsManagerInstance: TTSManager // Изменено для отложенной инициализации
    private lateinit var telegramClientInstance: TelegramClient // Если нужен один экземпляр

    // Фильтры можно инициализировать сразу
    private val spamFilter = SpamFilter()
    private val duplicateFilter = DuplicateFilter()

    override fun onCreate() {
        super.onCreate()
        // Инициализируем здесь, так как контекст сервиса уже доступен
        ttsManagerInstance = TTSManager(this)
        telegramClientInstance = TelegramClient(this) // Инициализируем один раз
        Log.d("NewsCollectorService", "Service created and managers initialized.")
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("NewsCollectorService", "onStartCommand received.")
        val channels: ArrayList<TelegramChannel>? = intent?.getParcelableArrayListExtra("channels")
        val timeHours = intent?.getIntExtra("timeHours", 1) ?: 1
        val fromDate = System.currentTimeMillis() / 1000 - (timeHours * 3600)

        if (channels == null || channels.isEmpty()) {
            Log.w("NewsCollectorService", "No channels provided, stopping service.")
            stopSelf(startId) // Важно передавать startId, если вы останавливаете сервис из-за ошибки в onStartCommand
            return START_NOT_STICKY // Или START_STICKY, в зависимости от желаемого поведения при нехватке данных
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("NewsCollectorService", "Coroutine started for processing ${channels.size} channels.")
                val allMessages = mutableListOf<String>()

                // Адаптируем получение сообщений для работы с suspend функцией
                // Предполагается, что в TelegramClient есть suspend fun getChannelMessagesSuspend
                channels.forEach { channel ->
                    try {
                        // Используем один экземпляр telegramClientInstance
                        Log.d("NewsCollectorService", "Fetching messages for channel: ${channel.name}") // Предполагаем, что у TelegramChannel есть name
                        // Если getChannelMessagesSuspend уже есть в TelegramClient:
                        val messages = telegramClientInstance.getChannelMessagesSuspend(channel.id, fromDate)

                        // Если такой функции нет, и нужно адаптировать getMessages с коллбэком:
                        // val messages: List<String> = suspendCancellableCoroutine { continuation ->
                        //    telegramClientInstance.getMessages(channel.id, fromDate) { resultMessages ->
                        //        if (continuation.isActive) {
                        //            continuation.resume(resultMessages)
                        //        }
                        //    }
                        // }

                        Log.d("NewsCollectorService", "Received ${messages.size} messages for channel ${channel.name}.")
                        val filtered = messages
                            .filter { !spamFilter.isSpam(it) }
                            .filter { !duplicateFilter.isDuplicate(it, allMessages) } // Передаем allMessages для проверки дубликатов на лету
                        allMessages.addAll(filtered)
                        Log.d("NewsCollectorService", "Added ${filtered.size} filtered messages from channel ${channel.name}.")
                    } catch (e: Exception) {
                        Log.e("NewsCollectorService", "Error fetching messages for channel ${channel.id}: ${e.message}", e)
                    }
                }

                if (allMessages.isEmpty()) {
                    Log.w("NewsCollectorService", "No messages collected after filtering.")
                } else {
                    allMessages.sortByDescending { it.length } // Сортируем перед TTS
                    Log.d("NewsCollectorService", "Total messages for TTS: ${allMessages.size}. Starting TTS conversion.")

                    // Вызываем suspend функцию convertToAudio и получаем результат
                    val audioFile = ttsManagerInstance.convertToAudio(allMessages)

                    if (audioFile != null) {
                        Log.d("NewsCollectorService", "TTS conversion successful. Audio file: ${audioFile.absolutePath}")
                        // Здесь вы можете что-то сделать с audioFile, например, отправить уведомление
                        // или передать путь к файлу через Intent/BroadcastReceiver
                    } else {
                        Log.e("NewsCollectorService", "TTS conversion failed or returned no file.")
                    }
                }
            } catch (e: Exception) {
                Log.e("NewsCollectorService", "Error in NewsCollectorService coroutine: ${e.message}", e)
            } finally {
                Log.d("NewsCollectorService", "Coroutine finished. Stopping service.")
                stopSelf(startId) // Останавливаем сервис после завершения работы корутины
                // Передаем startId, если это актуально для вашего сценария остановки
            }
        }
        return START_STICKY // Или другой флаг, в зависимости от ваших нужд
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d("NewsCollectorService", "Service destroyed.")
        // Не забываем освобождать ресурсы TTSManager, если он их держит
        if (::ttsManagerInstance.isInitialized) { // Проверяем, был ли инициализирован
            ttsManagerInstance.shutdown()
        }
        // Если TelegramClient требует очистки, сделайте это здесь
        // if (::telegramClientInstance.isInitialized && telegramClientInstance is SomeCloseableInterface) {
        //     telegramClientInstance.close()
        // }
        super.onDestroy()
    }
}

