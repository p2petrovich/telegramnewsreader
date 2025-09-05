// TelegramClient.kt
package com.p2petrovich.telegramnewsreader.telegram

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import com.p2petrovich.telegramnewsreader.ApiConfig
import com.p2petrovich.telegramnewsreader.model.Channel
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

// ✅ Заменены Java time импорты на ThreeTenABP
import org.threeten.bp.Instant
import org.threeten.bp.ZoneId
import org.threeten.bp.format.DateTimeFormatter

class TelegramClient(private val context: Context) {

    private var client: Client? = null
    private var isInitialized = false
    private var isAuthorized = false
    private var isReady = false
    private var authorizationState: TdApi.AuthorizationState? = null

    // Новое: LiveData для отслеживания состояния загрузки каналов
    private val _areChannelsLoaded = MutableLiveData<Boolean>(false)
    val areChannelsLoaded: LiveData<Boolean> = _areChannelsLoaded

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

    // колбэк завершения логаута
    private var onLoggedOut: (() -> Unit)? = null

    init {
        Log.d(TAG, "Constructor; holder=${System.identityHashCode(this)}")
        initStartTime = System.currentTimeMillis()
        initializeClient()
        Log.d(TAG, "Constructor completed in ${System.currentTimeMillis() - initStartTime}ms")
    }

