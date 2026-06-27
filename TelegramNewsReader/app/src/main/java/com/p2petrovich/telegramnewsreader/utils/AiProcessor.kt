package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import android.util.Log
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
    private const val GEMINI_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"

    private val client = HttpClients.shared

    private val testClient = HttpClients.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun providerConfig(context: Context): Triple<String, String, String> {
        val provider = PreferenceManager.getAiProvider(context)
        val model = PreferenceManager.getAiModel(context)
        return when (provider) {
            "groq" -> Triple(GROQ_URL, PreferenceManager.getGroqApiKey(context), model)
            "gemini" -> {
                val apiKey = PreferenceManager.getGeminiApiKey(context)
                val url = String.format(GEMINI_URL_TEMPLATE, model, apiKey)
                Triple(url, apiKey, model)
            }
            else   -> Triple(OPENROUTER_URL, PreferenceManager.getOpenRouterApiKey(context), model)
        }
    }

    /**
     * Проверяет доступность выбранной модели, отправляя пустой запрос.
     * Возвращает Pair(успех, сообщение)
     */
    suspend fun testModelAvailability(modelName: String, context: Context): Pair<Boolean, String> {
        val (apiUrl, apiKey, _) = providerConfig(context)
        if (apiKey.isBlank()) return false to context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_api_key_missing)

        val provider = PreferenceManager.getAiProvider(context)
        val json = if (provider == "gemini") {
            JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "ping"))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 5)
                })
            }
        } else {
            JSONObject().apply {
                put("model", modelName)
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "ping")
                    })
                }
                put("messages", messages)
                put("max_tokens", 5)
            }
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .post(body)

        if (provider != "gemini") {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        if (provider == "openrouter") {
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

        val promptResId = if (isRu) {
            when (style) {
                "minimal" -> com.p2petrovich.telegramnewsreader.R.string.ai_prompt_minimal_ru
                "extreme" -> com.p2petrovich.telegramnewsreader.R.string.ai_prompt_extreme_ru
                "balanced" -> com.p2petrovich.telegramnewsreader.R.string.ai_prompt_balanced_ru
                else -> com.p2petrovich.telegramnewsreader.R.string.ai_prompt_default_ru
            }
        } else {
            when (style) {
                "minimal" -> com.p2petrovich.telegramnewsreader.R.string.ai_prompt_minimal_en
                "extreme" -> com.p2petrovich.telegramnewsreader.R.string.ai_prompt_extreme_en
                "balanced" -> com.p2petrovich.telegramnewsreader.R.string.ai_prompt_balanced_en
                else -> com.p2petrovich.telegramnewsreader.R.string.ai_prompt_default_en
            }
        }

        val prompt = context.getString(promptResId, safeText)

        val temperature = when (style) {
            "minimal" -> 0.1   // чистка — нужна максимальная точность
            "extreme" -> 0.15  // одно предложение — строго, минимум переформулировок и смещения акцента
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

        val json = if (PreferenceManager.getAiProvider(context) == "gemini") {
            // Формат Google Gemini
            JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", temperature)
                    put("maxOutputTokens", maxOutputTokens)
                    // Отключаем "thinking" для моделей 2.0/2.5 Flash
                    if (modelName.contains("flash")) {
                        put("thinkingConfig", JSONObject().apply {
                            put("includeThoughts", false)
                            put("thinkingBudget", 0)
                        })
                    }
                })
            }
        } else {
            // Формат OpenAI (Groq / OpenRouter)
            JSONObject().apply {
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
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        var lastAttemptResponse: String? = null
        
        return withContext(Dispatchers.IO) {
            // Повторные попытки при 429 (Rate Limit) и сетевых сбоях
            for (attempt in 1..3) {
                try {
                    val body = json.toString().toRequestBody(mediaType)
                    val provider = PreferenceManager.getAiProvider(context)
                    
                    val requestBuilder = Request.Builder()
                        .url(apiUrl)
                        .post(body)

                    if (provider != "gemini") {
                        requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                    }

                    if (provider == "openrouter") {
                        requestBuilder
                            .addHeader("HTTP-Referer", "https://github.com/p2petrovich/TelegramNewsReader")
                            .addHeader("X-Title", "TelegramNewsReader")
                    }

                    val request = requestBuilder.build()
                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()

                    if (response.isSuccessful && responseBody != null) {
                        val jsonResponse = JSONObject(responseBody)
                        
                        val summarized = if (provider == "gemini") {
                            // Разбор ответа Gemini: candidates[0].content.parts[0].text
                            jsonResponse.getJSONArray("candidates")
                                .getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")
                                .getJSONObject(0)
                                .getString("text")
                                .trim()
                        } else {
                            // Разбор ответа OpenAI
                            val choices = jsonResponse.getJSONArray("choices")
                            if (choices.length() > 0) {
                                choices.getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")
                                    .trim()
                            } else null
                        }

                        if (summarized != null) {
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
