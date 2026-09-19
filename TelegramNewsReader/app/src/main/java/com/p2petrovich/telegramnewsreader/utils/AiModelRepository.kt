package com.p2petrovich.telegramnewsreader.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CachedModel(val id: String, val label: String)

sealed class ModelLoadResult {
    data class Success(
        val models: List<CachedModel>,
        val fromCache: Boolean
    ) : ModelLoadResult()

    data class Error(
        val cachedModels: List<CachedModel>,
        val message: String,
        val isMissingKey: Boolean = false
    ) : ModelLoadResult()
}

class AiModelRepository(context: Context) {

    private val appContext = context.applicationContext

    private val client = HttpClients.shared.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("ai_models_cache", Context.MODE_PRIVATE)

    suspend fun getOpenRouterFreeModels(forceRefresh: Boolean = false): ModelLoadResult =
        load("openrouter_models", apiKey = null, requiresKey = false, forceRefresh = forceRefresh) {
            fetchOpenRouter()
        }

    suspend fun getGroqModels(forceRefresh: Boolean = false): ModelLoadResult {
        val apiKey = PreferenceManager.getGroqApiKey(appContext)
        return load("groq_models", apiKey, requiresKey = true, forceRefresh = forceRefresh) {
            fetchGroq(apiKey)
        }
    }

    suspend fun getGeminiModels(forceRefresh: Boolean = false): ModelLoadResult {
        val apiKey = PreferenceManager.getGeminiApiKey(appContext)
        return load("gemini_models", apiKey, requiresKey = true, forceRefresh = forceRefresh) {
            fetchGemini(apiKey)
        }
    }

