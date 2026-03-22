package com.p2petrovich.telegramnewsreader.telegram

import android.content.Context
import android.util.Log
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
import java.util.concurrent.atomic.AtomicBoolean

import org.threeten.bp.Instant
import org.threeten.bp.ZoneId
import org.threeten.bp.format.DateTimeFormatter

class TelegramClient(private val context: Context) {

    private var client: Client? = null

    @Volatile
    private var isInitialized = false
    @Volatile
    private var isAuthorized = false
    @Volatile
    private var isReady = false

    private var authorizationState: TdApi.AuthorizationState? = null

    private val TAG = "TelegramClient"
    private val authLatch = CountDownLatch(1)

    // Для загрузки аватаров
    private val fileIdToChatId = ConcurrentHashMap<Int, Long>()
    private val chatIdToChannel = ConcurrentHashMap<Long, Channel>()
    private val chatIdToSmallId = ConcurrentHashMap<Long, Int>()

    var onChannelPhotoUpdated: ((channelId: Long, photoPath: String) -> Unit)? = null
    var onClientReady: (() -> Unit)? = null
    var onPasswordRequired: (() -> Unit)? = null

    private var onLoggedOut: (() -> Unit)? = null

    init {
        Log.d(TAG, "Constructor; holder=${System.identityHashCode(this)}")
        initializeClient()
    }

