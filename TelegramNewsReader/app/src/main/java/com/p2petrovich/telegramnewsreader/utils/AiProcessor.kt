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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

object AiProcessor {
    private const val TAG = "AiProcessor"
    private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Проверяет доступность выбранной модели, отправляя пустой запрос.
     * Возвращает Pair(успех, сообщение)
     */
    suspend fun testModelAvailability(modelName: String, context: Context): Pair<Boolean, String> {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) return false to "API ключ отсутствует"

        val json = JSONObject().apply {
            put("model", modelName)
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "ping")
                })
            }
            put("messages", messages)
            put("max_tokens", 1) // Минимум токенов для проверки
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        return try {
            val response = withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (response.isSuccessful) {
                true to "Модель доступна"
            } else {
                false to "Ошибка ${response.code}: ${response.message}"
            }
        } catch (e: Exception) {
            false to "Сеть недоступна: ${e.message}"
        }
    }

    suspend fun summarizeNews(newsText: String, context: Context): String {
        val apiKey = BuildConfig.GEMINI_API_KEY

        if (apiKey.isBlank()) {
            Log.e(TAG, "OpenRouter API Key is missing!")
            return "[AI Error: Key missing] $newsText"
        }

        if (newsText.isBlank()) return ""
        
        // Ограничение длины входного текста для избежания ошибок 400 (Bad Request / Filtered)
        val safeText = if (newsText.length > 8000) newsText.take(8000) + "..." else newsText

        val modelName = PreferenceManager.getAiModel(context)
        val style = PreferenceManager.getAiStyle(context)



        val prompt = when (style) {
            "minimal" -> """
                Ты — корректор новостей. Твоя задача — очистить текст от мусора, НЕ СОКРАЩАЯ его.

                Правила:
                1. Удали рекламу, промо-блоки, призывы подписаться/поставить лайк/перейти по ссылке.
                2. Удали ссылки, упоминания каналов (@channel), хэштеги, эмодзи-разделители и декоративные символы (───, ▪️, 🔥 и т.п.).
                3. Удали подписи авторов, плашки источников и служебные пометки ("Подробнее →", "Читать далее" и подобные).
                4. ВЕСЬ фактический текст новости сохрани дословно: факты, цифры, имена, цитаты, последовательность абзацев.
                5. Не добавляй ничего от себя: ни вступлений, ни выводов, ни комментариев.
                6. В ответе верни ТОЛЬКО очищенный текст новости, без пояснений.

                Текст для обработки:
                $safeText
            """.trimIndent()

            "extreme" -> """
                Ты — редактор экстренной новостной ленты "Молния". Сожми новость до ОДНОГО предложения.

                Правила:
                1. Не более 20 слов. Постарайся уложиться в 10-15 слов.
                2. Только главный факт: кто/что, что произошло.
                3. Без вводных ("Сообщается, что…", "Как стало известно…"), без оценок, без эмодзи.
                4. Сохрани ключевые цифры и имена собственные, если они и есть суть новости.
                5. В ответе верни ТОЛЬКО это одно предложение, без кавычек и пояснений.

                Текст:
                $safeText
            """.trimIndent()

            "balanced" -> """
                Ты — редактор новостного дайджеста. Сделай краткое САММАРИ новости.

                Правила:
                1. Сократи объём примерно в 2 раза относительно исходника.
                2. Сохрани ВСЕ ключевые факты, цифры, имена, даты.
                3. Удали рекламу, ссылки, "воду" и повторы.
                4. Пиши нейтральным новостным стилем, короткими предложениями. Можно 2 абзаца.
                5. Не добавляй своих оценок и выводов.
                6. В ответе верни ТОЛЬКО готовое саммари.

                Текст:
                $safeText
            """.trimIndent()

            else -> """
                Ты — редактор новостного дайджеста. Сделай краткое САММАРИ новости.

                Правила:
                1. Сократи объём примерно в 2 раза.
                2. Сохрани ключевые факты и цифры.
                3. Удали рекламу и мусор.
                4. В ответе верни ТОЛЬКО текст новости.

                Текст:
                $safeText
            """.trimIndent()
        }

        val temperature = when (style) {
            "minimal" -> 0.1   // чистка — нужна максимальная точность
            "extreme" -> 0.3   // одно предложение — строго, но можно чуть гибче
            "balanced" -> 0.3  // саммари — баланс между точностью и сжатием
            else      -> 0.4
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
            put("temperature", temperature)
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

        var lastAttemptResponse: String? = null
        
        // Повторные попытки при 429 (Rate Limit)
        for (attempt in 1..3) {
            try {
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

                        return if (summarized.isBlank()) safeText else summarized
                    } else return "[AI Empty Response] $safeText"
                } else if (response.code == 429) {
                    Log.w(TAG, "Rate limit hit (429), attempt $attempt/3. Waiting...")
                    lastAttemptResponse = "[AI Error 429: Rate Limit] $safeText"
                    delay(2000L * attempt) // Экспоненциальная задержка
                    continue
                } else {
                    Log.e(TAG, "OpenRouter error: ${response.code}")
                    return "[AI Error ${response.code}] $safeText"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error: ${e.message}")
                return "[AI Network Error] $safeText"
            }
        }
        
        return lastAttemptResponse ?: safeText
    }
}