    private fun initializeClient() {
        try {
            clientCreateTime = System.currentTimeMillis()
            client = Client.create({ update ->
                handleUpdate(update as TdApi.Update)
            }, null, null)

            Log.d(TAG, "Client.create() in ${System.currentTimeMillis() - clientCreateTime}ms")
            if (client == null) {
                Log.e(TAG, "CRITICAL: Client.create() returned null")
                return
            }

            parametersSetTime = System.currentTimeMillis()
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
                when (result) {
                    is TdApi.Ok -> {
                        isInitialized = true
                        Log.d(TAG, "TDLib parameters set; isInitialized=true in ${System.currentTimeMillis() - parametersSetTime}ms")
                    }
                    is TdApi.Error -> Log.e(TAG, "SetTdlibParameters error: ${result.message}")
                    else -> Log.e(TAG, "SetTdlibParameters unknown: $result")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TelegramClient: ${e.message}", e)
        }
    }

    private fun handleUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuthUpdate(update.authorizationState)
            is TdApi.UpdateNewChat -> Log.d(TAG, "New chat: ${update.chat.title}")
            is TdApi.UpdateFile -> {
                val f = update.file
                if (f.local.isDownloadingCompleted) {
                    val mappedChat = fileIdToChatId[f.id]
                    val chatId = fileIdToChatId.remove(f.id) ?: mappedChat
                    if (chatId != null) {
                        chatIdToChannel[chatId]?.let { ch ->
                            ch.photoPath = f.local.path
                            onChannelPhotoUpdated?.invoke(chatId, f.local.path)
                            Log.d(TAG, "Avatar downloaded for chat=$chatId path=${f.local.path}")
                        }
                    }
                }
            }
        }
    }

    private fun handleAuthUpdate(state: TdApi.AuthorizationState) {
        authorizationState = state

        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                Log.d(TAG, "WaitTdlibParameters")
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                Log.d(TAG, "WaitPhoneNumber")
                isAuthorized = false
                isReady = false
                PreferenceManager.setAuthorized(context, false)
            }
            is TdApi.AuthorizationStateWaitCode -> {
                Log.d(TAG, "WaitCode")
                isAuthorized = false
                isReady = false
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                Log.d(TAG, "WaitPassword")
                isAuthorized = false
                isReady = false
                onPasswordRequired?.invoke()
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                Log.d(TAG, "WaitOtherDeviceConfirmation")
                isAuthorized = false
                isReady = false
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                Log.d(TAG, "WaitRegistration")
                isAuthorized = false
                isReady = false
            }
            is TdApi.AuthorizationStateReady -> {
                Log.d(TAG, "Authorization ready")
                isAuthorized = true
                isReady = true
                PreferenceManager.setAuthorized(context, true)
                client?.send(TdApi.SetNetworkType(TdApi.NetworkTypeOther())) { r ->
                    Log.d(TAG, "SetNetworkType: ${r?.javaClass?.simpleName}")
                }
                authLatch.countDown()
                onClientReady?.invoke()
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                Log.d(TAG, "Logging out")
                isAuthorized = false
                isReady = false
                PreferenceManager.setAuthorized(context, false)
            }
            is TdApi.AuthorizationStateClosing -> {
                Log.d(TAG, "Closing")
                isAuthorized = false
                isReady = false
            }
            is TdApi.AuthorizationStateClosed -> {
                Log.d(TAG, "Closed")
                isInitialized = false
                isAuthorized = false
                isReady = false
                PreferenceManager.setAuthorized(context, false)
                onLoggedOut?.invoke()
                onLoggedOut = null
            }
            else -> Log.w(TAG, "Unhandled auth state: $state")
        }
    }

    fun sendCode(phone: String, callback: (Boolean) -> Unit) {
        Log.d(TAG, "sendCode; client=${System.identityHashCode(client)}")
        Log.d(TAG, getInitializationStatus())

        if (!isInitialized) {
            Log.e(TAG, "Client not initialized in sendCode")
            callback(false)
            return
        }

        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { result ->
            when (result) {
                is TdApi.Ok -> {
                    Log.d(TAG, "Code sent")
                    callback(true)
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Send code error: ${result.message}")
                    callback(false)
                }
                else -> {
                    Log.e(TAG, "Send code unknown: $result")
                    callback(false)
                }
            }
        }
    }

    fun verifyCode(code: String, callback: (Boolean) -> Unit) {
        Log.d(TAG, "verifyCode; client=${System.identityHashCode(client)}")
        Log.d(TAG, getInitializationStatus())

        if (!isInitialized) {
            Log.e(TAG, "Client not initialized in verifyCode")
            callback(false)
            return
        }

        client?.send(TdApi.CheckAuthenticationCode(code)) { result ->
            when (result) {
                is TdApi.Ok -> {
                    Log.d(TAG, "Code verified")
                    callback(true)
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Verify code error: ${result.message}")
                    callback(false)
                }
                else -> {
                    Log.e(TAG, "Verify code unknown: $result")
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
                    Log.d(TAG, "Password verified")
                    callback(true)
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Verify password error: ${result.message}")
                    callback(false)
                }
                else -> {
                    Log.e(TAG, "Verify password unknown: $result")
                    callback(false)
                }
            }
        }
    }

    fun loadChannels(callback: (List<Channel>) -> Unit) {
        Log.d(TAG, "loadChannels; client=${System.identityHashCode(client)}")
        Log.d(TAG, getInitializationStatus())

        // Сбрасываем состояние перед началом новой загрузки
        _areChannelsLoaded.value = false

        if (!isInitialized) {
            Log.e(TAG, "Client not initialized")
            callback(emptyList())
            _areChannelsLoaded.value = true // Новое: уведомляем, что каналы загружены (даже если ошибка)
            return
        }

        Thread {
            if (!waitForReady(15)) {
                Log.e(TAG, "Client not ready after timeout")
                callback(emptyList())
                _areChannelsLoaded.value = true // Новое: уведомляем, что каналы загружены (даже если ошибка)
                return@Thread
            }

            if (!isAuthorized) {
                Log.e(TAG, "Client not authorized")
                callback(emptyList())
                _areChannelsLoaded.value = true // Новое: уведомляем, что каналы загружены (даже если ошибка)
                return@Thread
            }

            client?.send(TdApi.GetChats(TdApi.ChatListMain(), 200)) { result ->
                when (result) {
                    is TdApi.Chats -> {
                        val channels = mutableListOf<Channel>()
                        var processed = 0
                        val total = result.chatIds.size

                        // Обработка случая, когда нет чатов
                        if (total == 0) {
                            Log.d(TAG, "loadChannels: no chats found")
                            callback(emptyList())
                            _areChannelsLoaded.value = true // Новое: уведомляем, что каналы загружены
                            return@send
                        }

                        for (chatId in result.chatIds) {
                            client?.send(TdApi.GetChat(chatId)) { chatResult ->
                                if (chatResult is TdApi.Chat) {
                                    val type = chatResult.type
                                    if (type is TdApi.ChatTypeSupergroup && type.isChannel) {
                                        val small = chatResult.photo?.small
                                        if (small != null) {
                                            chatIdToSmallId[chatId] = small.id
                                            fileIdToChatId[small.id] = chatId

                                            client?.send(TdApi.GetFile(small.id)) { getObj ->
                                                val file = getObj as? TdApi.File
                                                val ready = file?.local?.isDownloadingCompleted == true && !file.local.path.isNullOrBlank()
                                                if (ready) {
                                                    val path = file!!.local.path
                                                    chatIdToChannel[chatId]?.let { ch -> ch.photoPath = path }
                                                    onChannelPhotoUpdated?.invoke(chatId, path)
                                                    //Log.d(TAG, "Avatar local ready for chat=$chatId")
                                                } else {
                                                    client?.send(TdApi.DownloadFile(small.id, 32, 0, 0, true)) {
                                                        // Тихо ставим загрузку; результат придет в UpdateFile
                                                    }
                                                }
                                            }
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
                                    callback(channels.toList()) // Новое: передаем копию списка
                                    _areChannelsLoaded.value = true // Новое: уведомляем, что каналы загружены
                                }
                            }
                        }
                    }
                    else -> {
                        callback(emptyList())
                        _areChannelsLoaded.value = true // Новое: уведомляем, что каналы загружены (даже если ошибка)
                    }
                }
            }

        }.start()
    }

    fun redownloadPendingPhotos() {
        Log.d(TAG, "redownloadPendingPhotos; known=${chatIdToSmallId.size}")
        chatIdToSmallId.forEach { (chatId, smallId) ->
            val ch = chatIdToChannel[chatId]
            val need = ch == null || ch.photoPath.isNullOrBlank()
            if (need) {
                fileIdToChatId[smallId] = chatId
                client?.send(TdApi.GetFile(smallId)) { obj ->
                    val file = obj as? TdApi.File
                    val ready = file?.local?.isDownloadingCompleted == true && !file.local.path.isNullOrBlank()
                    if (ready) {
                        val path = file!!.local.path
                        chatIdToChannel[chatId]?.let { c -> c.photoPath = path }
                        onChannelPhotoUpdated?.invoke(chatId, path)
                        Log.d(TAG, "Avatar reapply local for chat=$chatId")
                    } else {
                        client?.send(TdApi.DownloadFile(smallId, 32, 0, 0, true)) {
                            // без лишних логов
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

        Log.d(TAG, "Get history for $channelId from=$fromDate")

        client?.send(TdApi.GetChatHistory(channelId, 0, 0, 1000, false)) { response ->
            try {
                when (response) {
                    is TdApi.Messages -> {
                        val messages = response.messages
                            .filter { it.date >= fromDate }
                            .mapNotNull { message ->
                                val time = try {
                                    // ✅ Используются ThreeTenABP классы
                                    Instant.ofEpochSecond(message.date.toLong())
                                        .atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                                } catch (e: Exception) {
                                    "??:??"
                                }

                                when (val content = message.content) {
                                    is TdApi.MessageText -> {
                                        val text = content.text.text.trim()
                                        if (text.isNotBlank()) "$time — $text" else null
                                    }
                                    is TdApi.MessagePhoto -> content.caption?.text?.trim()?.let { if (it.isNotBlank()) "$time — $it" else null }
                                    is TdApi.MessageVideo -> content.caption?.text?.trim()?.let { if (it.isNotBlank()) "$time — $it" else null }
                                    is TdApi.MessageDocument -> content.caption?.text?.trim()?.let { if (it.isNotBlank()) "$time — $it" else null }
                                    else -> null
                                }
                            }

                        if (cont.isActive) cont.resume(messages)
                    }

                    is TdApi.Error -> {
                        Log.e(TAG, "History error $channelId: ${response.message}")
                        if (cont.isActive) cont.resume(emptyList())
                    }

                    else -> {
                        Log.w(TAG, "History unknown for $channelId: ${response?.javaClass?.simpleName}")
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "History exception $channelId", e)
                if (cont.isActive) cont.resume(emptyList())
            }
        }

        cont.invokeOnCancellation {
            Log.d(TAG, "History canceled $channelId")
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
                Log.e(TAG, "Client not ready in paginated")
                cont.resume(emptyList())
                return
            }

            val request = TdApi.GetChatHistory(channelId, lastMessageId, 0, 100, false)

            client?.send(request) { response ->
                when (response) {
                    is TdApi.Messages -> {
                        val messages = response.messages
                        if (messages.isEmpty()) {
                            cont.resume(collectedMessages)
                            return@send
                        }

                        for (msg in messages) {
                            if (msg.date < fromDate) {
                                isDone = true
                                break
                            }

                            // ✅ Используются ThreeTenABP классы
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
                            cont.resume(collectedMessages)
                        } else {
                            loadNextPage()
                        }
                    }

                    is TdApi.Error -> {
                        Log.e(TAG, "Paginated error: ${response.message}")
                        cont.resume(collectedMessages)
                    }

                    else -> {
                        Log.w(TAG, "Paginated unknown: ${response?.javaClass?.simpleName}")
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

    fun logOut(onDone: (() -> Unit)? = null) {
        val c = client
        Log.d(TAG, "logOut requested; client=${System.identityHashCode(c)}")
        if (c == null) {
            onDone?.invoke()
            return
        }
        onLoggedOut = onDone
        c.send(TdApi.LogOut()) { r ->
            Log.d(TAG, "LogOut -> ${r?.javaClass?.simpleName}")
        }
    }

    fun close() {
        client?.send(TdApi.Close(), null)
        client = null
        isInitialized = false
        isAuthorized = false
        isReady = false
    }
}