    private fun initializeClient() {
        try {
            client = Client.create({ update ->
                handleUpdate(update as TdApi.Update)
            }, null, null)

            if (client == null) {
                Log.e(TAG, "CRITICAL: Client.create() returned null")
                return
            }

            client?.send(
                TdApi.SetTdlibParameters(
                    false,
                    context.filesDir.absolutePath + "/" + ApiConfig.DATABASE_DIRECTORY,
                    context.filesDir.absolutePath + "/" + ApiConfig.FILES_DIRECTORY,
                    byteArrayOf(),
                    true, true, true, true,
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
                        Log.d(TAG, "TDLib parameters set OK")
                    }
                    is TdApi.Error -> Log.e(TAG, "SetTdlibParameters error: ${result.message}")
                    else -> Log.e(TAG, "SetTdlibParameters unknown: $result")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TelegramClient", e)
        }
    }

    private fun handleUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuthUpdate(update.authorizationState)
            is TdApi.UpdateFile -> {
                val f = update.file
                if (f.local.isDownloadingCompleted) {
                    val chatId = fileIdToChatId.remove(f.id)
                    if (chatId != null) {
                        chatIdToChannel[chatId]?.let { ch ->
                            ch.photoPath = f.local.path
                            onChannelPhotoUpdated?.invoke(chatId, f.local.path)
                        }
                    }
                }
            }
            else -> { /* ignore */ }
        }
    }

    private fun handleAuthUpdate(state: TdApi.AuthorizationState) {
        authorizationState = state
        when (state) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                isAuthorized = false; isReady = false
                PreferenceManager.setAuthorized(context, false)
            }
            is TdApi.AuthorizationStateWaitCode -> {
                isAuthorized = false; isReady = false
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                isAuthorized = false; isReady = false
                onPasswordRequired?.invoke()
            }
            is TdApi.AuthorizationStateReady -> {
                isAuthorized = true; isReady = true
                PreferenceManager.setAuthorized(context, true)
                client?.send(TdApi.SetNetworkType(TdApi.NetworkTypeOther())) {}
                authLatch.countDown()
                onClientReady?.invoke()
            }
            is TdApi.AuthorizationStateLoggingOut,
            is TdApi.AuthorizationStateClosing -> {
                isAuthorized = false; isReady = false
                PreferenceManager.setAuthorized(context, false)
            }
            is TdApi.AuthorizationStateClosed -> {
                isInitialized = false; isAuthorized = false; isReady = false
                PreferenceManager.setAuthorized(context, false)
                onLoggedOut?.invoke()
                onLoggedOut = null
            }
            else -> Log.w(TAG, "Unhandled auth state: $state")
        }
    }

    fun sendCode(phone: String, callback: (Boolean) -> Unit) {
        if (!isInitialized) { callback(false); return }
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { result ->
            callback(result is TdApi.Ok)
        }
    }

    fun verifyCode(code: String, callback: (Boolean) -> Unit) {
        if (!isInitialized) { callback(false); return }
        client?.send(TdApi.CheckAuthenticationCode(code)) { result ->
            callback(result is TdApi.Ok)
        }
    }

    fun verifyPassword(password: String, callback: (Boolean) -> Unit) {
        if (!isInitialized) { callback(false); return }
        client?.send(TdApi.CheckAuthenticationPassword(password)) { result ->
            callback(result is TdApi.Ok)
        }
    }

    fun loadChannels(callback: (List<Channel>) -> Unit) {
        if (!isInitialized) { callback(emptyList()); return }

        Thread {
            if (!waitForReady(15)) { callback(emptyList()); return@Thread }
            if (!isAuthorized) { callback(emptyList()); return@Thread }

            client?.send(TdApi.GetChats(TdApi.ChatListMain(), 200)) { result ->
                when (result) {
                    is TdApi.Chats -> {
                        if (result.chatIds.isEmpty()) {
                            callback(emptyList())
                            return@send
                        }

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

                                            client?.send(TdApi.GetFile(small.id)) { getObj ->
                                                val file = getObj as? TdApi.File
                                                if (file?.local?.isDownloadingCompleted == true && !file.local.path.isNullOrBlank()) {
                                                    chatIdToChannel[chatId]?.photoPath = file.local.path
                                                    onChannelPhotoUpdated?.invoke(chatId, file.local.path)
                                                } else {
                                                    client?.send(TdApi.DownloadFile(small.id, 32, 0, 0, true)) {}
                                                }
                                            }
                                        }

                                        val channel = Channel(
                                            id = chatId, accessHash = 0,
                                            title = chatResult.title, username = "",
                                            isSelected = false, newMessagesCount = 0, photoPath = null
                                        )
                                        chatIdToChannel[chatId] = channel
                                        synchronized(channels) { channels.add(channel) }
                                    }
                                }

                                synchronized(channels) {
                                    processed++
                                    if (processed == total) {
                                        callback(channels.toList())
                                    }
                                }
                            }
                        }
                    }
                    else -> callback(emptyList())
                }
            }
        }.start()
    }

    fun redownloadPendingPhotos() {
        chatIdToSmallId.forEach { (chatId, smallId) ->
            val ch = chatIdToChannel[chatId]
            if (ch == null || ch.photoPath.isNullOrBlank()) {
                fileIdToChatId[smallId] = chatId
                client?.send(TdApi.GetFile(smallId)) { obj ->
                    val file = obj as? TdApi.File
                    if (file?.local?.isDownloadingCompleted == true && !file.local.path.isNullOrBlank()) {
                        chatIdToChannel[chatId]?.photoPath = file.local.path
                        onChannelPhotoUpdated?.invoke(chatId, file.local.path)
                    } else {
                        client?.send(TdApi.DownloadFile(smallId, 32, 0, 0, true)) {}
                    }
                }
            }
        }
    }

    suspend fun getChannelMessagesPaginated(
        channelId: Long,
        fromDate: Long,
        maxMessages: Int = 3000
    ): List<String> = suspendCancellableCoroutine { cont ->

        if (!isInitialized || !isAuthorized || client == null) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        val collectedMessages = mutableListOf<String>()
        var lastMessageId = 0L
        var loadedTotal = 0
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun loadNextPage() {
            client?.send(TdApi.GetChatHistory(channelId, lastMessageId, 0, 100, false)) { response ->
                when (response) {
                    is TdApi.Messages -> {
                        val messages = response.messages
                        if (messages.isEmpty()) {
                            if (cont.isActive) cont.resume(collectedMessages)
                            return@send
                        }

                        var isDone = false
                        for (msg in messages) {
                            if (msg.date < fromDate) { isDone = true; break }

                            val time = try {
                                Instant.ofEpochSecond(msg.date.toLong())
                                    .atZone(ZoneId.systemDefault())
                                    .format(timeFormatter)
                            } catch (_: Exception) { "??:??" }

                            val text = when (val content = msg.content) {
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
                            if (cont.isActive) cont.resume(collectedMessages)
                        } else {
                            loadNextPage()
                        }
                    }
                    is TdApi.Error -> {
                        Log.e(TAG, "Paginated error: ${response.message}")
                        if (cont.isActive) cont.resume(collectedMessages)
                    }
                    else -> {
                        if (cont.isActive) cont.resume(collectedMessages)
                    }
                }
            }
        }

        loadNextPage()

        cont.invokeOnCancellation {
            Log.d(TAG, "History canceled $channelId")
        }
    }

    private fun waitForReady(timeoutSeconds: Long = 10): Boolean {
        return try {
            if (isReady) true else authLatch.await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) { false }
    }

    fun checkAuthState(): Boolean = isReady && isAuthorized

    fun logOut(onDone: (() -> Unit)? = null) {
        val c = client
        if (c == null) { onDone?.invoke(); return }
        onLoggedOut = onDone
        c.send(TdApi.LogOut()) {}
    }

    fun close() {
        client?.send(TdApi.Close(), null)
        client = null
        isInitialized = false
        isAuthorized = false
        isReady = false
    }
}
