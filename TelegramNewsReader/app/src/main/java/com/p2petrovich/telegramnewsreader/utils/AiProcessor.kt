package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import android.util.Log
import com.p2petrovich.telegramnewsreader.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
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
    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

    private val client = HttpClients.shared

    private val testClient = HttpClients.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun providerConfig(context: Context): Triple<String, String, String> {
        val provider = PreferenceManager.getAiProvider(context)
        val model = PreferenceManager.getAiModel(context)
        return when (provider) {
            "groq" -> Triple(GROQ_URL, BuildConfig.GROQ_API_KEY, model)
            else -> Triple(OPENROUTER_URL, BuildConfig.OPENROUTER_API_KEY, model)
        }
    }

    /**
     * Проверяет доступность выбранной модели, отправляя пустой запрос.
     * Возвращает Pair(успех, сообщение)
     */
    suspend fun testModelAvailability(modelName: String, context: Context): Pair<Boolean, String> {
        val (apiUrl, apiKey, _) = providerConfig(context)
        if (apiKey.isBlank()) return false to context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_api_key_missing)

        val json = JSONObject().apply {
            put("model", modelName)
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "ping")
                })
            }
            put("messages", messages)
            put("max_tokens", 5) // Минимум токенов для проверки
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)

        if (PreferenceManager.getAiProvider(context) == "openrouter") {
            requestBuilder
                .addHeader("HTTP-Referer", "https://github.com/p2petrovich/TelegramNewsReader")
                .addHeader("X-Title", "TelegramNewsReader")
        }

        val request = requestBuilder.build()

        return try {
            val response = withContext(kotlinx.coroutines.Dispatchers.IO) {
                testClient.newCall(request).execute()
            }
            if (response.isSuccessful) {
                true to context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_model_available)
            } else {
                val errorMsg = response.body?.string() ?: response.message
                false to context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_error_format, response.code, errorMsg)
            }
        } catch (e: Exception) {
            false to context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_network_unavailable, e.message)
        }
    }

    private fun isRussian(text: String): Boolean {
        val cyrillicCount = text.count { it in '\u0400'..'\u04FF' }
        val latinCount = text.count { it.isLetter() && it !in '\u0400'..'\u04FF' }
        return cyrillicCount > latinCount
    }

    suspend fun summarizeNews(newsText: String, context: Context): String {
        val (apiUrl, apiKey, modelName) = providerConfig(context)

        if (apiKey.isBlank()) {
            Log.e(TAG, "AI API Key is missing for ${PreferenceManager.getAiProvider(context)}!")
            return "[AI Error: Key missing] $newsText"
        }

        if (newsText.isBlank()) return ""

        // Ограничение длины входного текста для избежания ошибок 400 (Bad Request / Filtered)
        val safeText = if (newsText.length > 8000) newsText.take(8000) + "..." else newsText

        val style = PreferenceManager.getAiStyle(context)
        val isRu = isRussian(safeText)

        val prompt = if (isRu) {
            when (style) {
                "minimal" -> """
                    Ты — корректор новостей. Твоя задача — очистить текст от мусора, НЕ СОКРАЩАЯ его.
    
                    Правила:
                    1. Удали рекламу, промо-блоки, призывы подписаться/поставить лайк/перейти по ссылке.
                    2. Удали ссылки, упоминания каналов (@channel), хэштеги, эмодзи-разделители и декоративные символы (───, ▪️, 🔥 и т.п.).
                    3. Удали подписи авторов, плашки источников и служебные пометки ("Подробнее →", "Читать далее" и подобные).
                    4. ВЕСЬ фактический текст новости сохрани дословно: факты, цифры, имена, цитаты, последовательность абзацев.
                    5. Не добавляй ничего от себя: ни вступлений, ни выводов, ни комментариев.
                    6. В ответе верни ТОЛЬКО очищенный текст новости, без пояснений. ВЕСЬ ТЕКСТ ДОЛЖЕН БЫТЬ НА РУССКОМ ЯЗЫКЕ.
    
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
                    5. В ответе верни ТОЛЬКО это одно предложение, без кавычек и пояснений. ОТВЕТ ДОЛЖЕН БЫТЬ НА РУССКОМ ЯЗЫКЕ.
    
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
                    6. В ответе верни ТОЛЬКО готовое саммари. ОТВЕТ ДОЛЖЕН БЫТЬ НА РУССКОМ ЯЗЫКЕ.
    
                    Текст:
                    $safeText
                """.trimIndent()
    
                else -> """
                    Ты — редактор новостного дайджеста. Сделай краткое САММАРИ новости.
    
                    Правила:
                    1. Сократи объём примерно в 2 раза.
                    2. Сохрани ключевые факты и цифры.
                    3. Удали рекламу и мусор.
                    4. В ответе верни ТОЛЬКО текст новости НА РУССКОМ ЯЗЫКЕ.
    
                    Текст:
                    $safeText
                """.trimIndent()
            }
        } else {
            when (style) {
                "minimal" -> """
                    You are a news proofreader. Your task is to clean the text from noise without shortening it.

                    Rules:
                    1. Remove ads, promo blocks, calls to subscribe/like/follow links.
                    2. Remove links, channel mentions (@channel), hashtags, emoji dividers, and decorative symbols.
                    3. Remove author signatures, source tags, and service notes ("Read more", "Details here", etc.).
                    4. Keep ALL factual news text verbatim: facts, figures, names, quotes, paragraph sequence.
                    5. Do not add anything of your own: no introductions, no conclusions, no comments.
                    6. Return ONLY the cleaned news text in your response, without explanations. THE RESPONSE MUST BE IN ENGLISH.

                    Text to process:
                    $safeText
                """.trimIndent()

                "extreme" -> """
                    You are an editor for an emergency news feed "Flash". Compress the news into ONE sentence.

                    Rules:
                    1. No more than 20 words. Try to keep it within 10-15 words.
                    2. Only the main fact: who/what, what happened.
                    3. No introductory phrases ("It is reported that...", "As it became known..."), no evaluations, no emojis.
                    4. Keep key figures and proper names if they are the essence of the news.
                    5. Use ONLY information present in the source text. Do NOT add or invent anything.
                    6. Return ONLY this one sentence in your response, without quotes or explanations. THE RESPONSE MUST BE IN ENGLISH.

                    Text:
                    $safeText
                """.trimIndent()

                "balanced" -> """
                    You are a news editor. Produce a faithful, concise version of the news below.

                    Rules:
                    1. Use ONLY information present in the source text. Do NOT add facts, context, background, or details that are not explicitly stated.
                    2. Do NOT invent or infer anything. If unsure, omit it.
                    3. Remove ads, links, fluff, and repetitions.
                    4. If the news is already short, return it almost unchanged — never expand it.
                    5. Neutral news style. The result must be equal to or shorter than the source, never longer.
                    6. Return ONLY the resulting text. THE RESPONSE MUST BE IN ENGLISH.

                    Text:
                    $safeText
                """.trimIndent()

                else -> """
                    You are a news editor. Produce a faithful, concise version of the news below.

                    Rules:
                    1. Use ONLY information present in the source text. Do NOT add or invent anything.
                    2. Keep key facts and figures.
                    3. Remove ads and noise.
                    4. Never make the text longer than the source.
                    5. Return ONLY the news text in your response in ENGLISH.

                    Text:
                    $safeText
                """.trimIndent()
            }
        }

        val temperature = when (style) {
            "minimal" -> 0.1   // чистка — нужна максимальная точность
            "extreme" -> 0.3   // одно предложение — строго, но можно чуть гибче
            "balanced" -> 0.1  // саммари — минимум "творчества", чтобы не досочинять
            else      -> 0.2
        }

        // Жёсткий потолок длины ответа: не даём модели раздувать короткие новости.
        // Грубая оценка: ~1 токен на 3-4 символа. Берём от длины входа.
        val maxOutputTokens = when (style) {
            "extreme"  -> 60
            "balanced" -> (safeText.length / 3).coerceIn(60, 800)
            "minimal"  -> (safeText.length / 2).coerceIn(100, 1200)
            else       -> (safeText.length / 3).coerceIn(60, 800)
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
            put("max_tokens", maxOutputTokens)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        var lastAttemptResponse: String? = null
        
        return withContext(Dispatchers.IO) {
            // Повторные попытки при 429 (Rate Limit) и сетевых сбоях
            for (attempt in 1..3) {
                try {
                    val body = json.toString().toRequestBody(mediaType)
                    val requestBuilder = Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .post(body)

                    if (PreferenceManager.getAiProvider(context) == "openrouter") {
                        requestBuilder
                            .addHeader("HTTP-Referer", "https://github.com/p2petrovich/TelegramNewsReader")
                            .addHeader("X-Title", "TelegramNewsReader")
                    }

                    val request = requestBuilder.build()
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

                            return@withContext if (summarized.isBlank()) safeText else summarized
                        } else return@withContext "[AI Empty Response] $safeText"
                    } else if (response.code == 429) {
                        Log.w(TAG, "Rate limit hit (429), attempt $attempt/3. Waiting...")
                        lastAttemptResponse = "[AI Error 429: Rate Limit] $safeText"
                        if (attempt < 3) {
                            delay(2000L * attempt) // Экспоненциальная задержка
                            continue
                        }
                    } else {
                        val provider = PreferenceManager.getAiProvider(context)
                        Log.e(TAG, "$provider error: ${response.code}")
                        return@withContext "[AI Error ${response.code}] $safeText"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Network error on attempt $attempt: ${e.message}")
                    lastAttemptResponse = "[AI Network Error] $safeText"
                    if (attempt < 3) {
                        delay(2000L * attempt)
                        continue
                    }
                    return@withContext lastAttemptResponse ?: safeText
                }
            }
            lastAttemptResponse ?: safeText
        }
    }

    /**
     * Удаляет технические префиксы вида [AI Error ...] или [AI Empty Response],
     * чтобы они не зачитывались вслух через TTS.
     */
    fun stripErrorPrefix(text: String): String {
        if (text.startsWith("[AI ")) {
            val closingBracketIndex = text.indexOf(']')
            if (closingBracketIndex != -1) {
                return text.substring(closingBracketIndex + 1).trim()
            }
        }
        return text
    }
}
