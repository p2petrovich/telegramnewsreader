package com.example.telegramnewsreader.telegram

import android.content.Context
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import com.example.telegramnewsreader.ApiConfig
import com.example.telegramnewsreader.models.TelegramChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.resume

class TelegramClient(private val context: Context) {

    private var client: Client? = null
    private val latch = CountDownLatch(1)

    init {
        client = Client.create({ update ->
            if (update is TdApi.UpdateAuthorizationState) {
                handleAuthUpdate(update.authorizationState)
            }
        }, null, null)

        // Передаём параметры напрямую в SetTdlibParameters
        client?.send(TdApi.SetTdlibParameters(
            false, // useTestDc
            context.filesDir.absolutePath + "/" + ApiConfig.DATABASE_DIRECTORY, // databaseDirectory
            context.filesDir.absolutePath + "/" + ApiConfig.FILES_DIRECTORY, // filesDirectory
            byteArrayOf(), // databaseEncryptionKey
            true, // useFileDatabase
            true, // useChatInfoDatabase
            true, // useMessageDatabase
            true, // useSecretChats
            ApiConfig.API_ID, // apiId
            ApiConfig.API_HASH, // apiHash
            "en", // systemLanguageCode
            "Android Device", // deviceModel
            android.os.Build.VERSION.RELEASE, // systemVersion
            "1.0" // applicationVersion
        ), { result ->
            if (result is TdApi.Ok) {
                latch.countDown()
            } else {
                Log.e("TelegramClient", "Failed to set TDLib parameters: $result")
            }
        })
        latch.await() // Ждём инициализации
    }

    private fun handleAuthUpdate(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                Log.d("TelegramClient", "Waiting for phone number")
            }
            is TdApi.AuthorizationStateWaitCode -> {
                Log.d("TelegramClient", "Waiting for authentication code")
            }
            is TdApi.AuthorizationStateReady -> {
                Log.d("TelegramClient", "Authorization complete")
            }
            else -> {
                Log.e("TelegramClient", "Unhandled auth state: $state")
            }
        }
    }

    fun sendCode(phone: String, callback: (Boolean) -> Unit) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null), { result ->
            if (result is TdApi.Ok) callback(true) else {
                Log.e("TelegramClient", "Failed to send code: $result")
                callback(false)
            }
        })
    }

    fun verifyCode(code: String, callback: (Boolean) -> Unit) {
        client?.send(TdApi.CheckAuthenticationCode(code), { result ->
            if (result is TdApi.Ok) callback(true) else {
                Log.e("TelegramClient", "Failed to verify code: $result")
                callback(false)
            }
        })
    }

    fun loadChannels(callback: (List<TelegramChannel>) -> Unit) {
        client?.send(TdApi.GetChats(TdApi.ChatListMain(), 100), { result ->
            if (result is TdApi.Chats) {
                val channels = mutableListOf<TelegramChannel>()
                val innerLatch = CountDownLatch(result.chatIds.size)
                for (id in result.chatIds) {
                    client?.send(TdApi.GetChat(id), { chatResult ->
                        if (chatResult is TdApi.Chat && chatResult.type is TdApi.ChatTypeSupergroup) {
                            val sg = chatResult.type as TdApi.ChatTypeSupergroup
                            if (sg.isChannel) {
                                channels.add(TelegramChannel(id, chatResult.title, "", "", false, 0))
                            }
                        }
                        innerLatch.countDown()
                    })
                }
                innerLatch.await()
                callback(channels)
            } else {
                Log.e("TelegramClient", "Failed to load chats: $result")
                callback(emptyList())
            }
        })
    }
    suspend fun getChannelMessagesSuspend(channelId: Long, fromDate: Long): List<String> =
        suspendCancellableCoroutine { continuation ->
            // Вызываем вашу существующую функцию с колбэком
            getMessages(channelId, fromDate) { messages ->
                if (continuation.isActive) {
                    continuation.resume(messages)
                }
            }
            // Вы можете также захотеть обработать отмену корутины,
            // если TDLib предоставляет способы отмены запросов,
            // но для простоты начнем с этого.
            // continuation.invokeOnCancellation { /* ... */ }
        }
    fun getMessages(channelId: Long, fromDate: Long, callback: (List<String>) -> Unit) {
        client?.send(TdApi.GetChatHistory(channelId, 0, 0, 100, false), { result ->
            if (result is TdApi.Messages) {
                val texts = result.messages.filter {
                    it.date > fromDate && it.content is TdApi.MessageText
                }.map { (it.content as TdApi.MessageText).text.text }
                callback(texts)
            } else {
                Log.e("TelegramClient", "Failed to get messages: $result")
                callback(emptyList())
            }
        })
    }
}