package com.example.telegramnewsreader.telegram

import android.content.Context
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import com.example.telegramnewsreader.ApiConfig
import com.example.telegramnewsreader.models.TelegramChannel
import com.example.telegramnewsreader.model.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class TelegramClient(private val context: Context) {

    private var client: Client? = null
    private var isInitialized = false
    private var isAuthorized = false
    private var authorizationState: TdApi.AuthorizationState? = null
    private val TAG = "TelegramClient"

    // Для ожидания готовности клиента
    private val authLatch = CountDownLatch(1)
    private var isReady = false

    init {
        initializeClient()
    }

    private fun initializeClient() {
        try {
            Log.d(TAG, "Initializing TelegramClient...")

            client = Client.create({ update ->
                handleUpdate(update as TdApi.Update)
            }, null, null)

            // Настройка параметров TDLib
            client?.send(TdApi.SetTdlibParameters(
                false, // useTestDc
                context.filesDir.absolutePath + "/" + ApiConfig.DATABASE_DIRECTORY,
                context.filesDir.absolutePath + "/" + ApiConfig.FILES_DIRECTORY,
                byteArrayOf(), // databaseEncryptionKey
                true, // useFileDatabase
                true, // useChatInfoDatabase
                true, // useMessageDatabase
                true, // useSecretChats
                ApiConfig.API_ID,
                ApiConfig.API_HASH,
                "ru", // systemLanguageCode - изменено на русский
                "Android Device", // deviceModel
                android.os.Build.VERSION.RELEASE, // systemVersion
                "1.0" // applicationVersion
            ), { result ->
                when (result) {
                    is TdApi.Ok -> {
                        Log.d(TAG, "TDLib parameters set successfully")
                        isInitialized = true
                    }
                    is TdApi.Error -> {
                        Log.e(TAG, "Failed to set TDLib parameters: ${result.message}")
                    }
                    else -> {
                        Log.e(TAG, "Unknown result when setting TDLib parameters: $result")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TelegramClient: ${e.message}", e)
        }
    }

    private fun handleUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> {
                handleAuthUpdate(update.authorizationState)
            }
            is TdApi.UpdateNewChat -> {
                Log.d(TAG, "New chat received: ${update.chat.title}")
            }
            else -> {
                // Обработка других обновлений если нужно
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
            }
            is TdApi.AuthorizationStateWaitCode -> {
                Log.d(TAG, "Waiting for authentication code")
                isAuthorized = false
                isReady = false
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
            is TdApi.AuthorizationStateWaitPassword -> {
                Log.d(TAG, "Waiting for password")
                isAuthorized = false
                isReady = false
            }
            is TdApi.AuthorizationStateReady -> {
                Log.d(TAG, "Authorization complete - client is ready!")
                isAuthorized = true
                isReady = true
                authLatch.countDown()
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

    // Метод для ожидания готовности клиента
    private fun waitForReady(timeoutSeconds: Long = 10): Boolean {
        return try {
            if (isReady) {
                Log.d(TAG, "Client already ready")
                true
            } else {
                Log.d(TAG, "Waiting for client to be ready...")
                authLatch.await(timeoutSeconds, TimeUnit.SECONDS)
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for client ready", e)
            false
        }
    }

    // Проверка текущего состояния авторизации
    fun checkAuthState(): Boolean {
        return isReady && isAuthorized
    }

    fun sendCode(phone: String, callback: (Boolean) -> Unit) {
        if (!isInitialized) {
            Log.e(TAG, "Client not initialized")
            callback(false)
            return
        }

        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null), { result ->
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
        })
    }

    fun verifyCode(code: String, callback: (Boolean) -> Unit) {
        if (!isInitialized) {
            Log.e(TAG, "Client not initialized")
            callback(false)
            return
        }

        client?.send(TdApi.CheckAuthenticationCode(code), { result ->
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
        })
    }

    fun loadChannels(callback: (List<com.example.telegramnewsreader.model.Channel>) -> Unit) {
        Log.d(TAG, "loadChannels called")

        if (!isInitialized) {
            Log.e(TAG, "Client not initialized")
            callback(emptyList())
            return
        }

        // Запускаем в отдельном потоке для ожидания готовности
        Thread {
            Log.d(TAG, "Waiting for client to be ready...")

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

            Log.d(TAG, "Client is ready and authorized, loading channels...")

            client?.send(TdApi.GetChats(TdApi.ChatListMain(), 100), { result ->
                when (result) {
                    is TdApi.Chats -> {
                        Log.d(TAG, "Received ${result.chatIds.size} chats")

                        if (result.chatIds.isEmpty()) {
                            Log.w(TAG, "No chats found")
                            callback(emptyList())
                            return@send
                        }

                        val channels = mutableListOf<com.example.telegramnewsreader.model.Channel>()
                        var processedCount = 0
                        val totalCount = result.chatIds.size

                        for (chatId in result.chatIds) {
                            client?.send(TdApi.GetChat(chatId), { chatResult ->
                                when (chatResult) {
                                    is TdApi.Chat -> {
                                        Log.d(TAG, "Processing chat: ${chatResult.title}, type: ${chatResult.type::class.simpleName}")

                                        when (chatResult.type) {
                                            is TdApi.ChatTypeSupergroup -> {
                                                val supergroup = chatResult.type as TdApi.ChatTypeSupergroup
                                                if (supergroup.isChannel) {
                                                    Log.d(TAG, "Found channel: ${chatResult.title}")
                                                    channels.add(com.example.telegramnewsreader.model.Channel(
                                                        id = chatId,
                                                        accessHash = 0L,
                                                        title = chatResult.title,
                                                        username = "",
                                                        isSelected = false
                                                    ))
                                                } else {
                                                    Log.d(TAG, "Found supergroup: ${chatResult.title}")
                                                    // Добавляем также супергруппы
                                                    channels.add(com.example.telegramnewsreader.model.Channel(
                                                        id = chatId,
                                                        accessHash = 0L,
                                                        title = chatResult.title,
                                                        username = "",
                                                        isSelected = false
                                                    ))
                                                }
                                            }
                                            is TdApi.ChatTypeBasicGroup -> {
                                                Log.d(TAG, "Found basic group: ${chatResult.title}")
                                                // Добавляем базовые группы
                                                channels.add(com.example.telegramnewsreader.model.Channel(
                                                    id = chatId,
                                                    accessHash = 0L,
                                                    title = chatResult.title,
                                                    username = "",
                                                    isSelected = false
                                                ))
                                            }
                                            is TdApi.ChatTypePrivate -> {
                                                Log.d(TAG, "Found private chat: ${chatResult.title}")
                                                // Приватные чаты обычно не нужны для новостей
                                            }
                                            is TdApi.ChatTypeSecret -> {
                                                Log.d(TAG, "Found secret chat: ${chatResult.title}")
                                                // Секретные чаты не нужны
                                            }
                                        }
                                    }
                                    is TdApi.Error -> {
                                        Log.e(TAG, "Error getting chat $chatId: ${chatResult.message}")
                                    }
                                }

                                processedCount++
                                if (processedCount == totalCount) {
                                    Log.d(TAG, "Processed all chats. Found ${channels.size} channels/groups")
                                    callback(channels)
                                }
                            })
                        }
                    }
                    is TdApi.Error -> {
                        Log.e(TAG, "Failed to load chats: ${result.message}")
                        callback(emptyList())
                    }
                    else -> {
                        Log.e(TAG, "Unknown result when loading chats: $result")
                        callback(emptyList())
                    }
                }
            })
        }.start()
    }

    suspend fun getChannelMessagesSuspend(channelId: Long, fromDate: Long): List<String> =
        suspendCancellableCoroutine { continuation ->
            getMessages(channelId, fromDate) { messages ->
                if (continuation.isActive) {
                    continuation.resume(messages)
                }
            }
        }

    fun getMessages(channelId: Long, fromDate: Long, callback: (List<String>) -> Unit) {
        if (!isInitialized || !isAuthorized) {
            Log.e(TAG, "Client not initialized or not authorized")
            callback(emptyList())
            return
        }

        client?.send(TdApi.GetChatHistory(channelId, 0, 0, 100, false), { result ->
            when (result) {
                is TdApi.Messages -> {
                    val texts = result.messages
                        .filter { it.date > fromDate && it.content is TdApi.MessageText }
                        .map { (it.content as TdApi.MessageText).text.text }

                    Log.d(TAG, "Found ${texts.size} messages for channel $channelId")
                    callback(texts)
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Failed to get messages: ${result.message}")
                    callback(emptyList())
                }
                else -> {
                    Log.e(TAG, "Unknown result when getting messages: $result")
                    callback(emptyList())
                }
            }
        })
    }

    fun close() {
        client?.send(TdApi.Close(), null)
        client = null
        isInitialized = false
        isAuthorized = false
        isReady = false
    }
}