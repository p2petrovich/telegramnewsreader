package com.example.telegramnewsreader.telegram

import android.content.Context
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import com.example.telegramnewsreader.ApiConfig
import com.example.telegramnewsreader.model.Channel
import com.example.telegramnewsreader.utils.PreferenceManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter



class TelegramClient(private val context: Context) {

    private var client: Client? = null
    private var isInitialized = false
    private var isAuthorized = false
    private var isReady = false
    private var authorizationState: TdApi.AuthorizationState? = null

    private val TAG = "TelegramClient"

    private var initStartTime: Long = 0
    private var clientCreateTime: Long = 0
    private var parametersSetTime: Long = 0

    private val authLatch = CountDownLatch(1)

    var onClientReady: (() -> Unit)? = null
    var onPasswordRequired: (() -> Unit)? = null

    init {
        Log.d(TAG, "=== INIT TRACKING === Constructor called")
        initStartTime = System.currentTimeMillis()
        initializeClient()
        Log.d(TAG, "=== INIT TRACKING === Constructor completed in ${System.currentTimeMillis() - initStartTime}ms")
    }

    private fun initializeClient() {
        try {
            Log.d(TAG, "=== INIT TRACKING === initializeClient() started")
            clientCreateTime = System.currentTimeMillis()

            client = Client.create({ update ->
                handleUpdate(update as TdApi.Update)
            }, null, null)

            Log.d(TAG, "=== INIT TRACKING === Client.create() completed in ${System.currentTimeMillis() - clientCreateTime}ms")
            Log.d(TAG, "=== INIT TRACKING === Client object is null: ${client == null}")

            if (client == null) {
                Log.e(TAG, "=== INIT TRACKING === CRITICAL: Client.create() returned null!")
                return
            }

            parametersSetTime = System.currentTimeMillis()
            Log.d(TAG, "=== INIT TRACKING === About to send TdlibParameters...")

            client?.send(TdApi.SetTdlibParameters(
                false,
                context.filesDir.absolutePath + "/" + ApiConfig.DATABASE_DIRECTORY,
                context.filesDir.absolutePath + "/" + ApiConfig.FILES_DIRECTORY,
                byteArrayOf(),
                true,
                true,
                true,
                true,
                ApiConfig.API_ID,
                ApiConfig.API_HASH,
                "ru",
                "Android Device",
                android.os.Build.VERSION.RELEASE,
                "1.0"
            ), { result ->
                Log.d(TAG, "=== INIT TRACKING === SetTdlibParameters callback received in ${System.currentTimeMillis() - parametersSetTime}ms")
                when (result) {
                    is TdApi.Ok -> {
                        Log.d(TAG, "TDLib parameters set successfully")
                        isInitialized = true
                        Log.d(TAG, "=== INIT TRACKING === isInitialized = true")
                    }
                    is TdApi.Error -> {
                        Log.e(TAG, "Failed to set TDLib parameters: ${result.message}")
                    }
                    else -> {
                        Log.e(TAG, "Unknown result when setting TDLib parameters: $result")
                    }
                }
            })

            Log.d(TAG, "=== INIT TRACKING === SetTdlibParameters sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TelegramClient: ${e.message}", e)
        }
    }

