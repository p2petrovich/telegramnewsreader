package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
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
    private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun summarizeNews(newsText: String, context: Context): String {
        val apiKey = BuildConfig.GEMINI_API_KEY
        
        if (apiKey.isBlank()) {
            Log.e(TAG, "OpenRouter API Key is missing!")
            return newsText
        }

        val modelName = PreferenceManager.getAiModel(context)
        val style = PreferenceManager.getAiStyle(context)

        val prompt = when (style) {
            "minimal" -> """
                Ты — корректор новостей. Очисти текст от мусора, сохраняя основной объем.
                1. Удали только рекламу, ссылки, призывы подписаться.
                2. Оставь основной текст новости почти без изменений.
                3. Текст для обработки: $newsText
            """.trimIndent()
            
            "extreme" -> """
                Ты — редактор "радио-молния". Преврати новость в ОДНО емкое предложение (до 20 слов).
                1. Удали всё лишнее. Только суть.
                2. Текст для обработки: $newsText
            """.trimIndent()
            
            else -> """
                Ты — редактор новостного дайджеста. Сделай текст ЧИСТЫМ и СЖАТЫМ.
                1. Удали рекламу, ссылки и "воду".
                2. Сократи текст примерно в 2 раза, сохраняя факты и цифры.
                3. Текст для обработки: $newsText
            """.trimIndent()
        }

        val json = JSONObject().apply {
            put("model", modelName)
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            put("messages", messages)
            put("temperature", if (style == "extreme") 0.3 else 0.4)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/p2petrovich/TelegramNewsReader")
            .addHeader("X-Title", "TelegramNewsReader")
            .post(body)
            .build()

        return try {
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
                    
                    if (summarized.isBlank()) newsText else summarized
                } else newsText
            } else {
                Log.e(TAG, "OpenRouter error: ${response.code}")
                newsText
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}")
            newsText
        }
    }
}
