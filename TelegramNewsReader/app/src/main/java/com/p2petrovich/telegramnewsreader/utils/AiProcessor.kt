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
            return "[AI Error: Key missing] $newsText"
        }

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
                $newsText
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
                $newsText
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
                $newsText
            """.trimIndent()

            else -> """
                Ты — редактор новостного дайджеста. Сделай краткое САММАРИ новости.

                Правила:
                1. Сократи объём примерно в 2 раза.
                2. Сохрани ключевые факты и цифры.
                3. Удали рекламу и мусор.
                4. В ответе верни ТОЛЬКО текст новости.

                Текст:
                $newsText
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
                } else "[AI Empty Response] $newsText"
            } else {
                Log.e(TAG, "OpenRouter error: ${response.code}")
                "[AI Error ${response.code}] $newsText"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}")
            "[AI Network Error] $newsText"
        }
    }
}
