package com.p2petrovich.telegramnewsreader.utils

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import com.p2petrovich.telegramnewsreader.BuildConfig

object AiProcessor {
    private const val TAG = "AiProcessor"
    private const val MODEL_NAME = "gemini-1.5-flash"

    private val generativeModel by lazy {
        GenerativeModel(
            modelName = MODEL_NAME,
            apiKey = BuildConfig.GEMINI_API_KEY,
            generationConfig = generationConfig {
                temperature = 0.4f
                topK = 32
                topP = 1f
                maxOutputTokens = 1000
            }
        )
    }

    suspend fun summarizeNews(newsText: String): String {
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            Log.e(TAG, "Gemini API Key is missing!")
            return newsText
        }

        val prompt = """
            Ты помощник по подготовке ежедневного аудио-дайджеста новостей. 
            Твоя задача: взять текст новости из Telegram и сделать его максимально лаконичным, интересным и удобным для озвучки.
            
            Требования:
            1. Оставь только самую важную суть (факты, цифры, результаты).
            2. Удали мусор, приветствия, призывы подписаться, ссылки и авторские отступления.
            3. Текст должен звучать естественно в устной речи. 
            4. Если несколько новостей об одном и том же, объедини их.
            5. Итоговый текст должен быть на русском языке.
            6. Если в тексте нет полезной информации, верни пустую строку.
            
            Текст для обработки:
            $newsText
        """.trimIndent()

        return try {
            val response = generativeModel.generateContent(prompt)
            val summarized = response.text?.trim()
            if (summarized.isNullOrBlank()) {
                Log.w(TAG, "Gemini returned empty text, using original")
                newsText
            } else {
                Log.d(TAG, "Summary success! Original length: ${newsText.length}, New: ${summarized.length}")
                summarized
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini error: ${e.message}")
            newsText // В случае ошибки возвращаем оригинал, чтобы не ломать поток
        }
    }
}
