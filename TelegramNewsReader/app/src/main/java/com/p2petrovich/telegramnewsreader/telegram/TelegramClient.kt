package com.p2petrovich.telegramnewsreader.telegram

import android.content.Context
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import com.p2petrovich.telegramnewsreader.ApiConfig
import com.p2petrovich.telegramnewsreader.models.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.resume
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
    private var authDeferred: CompletableDeferred<Boolean>? = null

    private val fileIdToChatId = ConcurrentHashMap<Int, Long>()
    private val chatIdToChannel = ConcurrentHashMap<Long, Channel>()
    private val chatIdToSmallId = ConcurrentHashMap<Long, Int>()

    var onChannelPhotoUpdated: ((channelId: Long, photoPath: String) -> Unit)? = null
    var onClientReady: (() -> Unit)? = null
    var onPasswordRequired: (() -> Unit)? = null
    private var onLoggedOut: (() -> Unit)? = null

    companion object {
        // Разделитель между временем и текстом сообщения.
        // Используется em-dash (U+2014). На него завязаны регексы в TextProcessor.
        private const val TIME_SEPARATOR = " \u2014 "
    }

    init {
        Log.d(TAG, "Constructor; holder=${System.identityHashCode(this)}")
        initializeClient()
    }

    private fun resetAuthDeferred() {
        authDeferred = CompletableDeferred()
    }

    private fun initializeClient() {
        resetAuthDeferred()
        client = Client.create({ update ->
            when (update) {
                is TdApi.UpdateAuthorizationState -> {
                    authorizationState = update.authorizationState
                    when (update.authorizationState) {
                        is TdApi.AuthorizationStateReady -> {
                            isReady = true
                            isInitialized = true
                            isAuthorized = true
                            authDeferred?.complete(true)
                            onClientReady?.invoke()
                        }
                        is TdApi.AuthorizationStateWaitTdlibParameters -> {
                            setTdlibParameters()
                        }
                        is TdApi.AuthorizationStateWaitPhoneNumber -> {
                            isAuthorized = false
                        }
                        is TdApi.AuthorizationStateWaitCode -> {
                            Log.d(TAG, "Waiting for auth code")
                        }
                        is TdApi.AuthorizationStateWaitPassword -> {
                            Log.d(TAG, "Waiting for 2FA password")
                            onPasswordRequired?.invoke()
                        }
                        is TdApi.AuthorizationStateClosed -> {
                            isInitialized = false
                            isAuthorized = false
                            isReady = false
                            resetAuthDeferred()
                            onLoggedOut?.invoke()
                        }
                        else -> {}
                    }
                }
                is TdApi.UpdateFile -> {
                    val file = update.file
                    val fileId = file.id
                    val chatId = fileIdToChatId[fileId]
                    if (chatId != null && file.local?.isDownloadingCompleted == true && !file.local.path.isNullOrBlank()) {
                        chatIdToChannel[chatId]?.photoPath = file.local.path
                        onChannelPhotoUpdated?.invoke(chatId, file.local.path)
                    }
                }
                else -> {}
            }
        }, { error ->
            Log.e(TAG, "TDLib fatal error: ${error.message}")
        }, { logMessage ->
            Log.v(TAG, "TDLib log: ${logMessage.message}")
        })
    }

    private fun setTdlibParameters() {
        client?.send(TdApi.SetTdlibParameters(
            false, // useTestDc
            context.getDir("tdlib", Context.MODE_PRIVATE).absolutePath, // databaseDirectory
            context.getDir("tdlib_files", Context.MODE_PRIVATE).absolutePath, // filesDirectory
            null as ByteArray?, // databaseEncryptionKey
            true, // useFileDatabase
            true, // useChatInfoDatabase
            true, // useMessageDatabase
            false, // useSecretChats
            ApiConfig.TELEGRAM_API_ID, // apiId
            ApiConfig.TELEGRAM_API_HASH, // apiHash
            "ru", // systemLanguageCode
            android.os.Build.MODEL, // deviceModel
            "2.0", // applicationVersion
            android.os.Build.VERSION.RELEASE // systemVersion
        )) {}
    }

    fun checkAuthState(): Boolean = isReady && isAuthorized

    fun setOnLoggedOutListener(listener: () -> Unit) { onLoggedOut = listener }

    fun setPhoneNumber(phoneNumber: String, callback: (Boolean) -> Unit) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null)) { result ->
            callback(result is TdApi.Ok)
        }
    }

    fun checkAuthenticationCode(code: String, callback: (Boolean, String) -> Unit) {
        client?.send(TdApi.CheckAuthenticationCode(code)) { result ->
            when (result) {
                is TdApi.Ok -> callback(true, "OK")
                is TdApi.Error -> callback(false, result.message)
                else -> callback(false, "Unknown response")
            }
        }
    }

    fun checkAuthenticationPassword(password: String, callback: (Boolean, String) -> Unit) {
        client?.send(TdApi.CheckAuthenticationPassword(password)) { result ->
            when (result) {
                is TdApi.Ok -> callback(true, "OK")
                is TdApi.Error -> callback(false, result.message)
                else -> callback(false, "Unknown response")
            }
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

                        val channels: Array<Channel?> = arrayOfNulls(result.chatIds.size)
                        var processed = 0
                        val total = result.chatIds.size

                        for ((index, chatId) in result.chatIds.withIndex()) {
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
                                        synchronized(channels) {
                                            if (index < channels.size) {
                                                channels[index] = channel
                                            }
                                        }
                                    }
                                }

                                synchronized(channels) {
                                    processed++
                                    if (processed == total) {
                                        callback(channels.filterNotNull())
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

    /**
     * ПАТЧ 1: рекурсивная пагинация + caption'ы из медиа + префикс HH:mm — текст.
     */
    suspend fun getChannelMessagesPaginated(
        channelId: Long,
        fromDate: Long,
        maxMessages: Int = 3000
    ): List<String> = suspendCancellableCoroutine { continuation ->

        if (!isInitialized || !isAuthorized || client == null) {
            continuation.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        val messages = mutableListOf<String>()
        val isCancelled = AtomicBoolean(false)
        val isResumed = AtomicBoolean(false)
        var loadedTotal = 0
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        continuation.invokeOnCancellation {
            Log.d(TAG, "History canceled for channel $channelId")
            isCancelled.set(true)
        }

        fun resumeOnce(result: List<String>) {
            if (isResumed.getAndSet(true)) return
            if (continuation.isActive) continuation.resume(result)
        }

        fun loadPage(fromMessageId: Long) {
            if (isCancelled.get() || isResumed.get()) return

            client?.send(TdApi.GetChatHistory(channelId, fromMessageId, 0, 100, false)) { response ->
                if (isCancelled.get() || isResumed.get()) return@send

                when (response) {
                    is TdApi.Messages -> {
                        val page = response.messages
                        if (page.isEmpty()) {
                            resumeOnce(messages)
                            return@send
                        }

                        var reachedDateLimit = false
                        for (msg in page) {
                            if (msg.date < fromDate) {
                                reachedDateLimit = true
                                break
                            }

                            val time = try {
                                Instant.ofEpochSecond(msg.date.toLong())
                                    .atZone(ZoneId.systemDefault())
                                    .format(timeFormatter)
                            } catch (_: Exception) {
                                "??:??"
                            }

                            val text: String? = when (val content = msg.content) {
                                is TdApi.MessageText      -> content.text.text.trim()
                                is TdApi.MessagePhoto     -> content.caption?.text?.trim()
                                is TdApi.MessageVideo     -> content.caption?.text?.trim()
                                is TdApi.MessageDocument  -> content.caption?.text?.trim()
                                is TdApi.MessageAnimation -> content.caption?.text?.trim()
                                is TdApi.MessageAudio     -> content.caption?.text?.trim()
                                else -> null
                            }

                            if (!text.isNullOrBlank()) {
                                // Формат "HH:mm — текст" — на этот разделитель завязаны фильтры TextProcessor
                                messages.add(time + TIME_SEPARATOR + text)
                            }
                        }

                        loadedTotal += page.size
                        val lastId = page.last().id

                        if (reachedDateLimit || loadedTotal >= maxMessages || page.size < 100) {
                            resumeOnce(messages)
                        } else {
                            // Рекурсивный запрос следующей страницы
                            loadPage(lastId)
                        }
                    }
                    is TdApi.Error -> {
                        Log.e(TAG, "GetChatHistory error: ${response.message}")
                        resumeOnce(messages)
                    }
                    else -> resumeOnce(messages)
                }
            }
        }

        loadPage(0)
    }

    fun logoutAndReset(callback: () -> Unit) {
        client?.send(TdApi.LogOut()) {
            isInitialized = false
            isAuthorized = false
            isReady = false
            resetAuthDeferred()
            callback()
        }
    }

    fun close() {
        client?.send(TdApi.Close()) { }
        client = null
    }

    private fun waitForReady(timeoutSec: Int): Boolean {
        return try {
            val startTime = System.currentTimeMillis()
            val timeoutMs = timeoutSec * 1000L

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (isReady && authDeferred?.isCompleted == true) {
                    return true
                }
                Thread.sleep(100)
            }

            false
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for ready", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for ready", e)
            false
        }
    }
}
