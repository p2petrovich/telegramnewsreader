package com.p2petrovich.telegramnewsreader.utils

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Единый пул HTTP-клиентов для всего приложения.
 * Обеспечивает эффективное переиспользование соединений и потоков.
 */
object HttpClients {
    /**
     * Общий клиент. Рекомендуется использовать newBuilder() если нужны специфичные таймауты,
     * так как это позволяет делить один и тот же ConnectionPool и Dispatcher.
     */
    val shared: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Вызывать при завершении работы приложения или выгрузке основных сервисов,
     * чтобы корректно остановить потоки и закрыть соединения.
     */
    fun shutdown() {
        try {
            shared.dispatcher.executorService.shutdown()
            shared.connectionPool.evictAll()
            shared.cache?.close()
        } catch (_: Exception) {}
    }
}
