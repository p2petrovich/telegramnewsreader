package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

object AiProcessor {
    private const val TAG = "AiProcessor"
    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val GEMINI_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"

    private val gson = Gson()
    private val client = HttpClients.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    // --- Data Models for JSON ---

    private data class OpenAIRequest(
        val model: String,
        val messages: List<Message>,
        @SerializedName("max_tokens") val maxTokens: Int,
        val temperature: Float
    ) {
        data class Message(val role: String, val content: String)
    }

    private data class GeminiRequest(
        val contents: List<Content>,
        val generationConfig: GenerationConfig
    ) {
        data class Content(val parts: List<Part>)
        data class Part(val text: String)
        data class GenerationConfig(
            val temperature: Float,
            val maxOutputTokens: Int,
            val thinkingConfig: ThinkingConfig? = null
        )
        data class ThinkingConfig(val includeThoughts: Boolean, val thinkingBudget: Int)
    }

    private data class OpenAIResponse(val choices: List<Choice>?) {
        data class Choice(val message: Message?)
        data class Message(val content: String?)
    }

    private data class GeminiResponse(val candidates: List<Candidate>?) {
        data class Candidate(val content: Content?)
        data class Content(val parts: List<Part>?)
        data class Part(val text: String?)
    }

    // --- Implementation ---

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

    suspend fun testModelAvailability(modelName: String, context: Context): Pair<Boolean, String> {
        val provider = PreferenceManager.getAiProvider(context)
        val apiKey = when (provider) {
            "groq" -> PreferenceManager.getGroqApiKey(context)
            "gemini" -> PreferenceManager.getGeminiApiKey(context)
            else -> PreferenceManager.getOpenRouterApiKey(context)
        }
        
        if (apiKey.isBlank()) {
            Logx.w(TAG, "testModelAvailability: API key missing for $provider")
            return false to context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_api_key_missing)
        }

        val apiUrl = if (provider == "gemini") {
            String.format(GEMINI_URL_TEMPLATE, modelName, apiKey)
        } else if (provider == "groq") {
            GROQ_URL
        } else {
            OPENROUTER_URL
        }

