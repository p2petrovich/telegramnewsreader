package com.p2petrovich.telegramnewsreader.utils

import android.util.Log
import com.p2petrovich.telegramnewsreader.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiProcessor {
    private const val TAG = "AiProcessor"
    
    // Используем DeepSeek Flash (самая быстрая бесплатная модель)
    private const val MODEL_NAME = "deepseek/deepseek-v4-flash:free"
    private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun summarizeNews(newsText: String): String {
        val apiKey = BuildConfig.GEMINI_API_KEY // Ключ OpenRouter берем из той же переменной
        
        if (apiKey.isBlank()) {
            Log.e(TAG, "OpenRouter API Key is missing!")
            return newsText
        }

        val prompt = """
            Ты — корректор новостей. Твоя задача — очистить текст от мусора, СОХРАНЯЯ основной смысл и объем новости.
            
            ЧТО НУЖНО СДЕЛАТЬ:
            1. Удали только рекламу, ссылки (http/https), призывы подписаться ("подпишись", "наш канал") и контакты.
            2. Удали приветствия и авторские отступления.
            3. Удали спецсимволы и лишние эмодзи, которые мешают озвучке.
            4. Оставь основной текст новости почти без изменений, лишь немного подправив его для плавного чтения.
            5. Если в сообщении ТОЛЬКО реклама — верни пустую строку.
            
            Текст новости:
            $newsText
        """.trimIndent()

        // Формируем JSON для OpenRouter (формат Chat Completion)
        val json = JSONObject().apply {
            put("model", MODEL_NAME)
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            put("messages", messages)
            put("temperature", 0.5)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/p2petrovich/TelegramNewsReader") // Обязательно для OpenRouter
            .addHeader("X-Title", "TelegramNewsReader")
            .post(body)
            .build()

        return try {
            // Выполняем запрос в IO потоке (хотя suspend функция уже должна вызываться из IO)
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val jsonResponse = JSONObject(responseBody)
                val choices = jsonResponse.getJSONArray("choices")
                if (choices.length() > 0) {
                    val summarized = choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                    
                    if (summarized.isBlank()) {
                        Log.w(TAG, "OpenRouter returned empty text")
                        newsText
                    } else {
                        Log.d(TAG, "Summary success via OpenRouter!")
                        summarized
                    }
                } else {
                    newsText
                }
            } else {
                Log.e(TAG, "OpenRouter error: ${response.code} - ${response.message}")
                Log.e(TAG, "Response body: $responseBody")
                newsText
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error via OpenRouter: ${e.message}")
            newsText
        }
    }
}