    private suspend fun load(
        cacheKey: String,
        apiKey: String?,
        requiresKey: Boolean,
        forceRefresh: Boolean,
        fetch: suspend () -> List<CachedModel>
    ): ModelLoadResult {
        if (requiresKey && apiKey.isNullOrBlank()) {
            return ModelLoadResult.Error(emptyList(), "API key is missing", isMissingKey = true)
        }
        val fingerprint = apiKey?.let { sha256(it) }.orEmpty()

        val entry = readCache(cacheKey, fingerprint)
        val baseCached = entry?.models.orEmpty().filter { item ->
            UNSUPPORTED_MODEL_TOKENS.none { token ->
                item.id.contains(token, ignoreCase = true) || item.label.contains(token, ignoreCase = true)
            }
        }
        val filteredCached = when (cacheKey) {
            "openrouter_models" -> baseCached.filter { item -> PREFERRED_OPENROUTER_MODELS.any { pref -> item.id.contains(pref, ignoreCase = true) } }
            "groq_models"       -> baseCached.filter { item -> PREFERRED_GROQ_MODELS.any { pref -> item.id.contains(pref, ignoreCase = true) } }
            "gemini_models"     -> baseCached.filter { item -> PREFERRED_GEMINI_MODELS.any { pref -> item.id.contains(pref, ignoreCase = true) } }
            else                -> baseCached
        }
        val cachedModels = if (filteredCached.isNotEmpty()) filteredCached else baseCached.take(5)

        val age = entry?.let { System.currentTimeMillis() - it.timestamp }
        val fresh = cachedModels.isNotEmpty() && age != null && age in 0 until CACHE_TTL_MS

        if (!forceRefresh && fresh) {
            return ModelLoadResult.Success(cachedModels, fromCache = true)
        }

        return try {
            val models = fetch()
            if (models.isEmpty()) throw IOException("Provider returned an empty model list")
            writeCache(cacheKey, fingerprint, models)
            ModelLoadResult.Success(models, fromCache = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logx.e(TAG, "Failed to load models: $cacheKey", e)
            ModelLoadResult.Error(cachedModels, e.message ?: "Network error")
        }
    }

    private suspend fun fetchOpenRouter(): List<CachedModel> {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .build()
        val parsed = execute(request, OpenRouterListResponse::class.java)
        val apiModels = parsed.data.orEmpty()
            .filter { item ->
                (item.id.endsWith(":free") ||
                    (item.pricing?.prompt == "0" && item.pricing?.completion == "0")) &&
                    UNSUPPORTED_MODEL_TOKENS.none { token ->
                        item.id.contains(token, ignoreCase = true)
                    }
            }
            .map { CachedModel(it.id, it.name ?: it.id) }

        val preferred = apiModels.filter { item ->
            PREFERRED_OPENROUTER_MODELS.any { pref -> item.id.contains(pref, ignoreCase = true) }
        }
        return (preferred.ifEmpty { apiModels }).take(5).sortedBy { it.label }
    }

    private suspend fun fetchGroq(apiKey: String): List<CachedModel> {
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        val parsed = execute(request, GroqListResponse::class.java)
        val apiModels = parsed.data.orEmpty()
            .filter { item ->
                item.active != false &&
                    UNSUPPORTED_MODEL_TOKENS.none { token ->
                        item.id.contains(token, ignoreCase = true)
                    }
            }
            .map { CachedModel(it.id, it.id) }

        val preferred = apiModels.filter { item ->
            PREFERRED_GROQ_MODELS.any { pref -> item.id.contains(pref, ignoreCase = true) }
        }
        return (preferred.ifEmpty { apiModels }).take(5).sortedBy { it.label }
    }

    private suspend fun fetchGemini(apiKey: String): List<CachedModel> {
        val result = mutableListOf<CachedModel>()
        var pageToken: String? = null
        var pages = 0
        do {
            val url = "https://generativelanguage.googleapis.com/v1beta/models".toHttpUrl()
                .newBuilder()
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("key", apiKey)
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val request = Request.Builder()
                .url(url)
                .build()

            val page = execute(request, GeminiListResponse::class.java)
            page.models.orEmpty()
                .filter { item ->
                    item.supportedGenerationMethods?.contains("generateContent") == true &&
                        UNSUPPORTED_MODEL_TOKENS.none { token ->
                            item.name.contains(token, ignoreCase = true)
                        }
                }
                .mapTo(result) {
                    val cleanId = it.name.removePrefix("models/")
                    CachedModel(cleanId, it.displayName ?: cleanId)
                }
            pageToken = page.nextPageToken
        } while (!pageToken.isNullOrEmpty() && ++pages < MAX_GEMINI_PAGES)

        val preferred = result.filter { item ->
            PREFERRED_GEMINI_MODELS.any { pref -> item.id.contains(pref, ignoreCase = true) }
        }
        return if (preferred.isNotEmpty()) preferred.sortedBy { it.label } else result.take(5).sortedBy { it.label }
    }

    private suspend fun <T> execute(request: Request, type: Class<T>): T =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        try {
                            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                            val body = r.body?.string() ?: throw IOException("Empty response body")
                            val parsed = gson.fromJson(body, type)
                                ?: throw IOException("Unparseable response")
                            if (cont.isActive) cont.resume(parsed)
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                }
            })
        }

    private fun writeCache(key: String, fingerprint: String, models: List<CachedModel>) {
        val entry = CacheEntry(System.currentTimeMillis(), fingerprint, models)
        prefs.edit().putString(key, gson.toJson(entry)).apply()
    }

    private fun readCache(key: String, fingerprint: String): CacheEntry? {
        val json = prefs.getString(key, null) ?: return null
        val entry = try {
            gson.fromJson(json, CacheEntry::class.java)
        } catch (e: Exception) {
            null
        } ?: return null
        if (entry.keyFingerprint != fingerprint || entry.models.isNullOrEmpty()) return null
        return entry
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private data class CacheEntry(
        val timestamp: Long,
        val keyFingerprint: String,
        val models: List<CachedModel>?
    )

    private data class OpenRouterListResponse(val data: List<OpenRouterItem>?)
    private data class OpenRouterItem(
        val id: String,
        val name: String?,
        val pricing: OpenRouterPricing?
    )
    private data class OpenRouterPricing(val prompt: String?, val completion: String?)

    private data class GroqListResponse(val data: List<GroqItem>?)
    private data class GroqItem(val id: String, val active: Boolean?)

    private data class GeminiListResponse(
        val models: List<GeminiItem>?,
        val nextPageToken: String?
    )
    private data class GeminiItem(
        val name: String,
        val displayName: String?,
        val supportedGenerationMethods: List<String>?
    )

    private companion object {
        const val TAG = "AiModelRepository"
        const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        const val MAX_GEMINI_PAGES = 10
        val UNSUPPORTED_MODEL_TOKENS = listOf(
            "whisper", "orpheus", "tts", "guard", "safeguard",
            "embed", "image", "flux", "diffusion", "moderation", "banana", "nano"
        )

        val PREFERRED_OPENROUTER_MODELS = listOf(
            "google/gemma-2-9b-it:free",
            "meta-llama/llama-3.1-8b-instruct:free",
            "qwen/qwen-2.5-7b-instruct:free",
            "mistralai/mistral-7b-instruct:free",
            "nvidia/nemotron-3-super-120b-a12b:free"
        )

        val PREFERRED_GROQ_MODELS = listOf(
            "llama-3.3-70b",
            "llama-3.1-8b",
            "gemma2-9b",
            "mixtral-8x7b"
        )

        val PREFERRED_GEMINI_MODELS = listOf(
            "gemini-2.0-flash",
            "gemini-1.5-pro",
            "gemini-1.5-flash",
            "gemini-1.0-pro",
            "gemini-2.0-flash-exp"
        )
    }
}