    private fun handleUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuthUpdate(update.authorizationState)
            is TdApi.UpdateNewChat -> Log.d(TAG, "New chat received: ${update.chat.title}")
        }
    }

    private fun handleAuthUpdate(state: TdApi.AuthorizationState) {
        authorizationState = state

        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                Log.d(TAG, "Waiting for TDLib parameters")
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                Log.d(TAG, "Waiting for phone number")
                isAuthorized = false
                isReady = false
                PreferenceManager.setAuthorized(context, false)
            }
            is TdApi.AuthorizationStateWaitCode -> {
                Log.d(TAG, "Waiting for authentication code")
                isAuthorized = false
                isReady = false
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                Log.d(TAG, "Waiting for password")
                isAuthorized = false
                isReady = false
                onPasswordRequired?.invoke()
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                Log.d(TAG, "Waiting for other device confirmation")
                isAuthorized = false
                isReady = false
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                Log.d(TAG, "Waiting for registration")
                isAuthorized = false
                isReady = false
            }
            is TdApi.AuthorizationStateReady -> {
                Log.d(TAG, "Authorization complete - client is ready!")
                isAuthorized = true
                isReady = true
                PreferenceManager.setAuthorized(context, true)
                authLatch.countDown()
                onClientReady?.invoke()
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                Log.d(TAG, "Logging out")
                isAuthorized = false
                isReady = false
            }
            is TdApi.AuthorizationStateClosed -> {
                Log.d(TAG, "Authorization closed")
                isAuthorized = false
                isReady = false
            }
            else -> {
                Log.w(TAG, "Unhandled auth state: $state")
            }
        }
    }

    fun sendCode(phone: String, callback: (Boolean) -> Unit) {
        Log.d(TAG, "=== INIT TRACKING === sendCode called")
        Log.d(TAG, getInitializationStatus())

        if (!isInitialized) {
            Log.e(TAG, "Client not initialized in sendCode")
            callback(false)
            return
        }

        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { result ->
            when (result) {
                is TdApi.Ok -> {
                    Log.d(TAG, "Code sent successfully")
                    callback(true)
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Failed to send code: ${result.message}")
                    callback(false)
                }
                else -> {
                    Log.e(TAG, "Unknown result when sending code: $result")
                    callback(false)
                }
            }
        }
    }

    fun verifyCode(code: String, callback: (Boolean) -> Unit) {
        Log.d(TAG, "=== INIT TRACKING === verifyCode called")
        Log.d(TAG, getInitializationStatus())

        if (!isInitialized) {
            Log.e(TAG, "Client not initialized in verifyCode")
            callback(false)
            return
        }

        client?.send(TdApi.CheckAuthenticationCode(code)) { result ->
            when (result) {
                is TdApi.Ok -> {
                    Log.d(TAG, "Code verified successfully")
                    callback(true)
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Failed to verify code: ${result.message}")
                    callback(false)
                }
                else -> {
                    Log.e(TAG, "Unknown result when verifying code: $result")
                    callback(false)
                }
            }
        }
    }

    fun verifyPassword(password: String, callback: (Boolean) -> Unit) {
        if (!isInitialized) {
            Log.e(TAG, "Client not initialized in verifyPassword")
            callback(false)
            return
        }

        client?.send(TdApi.CheckAuthenticationPassword(password)) { result ->
            when (result) {
                is TdApi.Ok -> {
                    Log.d(TAG, "Password verified successfully")
                    callback(true)
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Failed to verify password: ${result.message}")
                    callback(false)
                }
                else -> {
                    Log.e(TAG, "Unknown result when verifying password: $result")
                    callback(false)
                }
            }
        }
    }


    fun loadChannels(callback: (List<Channel>) -> Unit) {
        Log.d(TAG, "=== INIT TRACKING === loadChannels called")
        Log.d(TAG, getInitializationStatus())

        if (!isInitialized) {
            Log.e(TAG, "Client not initialized")
            callback(emptyList())
            return
        }

        Thread {
            if (!waitForReady(15)) {
                Log.e(TAG, "Client not ready after timeout")
                callback(emptyList())
                return@Thread
            }

            if (!isAuthorized) {
                Log.e(TAG, "Client not authorized")
                callback(emptyList())
                return@Thread
            }

            // Получаем до 100 чатов (можно и больше, чтобы найти 5 каналов)
            client?.send(TdApi.GetChats(TdApi.ChatListMain(), 200)) { result ->
                when (result) {
                    is TdApi.Chats -> {
                        val channels = mutableListOf<Channel>()
                        var processed = 0
                        val total = result.chatIds.size

                        for (chatId in result.chatIds) {
                            client?.send(TdApi.GetChat(chatId)) { chatResult ->
                                if (chatResult is TdApi.Chat) {
                                    val type = chatResult.type
                                    if (type is TdApi.ChatTypeSupergroup && type.isChannel) {
                                        channels.add(Channel(chatId, 0, chatResult.title, "", false))
                                    }
                                }

                                processed++
                                if (processed == total) {
                                    callback(channels)
                                }
                            }
                        }
                    }
                    else -> {
                        callback(emptyList())
                    }
                }
            }

        }.start()
    }


    fun getMessages(channelId: Long, fromDate: Long, callback: (List<String>) -> Unit) {
        if (!isInitialized || !isAuthorized) {
            Log.e(TAG, "Client not initialized or not authorized")
            callback(emptyList())
            return
        }

        client?.send(TdApi.GetChatHistory(channelId, 0, 0, 100, false)) { result ->
            when (result) {
                is TdApi.Messages -> {
                    val texts = result.messages
                        .filter { it.date > fromDate && it.content is TdApi.MessageText }
                        .map { (it.content as TdApi.MessageText).text.text }
                    callback(texts)
                }
                else -> {
                    callback(emptyList())
                }
            }
        }
    }

    // Добавьте этот улучшенный метод в TelegramClient.kt

    suspend fun getChannelMessagesSuspend(
        channelId: Long,
        fromDate: Long
    ): List<String> = suspendCancellableCoroutine { cont ->

        if (!isInitialized || client == null) {
            Log.w(TAG, "getChannelMessagesSuspend: клиент не инициализирован")
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        if (!isReady || !isAuthorized) {
            Log.w(TAG, "getChannelMessagesSuspend: клиент не готов (ready=$isReady, auth=$isAuthorized)")
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        Log.d(TAG, "📡 Запрос истории для канала $channelId (fromDate=$fromDate)")

        // ✅ УЛУЧШЕНИЕ: Увеличиваем лимит до 200 сообщений
        client?.send(TdApi.GetChatHistory(channelId, 0, 0, 200, false)) { response ->
            try {
                when (response) {
                    is TdApi.Messages -> {
                        Log.d(TAG, "📨 Канал $channelId: получено ${response.messages.size} сообщений от TDLib")

                        val messages = response.messages
                            .filter { message ->
                                // ✅ ИСПРАВЛЕНИЕ: добавляем буфер 5 минут (300 секунд)
                                val bufferTime = 0
                                val isRecent = message.date >= (fromDate - bufferTime)
                                if (!isRecent) {
                                    Log.v(TAG, "⏰ Сообщение слишком старое: ${message.date} < ${fromDate - bufferTime} (с буфером)")
                                }
                                isRecent
                            }
                            .mapNotNull { message ->
                                // ✅ Форматируем время
                                val time = try {
                                    java.time.Instant.ofEpochSecond(message.date.toLong())
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                                } catch (e: Exception) {
                                    Log.w(TAG, "Ошибка форматирования времени для сообщения ${message.id}", e)
                                    "??:??"
                                }

                                // ✅ Обрабатываем разные типы контента
                                when (val content = message.content) {
                                    is TdApi.MessageText -> {
                                        val text = content.text.text.trim()
                                        if (text.isNotBlank()) "$time — $text" else null
                                    }

                                    is TdApi.MessagePhoto -> {
                                        val caption = content.caption?.text?.trim()
                                        if (!caption.isNullOrBlank()) {
                                            "$time — $caption"
                                        } else {
                                            Log.v(TAG, "Пропускаем фото без подписи")
                                            null
                                        }
                                    }

                                    is TdApi.MessageVideo -> {
                                        val caption = content.caption?.text?.trim()
                                        if (!caption.isNullOrBlank()) {
                                            "$time — $caption"
                                        } else {
                                            Log.v(TAG, "Пропускаем видео без подписи")
                                            null
                                        }
                                    }

                                    is TdApi.MessageDocument -> {
                                        val caption = content.caption?.text?.trim()
                                        if (!caption.isNullOrBlank()) {
                                            "$time — $caption"
                                        } else {
                                            Log.v(TAG, "Пропускаем документ без подписи")
                                            null
                                        }
                                    }

                                    // ✅ Пропускаем медиа без текста
                                    is TdApi.MessageSticker,
                                    is TdApi.MessageVoiceNote,
                                    is TdApi.MessageVideoNote,
                                    is TdApi.MessageAnimation -> {
                                        Log.v(TAG, "Пропускаем медиа-сообщение: ${content.javaClass.simpleName}")
                                        null
                                    }

                                    else -> {
                                        Log.v(TAG, "Неизвестный тип сообщения: ${content.javaClass.simpleName}")
                                        null
                                    }
                                }
                            }

                        Log.d(TAG, "✅ Канал $channelId: после обработки ${messages.size} текстовых сообщений")
                        if (messages.isNotEmpty()) {
                            Log.d(TAG, "🔍 Примеры из канала $channelId: ${messages.take(2)}")
                        }

                        if (cont.isActive) {
                            cont.resume(messages)
                        }
                    }

                    is TdApi.Error -> {
                        Log.e(TAG, "❌ Ошибка получения истории канала $channelId: ${response.message}")
                        if (cont.isActive) {
                            cont.resume(emptyList())
                        }
                    }

                    else -> {
                        Log.w(TAG, "⚠️ Канал $channelId: неожиданный ответ ${response?.javaClass?.simpleName}")
                        if (cont.isActive) {
                            cont.resume(emptyList())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Исключение при обработке ответа для канала $channelId", e)
                if (cont.isActive) {
                    cont.resume(emptyList())
                }
            }
        }

        // ✅ Обработка отмены корутины
        cont.invokeOnCancellation {
            Log.d(TAG, "🚫 Запрос для канала $channelId отменен")
        }
    }


    private fun waitForReady(timeoutSeconds: Long = 10): Boolean {
        return try {
            if (isReady) true else authLatch.await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
    }

    fun getInitializationStatus(): String {
        return """
            === TELEGRAM CLIENT STATUS ===
            Time since constructor: ${System.currentTimeMillis() - initStartTime}ms
            Client object: ${if (client != null) "EXISTS" else "NULL"}
            isInitialized: $isInitialized
            isAuthorized: $isAuthorized
            isReady: $isReady
            authorizationState: ${authorizationState?.javaClass?.simpleName ?: "null"}
        """.trimIndent()
    }

    fun checkAuthState(): Boolean {
        return isReady && isAuthorized
    }

    fun close() {
        client?.send(TdApi.Close(), null)
        client = null
        isInitialized = false
        isAuthorized = false
        isReady = false
    }
}