        val requestBodyString = if (provider == "gemini") {
            val req = GeminiRequest(
                contents = listOf(GeminiRequest.Content(listOf(GeminiRequest.Part("ping")))),
                generationConfig = GeminiRequest.GenerationConfig(0.1f, 5)
            )
            gson.toJson(req)
        } else {
            val req = OpenAIRequest(
                model = modelName,
                messages = listOf(OpenAIRequest.Message("user", "ping")),
                maxTokens = 5,
                temperature = 0.1f
            )
            gson.toJson(req)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .post(requestBodyString.toRequestBody(mediaType))

        if (provider != "gemini") {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        if (provider == "openrouter") {
            requestBuilder
                .addHeader("HTTP-Referer", "https://github.com/p2petrovich/TelegramNewsReader")
                .addHeader("X-Title", "TelegramNewsReader")
        }

        return try {
            val response = withContext(Dispatchers.IO) {
                client.newCall(requestBuilder.build()).execute()
            }
            if (response.isSuccessful) {
                true to context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_model_available)
            } else if (response.code == 429) {
                false to context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_error_429)
            } else {
                val errorBody = response.body?.string() ?: response.message
                Logx.e(TAG, "testModelAvailability: provider=$provider code=${response.code} error=$errorBody")
                false to context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_error_format, response.code, "Check Logcat")
            }
        } catch (e: Exception) {
            Logx.e(TAG, "testModelAvailability: network error", e)
            false to context.getString(com.p2petrovich.telegramnewsreader.R.string.ai_network_unavailable, e.message)
        }
    }

    suspend fun summarizeNews(newsText: String, context: Context): String {
        val (apiUrl, apiKey, modelName) = providerConfig(context)
        val provider = PreferenceManager.getAiProvider(context)

        if (apiKey.isBlank()) {
            Logx.e(TAG, "summarizeNews: AI API Key is missing for $provider!")
            return "[AI Error: Key missing] $newsText"
        }

        if (newsText.isBlank()) return ""

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
            "minimal" -> 0.1f
            "extreme" -> 0.15f
            "balanced" -> 0.1f
            else      -> 0.2f
        }

        val maxOutputTokens = when (style) {
            "extreme"  -> 60
            "balanced" -> (safeText.length / 3).coerceIn(60, 800)
            "minimal"  -> (safeText.length / 2).coerceIn(100, 1200)
            else       -> (safeText.length / 3).coerceIn(60, 800)
        }

        val requestBodyString = if (provider == "gemini") {
            val req = GeminiRequest(
                contents = listOf(GeminiRequest.Content(listOf(GeminiRequest.Part(prompt)))),
                generationConfig = GeminiRequest.GenerationConfig(
                    temperature = temperature,
                    maxOutputTokens = maxOutputTokens,
                    thinkingConfig = GeminiRequest.ThinkingConfig(false, 0)
                )
            )
            gson.toJson(req)
        } else {
            val req = OpenAIRequest(
                model = modelName,
                messages = listOf(OpenAIRequest.Message("user", prompt)),
                maxTokens = maxOutputTokens,
                temperature = temperature
            )
            gson.toJson(req)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        var lastAttemptResponse: String? = null
        
        return withContext(Dispatchers.IO) {
            for (attempt in 1..3) {
                try {
                    val requestBuilder = Request.Builder()
                        .url(apiUrl)
                        .post(requestBodyString.toRequestBody(mediaType))

                    if (provider != "gemini") {
                        requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                    }

                    if (provider == "openrouter") {
                        requestBuilder
                            .addHeader("HTTP-Referer", "https://github.com/p2petrovich/TelegramNewsReader")
                            .addHeader("X-Title", "TelegramNewsReader")
                    }

                    val response = client.newCall(requestBuilder.build()).execute()
                    val responseBody = response.body?.string()

                    if (response.isSuccessful && responseBody != null) {
                        val summarized = if (provider == "gemini") {
                            val geminiResp = gson.fromJson(responseBody, GeminiResponse::class.java)
                            geminiResp.candidates?.getOrNull(0)?.content?.parts?.getOrNull(0)?.text?.trim()
                        } else {
                            val openAiResp = gson.fromJson(responseBody, OpenAIResponse::class.java)
                            openAiResp.choices?.getOrNull(0)?.message?.content?.trim()
                        }

                        if (summarized != null) {
                            return@withContext if (summarized.isBlank()) safeText else summarized
                        } else {
                            Logx.e(TAG, "AI summarized text is null. Provider: $provider. Masked Key: ${Logx.mask(apiKey)}")
                            return@withContext "[AI Error: Bad Response] $safeText"
                        }
                    } else if (response.code == 429) {
                        Logx.w(TAG, "Rate limit (429) for $provider. Attempt $attempt/3. Masked Key: ${Logx.mask(apiKey)}")
                        lastAttemptResponse = "[AI Error 429] $safeText"
                        if (attempt < 3) {
                            delay(2000L * attempt)
                            continue
                        }
                    } else {
                        Logx.e(TAG, "summarizeNews: $provider Error ${response.code}. Masked Key: ${Logx.mask(apiKey)}")
                        return@withContext "[AI Error ${response.code}] $safeText"
                    }
                } catch (e: Exception) {
                    Logx.e(TAG, "summarizeNews: Network error for $provider. Attempt $attempt. Masked Key: ${Logx.mask(apiKey)}", e)
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

    fun stripErrorPrefix(text: String): String {
        if (text.startsWith("[AI ")) {
            val closingBracketIndex = text.indexOf(']')
            if (closingBracketIndex != -1) {
                return text.substring(closingBracketIndex + 1).trim()
            }
        }
        return text
    }

    private fun isRussian(text: String): Boolean {
        val cyrillicCount = text.count { it in '\u0400'..'\u04FF' }
        val latinCount = text.count { it.isLetter() && it !in '\u0400'..'\u04FF' }
        return cyrillicCount > latinCount
    }
}
