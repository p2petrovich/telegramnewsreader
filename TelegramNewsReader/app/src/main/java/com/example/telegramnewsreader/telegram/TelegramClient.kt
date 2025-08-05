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
import java.util.concurrent.ConcurrentHashMap
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

    // Для загрузки аватаров
    private val fileIdToChatId = ConcurrentHashMap<Int, Long>()
    private val chatIdToChannel = ConcurrentHashMap<Long, Channel>()
    private val chatIdToSmallId = ConcurrentHashMap<Long, Int>()

    // UI-колбэк: фото обновилось
    var onChannelPhotoUpdated: ((channelId: Long, photoPath: String) -> Unit)? = null

    var onClientReady: (() -> Unit)? = null
    var onPasswordRequired: (() -> Unit)? = null

    init {
        Log.d(TAG, "=== INIT TRACKING === Constructor called; clientHolder=${System.identityHashCode(this)}")
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
            Log.d(TAG, "=== INIT TRACKING === Client object is null: ${client == null}, clientHash=${System.identityHashCode(client)}")

            if (client == null) {
                Log.e(TAG, "=== INIT TRACKING === CRITICAL: Client.create() returned null!")
                return
            }

            parametersSetTime = System.currentTimeMillis()
            Log.d(TAG, "=== INIT TRACKING === About to send TdlibParameters...")

            client?.send(
                TdApi.SetTdlibParameters(
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
                )
            ) { result ->
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
            }

            Log.d(TAG, "=== INIT TRACKING === SetTdlibParameters sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TelegramClient: ${e.message}", e)
        }
    }

    private fun handleUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuthUpdate(update.authorizationState)
            is TdApi.UpdateNewChat -> Log.d(TAG, "New chat received: ${update.chat.title}")
            is TdApi.UpdateFile -> {
                val f = update.file
                Log.v(TAG, "UpdateFile: id=${f.id}, completed=${f.local.isDownloadingCompleted}, active=${f.local.isDownloadingActive}, downloaded=${f.local.downloadedSize}/${f.size}, path=${f.local.path}")
                val mappedChat = fileIdToChatId[f.id]
                Log.v(TAG, "UpdateFile: map lookup chatId=$mappedChat for fileId=${f.id}")
                if (f.local.isDownloadingCompleted) {
                    val chatId = fileIdToChatId.remove(f.id) ?: mappedChat
                    Log.d(TAG, "📥 File downloaded: id=${f.id}, path=${f.local.path}, chatId=$chatId")
                    if (chatId != null) {
                        chatIdToChannel[chatId]?.let { ch ->
                            ch.photoPath = f.local.path
                            onChannelPhotoUpdated?.invoke(chatId, f.local.path)
                        }
                    }
                } else {
                    Log.v(TAG, "… downloading file id=${f.id}, downloaded=${f.local.downloadedSize}/${f.size}")
                }
            }
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
                client?.send(TdApi.SetNetworkType(TdApi.NetworkTypeOther())) { r ->
                    Log.d(TAG, "SetNetworkType result: ${r?.javaClass?.simpleName}")
                }
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
        Log.d(TAG, "=== INIT TRACKING === sendCode called; clientHash=${System.identityHashCode(client)}")
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
        Log.d(TAG, "=== INIT TRACKING === verifyCode called; clientHash=${System.identityHashCode(client)}")
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
        Log.d(TAG, "=== INIT TRACKING === loadChannels called; clientHash=${System.identityHashCode(client)}")
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
                                        val small = chatResult.photo?.small
                                        if (small != null) {
                                            chatIdToSmallId[chatId] = small.id
                                            fileIdToChatId[small.id] = chatId

                                            Log.v(
                                                TAG,
                                                "Photo small for chat $chatId -> id=${small.id}, remoteId=${small.remote?.id}, unique=${small.remote?.uniqueId}, size=${small.size}, local=${small.local?.path}"
                                            )

                                            client?.send(TdApi.GetFile(small.id)) { before ->
                                                Log.v(TAG, "GetFile BEFORE download ${small.id}: $before")

                                                // NEW: если файл уже локально завершен — применяем сразу
                                                val localReady = (before is TdApi.File)
                                                        && before.local.isDownloadingCompleted
                                                        && before.local.path.isNotBlank()
                                                val file = before as? TdApi.File
                                                val isLocalReady = file?.local?.isDownloadingCompleted == true && !file.local.path.isNullOrBlank()
                                                if (isLocalReady) {
                                                    val path = file!!.local.path
                                                    Log.d(TAG, "Local small is already ready for chat=$chatId -> $path")
                                                    chatIdToChannel[chatId]?.let { ch ->
                                                        ch.photoPath = path
                                                    }
                                                    onChannelPhotoUpdated?.invoke(chatId, path)
                                                } else {
                                                    client?.send(TdApi.DownloadFile(small.id, 32, 0, 0, true)) {
                                                        Log.v(TAG, "DownloadFile sent for photoId=${small.id} chatId=$chatId (prio=32)")
                                                        client?.send(TdApi.GetFile(small.id)) { after ->
                                                            Log.v(TAG, "GetFile AFTER download ${small.id}: $after")
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            Log.v(TAG, "🖼 no photo for '${chatResult.title}'")
                                        }

                                        val channel = Channel(
                                            id = chatId,
                                            accessHash = 0,
                                            title = chatResult.title,
                                            username = "",
                                            isSelected = false,
                                            newMessagesCount = 0,
                                            photoPath = null
                                        )
                                        chatIdToChannel[chatId] = channel
                                        channels.add(channel)
                                    }
                                }

                                processed++
                                if (processed == total) {
                                    Log.d(TAG, "RETURN channels=${channels.size}")
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

    fun redownloadPendingPhotos() {
        Log.d(TAG, "redownloadPendingPhotos start; knownChats=${chatIdToSmallId.size}")
        chatIdToSmallId.forEach { (chatId, smallId) ->
            val ch = chatIdToChannel[chatId]
            val need = ch == null || ch.photoPath.isNullOrBlank()
            if (need) {
                fileIdToChatId[smallId] = chatId
                client?.send(TdApi.GetFile(smallId)) { before ->
                    val file = before as? TdApi.File
                    val already = file?.local?.isDownloadingCompleted == true && !file.local.path.isNullOrBlank()
                    if (already) {
                        val path = file!!.local.path
                        Log.d(TAG, "Reapply local small ready for chat=$chatId -> $path")
                        chatIdToChannel[chatId]?.let { c -> c.photoPath = path }
                        onChannelPhotoUpdated?.invoke(chatId, path)
                    } else {
                        client?.send(TdApi.DownloadFile(smallId, 32, 0, 0, true)) {
                            Log.v(TAG, "Re-DownloadFile sent smallId=$smallId chatId=$chatId (prio=32)")
                            client?.send(TdApi.GetFile(smallId)) { after ->
                                Log.v(TAG, "GetFile AFTER re-download $smallId: $after")
                            }
                        }
                    }
                }
            }
        }
    }

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

        client?.send(TdApi.GetChatHistory(channelId, 0, 0, 1000, false)) { response ->
            try {
                when (response) {
                    is TdApi.Messages -> {
                        Log.d(TAG, "📨 Канал $channelId: получено ${response.messages.size} сообщений от TDLib")

                        val messages = response.messages
                            .filter { message ->
                                val bufferTime = 0
                                val isRecent = message.date >= (fromDate - bufferTime)
                                if (!isRecent) {
                                    Log.v(TAG, "⏰ Сообщение слишком старое: ${message.date} < ${fromDate - bufferTime} (с буфером)")
                                }
                                isRecent
                            }
                            .mapNotNull { message ->
                                val time = try {
                                    Instant.ofEpochSecond(message.date.toLong())
                                        .atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                                } catch (e: Exception) {
                                    Log.w(TAG, "Ошибка форматирования времени для сообщения ${message.id}", e)
                                    "??:??"
                                }

                                when (val content = message.content) {
                                    is TdApi.MessageText -> {
                                        val text = content.text.text.trim()
                                        if (text.isNotBlank()) "$time — $text" else null
                                    }
                                    is TdApi.MessagePhoto -> {
                                        val caption = content.caption?.text?.trim()
                                        if (!caption.isNullOrBlank()) "$time — $caption" else null
                                    }
                                    is TdApi.MessageVideo -> {
                                        val caption = content.caption?.text?.trim()
                                        if (!caption.isNullOrBlank()) "$time — $caption" else null
                                    }
                                    is TdApi.MessageDocument -> {
                                        val caption = content.caption?.text?.trim()
                                        if (!caption.isNullOrBlank()) "$time — $caption" else null
                                    }
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

                        if (cont.isActive) cont.resume(messages)
                    }

                    is TdApi.Error -> {
                        Log.e(TAG, "❌ Ошибка получения истории канала $channelId: ${response.message}")
                        if (cont.isActive) cont.resume(emptyList())
                    }

                    else -> {
                        Log.w(TAG, "⚠️ Канал $channelId: неожиданный ответ ${response?.javaClass?.simpleName}")
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Исключение при обработке ответа для канала $channelId", e)
                if (cont.isActive) cont.resume(emptyList())
            }
        }

        cont.invokeOnCancellation {
            Log.d(TAG, "🚫 Запрос для канала $channelId отменен")
        }
    }

    suspend fun getChannelMessagesPaginated(
        channelId: Long,
        fromDate: Long,
        maxMessages: Int = 3000
    ): List<String> = suspendCancellableCoroutine { cont ->

        val collectedMessages = mutableListOf<String>()
        var lastMessageId = 0L
        var loadedTotal = 0
        var isDone = false

        fun loadNextPage() {
            if (!isInitialized || !isAuthorized || client == null) {
                Log.e(TAG, "❌ TelegramClient не готов")
                cont.resume(emptyList())
                return
            }

            val request = TdApi.GetChatHistory(channelId, lastMessageId, 0, 100, false)

            client?.send(request) { response ->
                when (response) {
                    is TdApi.Messages -> {
                        val messages = response.messages
                        if (messages.isEmpty()) {
                            Log.d(TAG, "📭 Больше сообщений нет")
                            cont.resume(collectedMessages)
                            return@send
                        }

                        Log.d(TAG, "📨 Получено ${messages.size} сообщений")

                        for (msg in messages) {
                            if (msg.date < fromDate) {
                                Log.d(TAG, "⏹ Достигнут fromDate: ${msg.date} < $fromDate")
                                isDone = true
                                break
                            }

                            val time = Instant.ofEpochSecond(msg.date.toLong())
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm"))

                            val content = msg.content
                            val text = when (content) {
                                is TdApi.MessageText -> content.text.text.trim()
                                is TdApi.MessagePhoto -> content.caption?.text?.trim()
                                is TdApi.MessageVideo -> content.caption?.text?.trim()
                                is TdApi.MessageDocument -> content.caption?.text?.trim()
                                else -> null
                            }

                            if (!text.isNullOrBlank()) {
                                collectedMessages.add("$time — $text")
                            }
                        }

                        loadedTotal += messages.size
                        lastMessageId = messages.last().id

                        if (isDone || loadedTotal >= maxMessages) {
                            Log.d(TAG, "✅ Завершено: собрано ${collectedMessages.size} сообщений")
                            cont.resume(collectedMessages)
                        } else {
                            loadNextPage()
                        }
                    }

                    is TdApi.Error -> {
                        Log.e(TAG, "❌ Ошибка TDLib: ${response.message}")
                        cont.resume(collectedMessages)
                    }

                    else -> {
                        Log.w(TAG, "⚠️ Неожиданный ответ: ${response?.javaClass?.simpleName}")
                        cont.resume(collectedMessages)
                    }
                }
            }
        }

        loadNextPage()
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


    fun checkAuthState(): Boolean = isReady && isAuthorized

    fun close() {
        client?.send(TdApi.Close(), null)
        client = null
        isInitialized = false
        isAuthorized = false
        isReady = false
    }
}