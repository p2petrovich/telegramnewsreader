package com.p2petrovich.telegramnewsreader.telegram

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import com.p2petrovich.telegramnewsreader.ApiConfig
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.models.Channel
import com.p2petrovich.telegramnewsreader.utils.DebugConfig
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import com.p2petrovich.telegramnewsreader.utils.SecurityManager
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    // [FIX reset] Флаг «идёт выход из аккаунта». Во время LogOut TDLib возвращает
    // авторизацию в WaitTdlibParameters (готовится принять следующего пользователя),
    // и без этого флага обработчик повторно вызывал setTdlibParameters(), из-за чего
    // клиент «оживал» между LogOut и Close. Это и приводило к необходимости нажимать
    // «Сбросить авторизацию» дважды.
    @Volatile
    private var isLoggingOut = false

    private var authorizationState: TdApi.AuthorizationState? = null

    private val TAG = "TelegramClient"
    private var authDeferred: CompletableDeferred<Boolean>? = null

    private val fileIdToChatId = ConcurrentHashMap<Int, Long>()
    private val chatIdToChannel = ConcurrentHashMap<Long, Channel>()
    private val chatIdToSmallId = ConcurrentHashMap<Long, Int>()

    var onChannelPhotoUpdated: ((channelId: Long, photoPath: String) -> Unit)? = null
    var onClientReady: (() -> Unit)? = null
    var onPasswordRequired: (() -> Unit)? = null
    var onFatalError: ((message: String) -> Unit)? = null
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
                            // [FIX reset] Во время выхода НЕ переинициализируем клиент:
                            // ждём перехода LogOut -> Close -> Closed. Иначе старый клиент
                            // успевает заново открыть базу прямо перед удалением каталогов.
                            if (!isLoggingOut) {
                                setTdlibParameters()
                                applyProxySettings()
                            }
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
                        is TdApi.AuthorizationStateWaitRegistration -> {
                            Log.d(TAG, "Registration required")
                        }
                        is TdApi.AuthorizationStateClosing -> {
                            Log.d(TAG, "Closing client")
                        }
                        is TdApi.AuthorizationStateClosed -> {
                            isInitialized = false
                            isAuthorized = false
                            isReady = false
                            client = null
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
        val keyResult = SecurityManager.getDatabaseEncryptionKeyChecked(context)

        val encryptionKey: ByteArray = when (keyResult) {
            is SecurityManager.KeyResult.Ok -> keyResult.key
            is SecurityManager.KeyResult.LostNeedsWipe -> {
                // Ключ утерян: старая БД нечитаема. Сбрасываем каталоги и стартуем заново.
                Log.w(TAG, "DB key lost — wiping TDLib dirs and re-initializing")
                ApiConfig.tdlibDatabaseDir(context).deleteRecursively()
                ApiConfig.tdlibFilesDir(context).deleteRecursively()
                // Сообщаем пользователю, что потребуется повторный вход.
                onFatalError?.invoke(context.getString(R.string.db_key_lost_error))

                // [FIX] Сбрасываем маркер ПЕРЕД retry, иначе getDatabaseEncryptionKeyChecked
                // снова увидит KEY_MARKER=true и вернёт LostNeedsWipe — бесконечный цикл.
                // Маркер нужно сбросить именно здесь, синхронно с удалением базы TDLib,
                // чтобы новый ключ соответствовал новой (пустой) базе.
                SecurityManager.resetKeyMarker(context)

                Log.w(TAG, "Attempting retry after wipe...")
                when (val retry = SecurityManager.getDatabaseEncryptionKeyChecked(context)) {
                    is SecurityManager.KeyResult.Ok -> {
                        Log.w(TAG, "Retry succeeded, key length=${retry.key.size}")
                        retry.key
                    }
                    else -> {
                        Log.e(TAG, "Retry FAILED: $retry")
                        onFatalError?.invoke(context.getString(R.string.security_error_keystore_unavailable))
                        return
                    }
                }
            }
            is SecurityManager.KeyResult.Unavailable -> {
                onFatalError?.invoke(context.getString(R.string.security_error_keystore_unavailable_start))
                return
            }
        }

        client?.send(TdApi.SetTdlibParameters(
            false, // useTestDc
            ApiConfig.tdlibDatabaseDir(context).absolutePath, // databaseDirectory
            ApiConfig.tdlibFilesDir(context).absolutePath, // filesDirectory
            encryptionKey, // databaseEncryptionKey
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
        )) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "SetTdlibParameters error: ${result.message} (code: ${result.code})")
            } else {
                Log.d(TAG, "SetTdlibParameters success")
            }
        }
    }

    fun checkAuthState(): Boolean = isReady && isAuthorized

    fun setOnLoggedOutListener(listener: () -> Unit) { onLoggedOut = listener }

    fun setPhoneNumber(phoneNumber: String, callback: (Boolean, String?) -> Unit) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null)) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "SetPhoneNumber error: ${result.message} (code: ${result.code})")
                callback(false, result.message)
            } else {
                callback(result is TdApi.Ok, null)
            }
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

        // Используем CoroutineScope для безопасного ожидания готовности клиента.
        // Используем Dispatchers.IO, так как TDLib делает сетевые запросы.
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            if (!waitForReady(15)) { callback(emptyList()); return@launch }
            if (!isAuthorized) { callback(emptyList()); return@launch }

            val callbackFired = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())

            client?.send(TdApi.GetChats(TdApi.ChatListMain(), 200)) { result ->
                when (result) {
                    is TdApi.Chats -> {
                        if (result.chatIds.isEmpty()) {
                            if (callbackFired.compareAndSet(false, true)) callback(emptyList())
                            return@send
                        }

                        val channels: Array<Channel?> = arrayOfNulls(result.chatIds.size)
                        val processed = AtomicInteger(0)
                        val total = result.chatIds.size

                        fun finishOnce() {
                            if (callbackFired.compareAndSet(false, true)) {
                                handler.removeCallbacksAndMessages(null)
                                val list = synchronized(channels) { channels.filterNotNull() }
                                callback(list)
                            }
                        }

                        // Watchdog timeout
                        handler.postDelayed({
                            Log.w(TAG, "loadChannels watchdog fired: ${processed.get()}/$total processed, returning partial result")
                            finishOnce()
                        }, 12_000L)

                        for ((index, chatId) in result.chatIds.withIndex()) {
                            client?.send(TdApi.GetChat(chatId)) { chatResult ->
                                try {
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
                                } finally {
                                    if (processed.incrementAndGet() >= total) {
                                        finishOnce()
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        if (callbackFired.compareAndSet(false, true)) callback(emptyList())
                    }
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
            Log.w(TAG, "getChannelMessagesPaginated: Client not ready for channel $channelId")
            continuation.resume(emptyList()) { }
            return@suspendCancellableCoroutine
        }

        // Принудительная синхронизация
        client?.send(TdApi.OpenChat(channelId)) { }

        val messages = mutableListOf<String>()
        val isCancelled = AtomicBoolean(false)
        val isResumed = AtomicBoolean(false)
        val isChatClosed = AtomicBoolean(false)
        var loadedTotal = 0
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun closeChatOnce() {
            if (isChatClosed.getAndSet(true)) return
            try { client?.send(TdApi.CloseChat(channelId)) { } } catch (_: Exception) {}
        }

        continuation.invokeOnCancellation {
            Log.d(TAG, "History canceled for channel $channelId")
            isCancelled.set(true)
            closeChatOnce()
        }

        fun resumeOnce(result: List<String>) {
            if (isResumed.getAndSet(true)) return
            closeChatOnce()
            if (DebugConfig.LOG_TG_HISTORY) {
                Log.d(TAG, "Channel $channelId: finished, found ${result.size} messages")
            }
            if (continuation.isActive) continuation.resume(result) { }
        }

        fun loadPage(fromMessageId: Long) {
            if (isCancelled.get() || isResumed.get()) return

            if (DebugConfig.LOG_TG_HISTORY) {
                Log.v(TAG, "Channel $channelId: requesting page from $fromMessageId")
            }
            client?.send(TdApi.GetChatHistory(channelId, fromMessageId, 0, 100, false)) { response ->
                if (isCancelled.get() || isResumed.get()) return@send

                when (response) {
                    is TdApi.Messages -> {
                        val page = response.messages
                        if (DebugConfig.LOG_TG_HISTORY) {
                            Log.v(TAG, "Channel $channelId: received ${page.size} items from TDLib")
                        }
                        
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

                            val timeStr = try {
                                Instant.ofEpochSecond(msg.date.toLong())
                                    .atZone(ZoneId.systemDefault())
                                    .format(timeFormatter)
                            } catch (_: Exception) { "??:??" }

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
                                messages.add(timeStr + TIME_SEPARATOR + text)
                            }
                        }

                        loadedTotal += page.size
                        val lastId = page.last().id

                        // [CRITICAL FIX] Загрузка не должна останавливаться, если получено меньше 100 сообщений.
                        if (reachedDateLimit || loadedTotal >= maxMessages) {
                            if (DebugConfig.LOG_TG_HISTORY) {
                                Log.d(TAG, "Channel $channelId: reached limits (date=$reachedDateLimit, total=$loadedTotal)")
                            }
                            resumeOnce(messages)
                        } else {
                            loadPage(lastId)
                        }
                    }
                    is TdApi.Error -> {
                        Log.e(TAG, "GetChatHistory error for $channelId: ${response.message}")
                        resumeOnce(messages)
                    }
                    else -> resumeOnce(messages)
                }
            }
        }

        // Ждем 500мс после OpenChat для синхронизации
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isCancelled.get() && !isResumed.get()) {
                client?.send(TdApi.GetChat(channelId)) { chatResult ->
                    if (isCancelled.get() || isResumed.get()) return@send
                    
                    val lastMsgId = (chatResult as? TdApi.Chat)?.lastMessage?.id ?: 0L
                    if (DebugConfig.LOG_TG_HISTORY) {
                        Log.d(TAG, "Channel $channelId: lastMsgId=$lastMsgId, starting loadPage (fromDate=$fromDate)")
                    }
                    
                    loadPage(if (lastMsgId > 0) lastMsgId + 1 else 0)
                }
            }
        }, 500)
    }

    fun logoutAndReset(callback: () -> Unit) {
        isLoggingOut = true
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
    }

    /**
     * Очищает локальный кэш сообщений TDLib через оптимизацию хранилища.
     */
    fun clearTtsRelatedCache(callback: (Boolean) -> Unit) {
        Log.d(TAG, "Full TDLib cache reset started...")
        client?.send(TdApi.OptimizeStorage(
            0L, -1, -1, -1, null, null, null, true, 0
        )) { result ->
            val success = result is TdApi.StorageStatistics
            Log.d(TAG, "TDLib storage optimization result: $success")
            callback(success)
        }
    }

    fun applyProxySettings() {
        if (PreferenceManager.isProxyEnabled(context)) {
            val proxies = PreferenceManager.getProxyList(context)
            val activeProxy = proxies.find { it.isEnabled }

            if (activeProxy != null) {
                val host = activeProxy.host
                val port = activeProxy.port
                val secret = extractSecret(activeProxy.secret)

                if (host.isNotEmpty() && port > 0) {
                    client?.send(TdApi.GetProxies()) { result ->
                        if (result is TdApi.Proxies) {
                            result.proxies.forEach { p ->
                                client?.send(TdApi.RemoveProxy(p.id)) {}
                            }
                        }
                        val proxyType = TdApi.ProxyTypeMtproto(secret)
                        Log.d(TAG, "Applying MTProto proxy: $host:$port")
                        client?.send(TdApi.AddProxy(host, port, true, proxyType)) { res ->
                            if (res is TdApi.Proxy) {
                                client?.send(TdApi.EnableProxy(res.id)) {
                                    Log.d(TAG, "Proxy applied and enabled")
                                }
                            }
                        }
                    }
                    return
                }
            }
        }
        client?.send(TdApi.DisableProxy()) {
            Log.d(TAG, "Proxy disabled")
        }
    }

    private fun extractSecret(input: String): String {
        val trimmed = input.trim()
        Regex("[?&]secret=([^&\\s]+)").find(trimmed)?.let { return it.groupValues[1] }
        return trimmed
    }

    fun testProxy(host: String, port: Int, secret: String, callback: (Double?, String?) -> Unit) {
        if (client == null) {
            callback(null, context.getString(R.string.lib_not_ready))
            return
        }

        val cleanSecret = extractSecret(secret)
        val proxyType = TdApi.ProxyTypeMtproto(cleanSecret)

        fun tryDc(dcIds: List<Int>) {
            if (dcIds.isEmpty()) {
                callback(null, context.getString(R.string.proxy_no_tg_response))
                return
            }
            val dc = dcIds.first()
            client?.send(TdApi.TestProxy(host, port, proxyType, dc, 10.0)) { result ->
                when (result) {
                    is TdApi.Seconds -> callback(result.seconds, null)
                    is TdApi.Ok -> callback(0.0, null)
                    is TdApi.Error -> {
                        if (result.code == 400) {
                            tryDc(dcIds.drop(1))
                        } else {
                            callback(null, context.getString(R.string.proxy_error_with_code, result.message, result.code))
                        }
                    }
                    else -> {
                        val typeName = result?.javaClass?.simpleName ?: "null"
                        Log.e(TAG, "TestProxy unknown result: $typeName")
                        callback(null, context.getString(R.string.proxy_unexpected_response, typeName))
                    }
                }
            }
        }

        tryDc(listOf(2, 1, 3))
    }

    /**
     * Ожидает готовности клиента и завершения авторизации.
     * Переведено на suspend/delay вместо Thread.sleep для предотвращения ANR.
     */
    private suspend fun waitForReady(timeoutSec: Int): Boolean {
        return try {
            val startTime = System.currentTimeMillis()
            val timeoutMs = timeoutSec * 1000L

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (isReady && authDeferred?.isCompleted == true) {
                    return true
                }
                delay(100)
            }

            false
        } catch (e: CancellationException) {
            Log.e(TAG, "Waiting for ready was cancelled", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for ready", e)
            false
        }
    }
}
