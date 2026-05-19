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
    
    // Используем бесплатную модель Gemini через OpenRouter
    private const val MODEL_NAME = "google/gemini-flash-1.5-free"
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
            Ты помощник по подготовке ежедневного аудио-дайджеста новостей. 
            Твоя задача: взять текст новости из Telegram и сделать его максимально лаконичным, интересным и удобным для озвучки.
            
            Требования:
            1. Оставь только самую важную суть (факты, цифры, результаты).
            2. Удали мусор, приветствия, призывы подписаться, ссылки и авторские отступления.
            3. Текст должен звучать естественно в устной речи. 
            4. Если в тексте нет полезной информации, верни пустую строку.
            5. Итоговый текст должен быть на русском языке.
            
            Текст для обработки:
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
            put("temperature", 0.4)
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
