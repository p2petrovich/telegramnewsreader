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

    private const val MAX_INPUT_CHARS = 8000

    private val client = HttpClients.shared

    private val testClient = HttpClients.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** Результат саммаризации: позволяет вызывающему коду отличать успех от ошибки. */
    sealed interface SummaryResult {
        data class Success(val text: String) : SummaryResult
        data class Failure(val original: String, val reason: String) : SummaryResult
    }

    private fun providerConfig(context: Context): Triple<String, String, String> {
        val provider = PreferenceManager.getAiProvider(context)
        val model = PreferenceManager.getAiModel(context)
        return when (provider) {
            "groq" -> Triple(GROQ_URL, BuildConfig.GROQ_API_KEY, model)
            else -> Triple(OPENROUTER_URL, BuildConfig.OPENROUTER_API_KEY, model)
        }
    }

    private fun Request.Builder.addProviderHeaders(context: Context): Request.Builder {
        if (PreferenceManager.getAiProvider(context) == "openrouter") {
            addHeader("HTTP-Referer", "https://github.com/p2petrovich/TelegramNewsReader")
            addHeader("X-Title", "TelegramNewsReader")
        }
        return this
    }

    /**
     * Проверяет доступность выбранной модели, отправляя пустой запрос.
     * Возвращает Pair(успех, сообщение)
     */
    suspend fun testModelAvailability(modelName: String, context: Context): Pair<Boolean, String> {
        val (apiUrl, apiKey, _) = providerConfig(context)
        if (apiKey.isBlank()) {
            return false to context.getString(
                com.p2petrovich.telegramnewsreader.R.string.ai_api_key_missing
            )
        }

        val json = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "ping")
                })
            })
            put("max_tokens", 5) // Минимум токенов для проверки
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .addProviderHeaders(context)
            .build()

        return try {
            val response = withContext(Dispatchers.IO) {
                testClient.newCall(request).execute()
            }
            response.use {
                if (it.isSuccessful) {
                    true to context.getString(
                        com.p2petrovich.telegramnewsreader.R.string.ai_model_available
                    )
                } else {
                    val errorMsg = it.body?.string() ?: it.message
                    false to context.getString(
                        com.p2petrovich.telegramnewsreader.R.string.ai_error_format,
                        it.code,
                        errorMsg
                    )
                }
            }
        } catch (e: Exception) {
            false to context.getString(
                com.p2petrovich.telegramnewsreader.R.string.ai_network_unavailable,
                e.message
            )
        }
    }

    /**
     * Системный промпт строится без угадывания языка: модель сама отвечает
     * на языке исходного текста. Во всех стилях запрещено придумывать детали.
     */
    private fun buildSystemPrompt(style: String): String = when (style) {
        "minimal" -> """
            You are a news proofreader / корректор новостей.
            Your task is to clean the text from noise WITHOUT shortening it.

            Rules:
            1. Remove ads, promo blocks, calls to subscribe/like/follow links.
            2. Remove links, channel mentions (@channel), hashtags, emoji dividers and decorative symbols (───, ▪️, 🔥, etc.).
            3. Remove author signatures, source tags and service notes ("Read more →", "Подробнее →", etc.).
            4. Keep ALL factual news text VERBATIM: facts, figures, names, quotes, paragraph order.
            5. Do NOT add anything of your own: no introductions, no conclusions, no comments, no invented details.
            6. Use ONLY information present in the source text. Never add facts, context or background that is not explicitly stated.
            7. Return ONLY the cleaned news text, without explanations.
            8. CRITICAL: Always reply in the SAME LANGUAGE as the source text (Russian source → Russian answer, English source → English answer).
        """.trimIndent()

        "extreme" -> """
            You are an editor for an emergency news feed "Flash" / "Молния".
            Compress the news into ONE sentence.

            Rules:
            1. No more than 20 words. Try to keep it within 10–15 words.
            2. Only the main fact: who/what, what happened.
            3. No introductory phrases ("It is reported that…", "Сообщается, что…"), no evaluations, no emojis.
            4. Keep key figures and proper names if they are the essence of the news.
            5. Use ONLY information present in the source text. Do NOT add, infer or invent anything.
            6. Return ONLY this one sentence, without quotes or explanations.
            7. CRITICAL: Always reply in the SAME LANGUAGE as the source text (Russian source → Russian answer, English source → English answer).
        """.trimIndent()

        "balanced" -> """
            You are a news editor / редактор дайджеста.
            Produce a faithful, concise summary of the news.

            Rules:
            1. Use ONLY information present in the source text. Do NOT add facts, context, background or details that are not explicitly stated.
            2. Do NOT invent or infer anything. If unsure, omit it.
            3. Keep ALL key facts, figures, names and dates.
            4. Remove ads, links, fluff and repetitions.
            5. Aim to roughly halve the length. If the news is already short, return it almost unchanged — never expand it.
            6. Neutral news style, short sentences. The result must be equal to or shorter than the source, never longer. No personal opinions or conclusions.
            7. Return ONLY the resulting text.
            8. CRITICAL: Always reply in the SAME LANGUAGE as the source text (Russian source → Russian answer, English source → English answer).
        """.trimIndent()

        else -> """
            You are a news editor / редактор дайджеста.
            Produce a faithful, concise version of the news.

            Rules:
            1. Use ONLY information present in the source text. Do NOT add or invent anything.
            2. Keep key facts and figures.
            3. Remove ads and noise.
            4. Never make the text longer than the source.
            5. Return ONLY the news text.
            6. CRITICAL: Always reply in the SAME LANGUAGE as the source text (Russian source → Russian answer, English source → English answer).
        """.trimIndent()
    }

    /**
     * Основной метод: возвращает типизированный результат.
     */
    suspend fun summarizeNewsResult(newsText: String, context: Context): SummaryResult {
        val (apiUrl, apiKey, modelName) = providerConfig(context)

        if (apiKey.isBlank()) {
            Log.e(TAG, "AI API Key is missing for ${PreferenceManager.getAiProvider(context)}!")
            return SummaryResult.Failure(newsText, "Key missing")
        }

        if (newsText.isBlank()) return SummaryResult.Success("")

        // Ограничение длины входа для избежания 400 (Bad Request / Filtered).
        val safeText = if (newsText.length > MAX_INPUT_CHARS) {
            Log.w(TAG, "Input truncated from ${newsText.length} to $MAX_INPUT_CHARS chars")
            newsText.take(MAX_INPUT_CHARS) + "..."
        } else {
            newsText
        }

        val style = PreferenceManager.getAiStyle(context)
        val systemPrompt = buildSystemPrompt(style)

        val temperature = when (style) {
            "minimal" -> 0.1   // чистка — нужна максимальная точность
            "extreme" -> 0.3   // одно предложение — строго, но можно чуть гибче
            "balanced" -> 0.1  // саммари — минимум «творчества»
            else -> 0.2
        }

        // Жёсткий потолок длины ответа: не даём раздувать короткие новости.
        // Грубо: ~1 токен на 3–4 символа.
        val maxOutputTokens = when (style) {
            "extreme" -> 60
            "balanced" -> (safeText.length / 3).coerceIn(60, 800)
            "minimal" -> (safeText.length / 2).coerceIn(100, 1200)
            else -> (safeText.length / 3).coerceIn(60, 800)
        }

        val json = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().apply {
                // Правила — отдельно от данных: лучше следование + защита от инъекций.
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", safeText)
                })
            })
            put("temperature", temperature)
            put("max_tokens", maxOutputTokens)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val bodyString = json.toString()
        var lastFailure: SummaryResult.Failure? = null

        return withContext(Dispatchers.IO) {
            // Повторные попытки при 429 (Rate Limit) и сетевых сбоях.
            for (attempt in 1..3) {
                try {
                    val body = bodyString.toRequestBody(mediaType)
                    val request = Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .post(body)
                        .addProviderHeaders(context)
                        .build()

                    client.newCall(request).execute().use { response ->
                        val responseBody = response.body?.string()

                        when {
                            response.isSuccessful && responseBody != null -> {
                                val choices = JSONObject(responseBody).optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val summarized = choices.getJSONObject(0)
                                        .getJSONObject("message")
                                        .getString("content")
                                        .trim()
                                    return@withContext if (summarized.isBlank()) {
                                        SummaryResult.Success(safeText)
                                    } else {
                                        SummaryResult.Success(summarized)
                                    }
                                } else {
                                    return@withContext SummaryResult.Failure(safeText, "Empty Response")
                                }
                            }

                            response.code == 429 -> {
                                Log.w(TAG, "Rate limit hit (429), attempt $attempt/3. Waiting...")
                                lastFailure = SummaryResult.Failure(safeText, "Error 429: Rate Limit")
                                if (attempt < 3) {
                                    delay(2000L * attempt) // нарастающая задержка
                                }
                            }

                            else -> {
                                val provider = PreferenceManager.getAiProvider(context)
                                Log.e(TAG, "$provider error: ${response.code}")
                                return@withContext SummaryResult.Failure(
                                    safeText,
                                    "Error ${response.code}"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Network error on attempt $attempt: ${e.message}")
                    lastFailure = SummaryResult.Failure(safeText, "Network Error")
                    if (attempt < 3) {
                        delay(2000L * attempt)
                    }
                }
            }
            lastFailure ?: SummaryResult.Failure(safeText, "Unknown")
        }
    }

    /**
     * Обратная совместимость со старым строковым API.
     * При ошибке возвращает текст с техническим префиксом, как раньше.
     */
    suspend fun summarizeNews(newsText: String, context: Context): String {
        return when (val result = summarizeNewsResult(newsText, context)) {
            is SummaryResult.Success -> result.text
            is SummaryResult.Failure -> "[AI ${result.reason}] ${result.original}"
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
