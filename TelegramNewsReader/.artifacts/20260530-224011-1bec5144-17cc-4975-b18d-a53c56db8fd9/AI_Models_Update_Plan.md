# План обновления AI-моделей — TelegramNewsReader

> Дата: май 2026  
> Файлы: `AiProcessor.kt`, `PreferenceManager.kt`, `MainActivity.kt`, `build.gradle`

---

## Содержание

1. [Что сломано прямо сейчас](#1-что-сломано-прямо-сейчас)
2. [Обновление списка моделей OpenRouter](#2-обновление-списка-моделей-openrouter)
3. [Добавление Groq как второго провайдера](#3-добавление-groq-как-второго-провайдера)
4. [Изменения в коде](#4-изменения-в-коде)
5. [Итоговые списки моделей](#5-итоговые-списки-моделей)

---

## 1. Что сломано прямо сейчас

### Неверный ID модели Gemini

```kotlin
// Текущий — сломан (устаревший формат без :free)
"google/gemini-flash-1.5-free" to "Gemini 1.5 Flash (Google) — FREE"

// Исправить на:
"google/gemini-2.0-flash-exp:free" to "Gemini 2.0 Flash (Google) — FREE"
```

**Действие:** найти и заменить в `MainActivity.kt` строку 1557.

---

### Устаревшая модель Nemotron

```kotlin
// Текущий — устарел
"nvidia/nemotron-3-super-120b-a12b:free"

// Удалить из списка — модель снята с бесплатного доступа
```

---

### Слабая модель GLM

```kotlin
// Текущий — плохой русский язык
"z-ai/glm-4.5-air:free"

// Удалить из списка
```

---

## 2. Обновление списка моделей OpenRouter

### Что убрать

| Model ID | Причина |
|---|---|
| `z-ai/glm-4.5-air:free` | Слабый русский, малоизвестная модель |
| `nvidia/nemotron-3-super-120b-a12b:free` | Устарела |
| `google/gemini-flash-1.5-free` | Неверный ID — сломано |

### Что добавить

| Model ID | Название | Почему |
|---|---|---|
| `qwen/qwen3-235b-a22b:free` | Qwen3 235B | Лучший русский среди free-моделей на OpenRouter |
| `deepseek/deepseek-r1:free` | DeepSeek R1 | Reasoning-модель, лучшее качество саммари |
| `google/gemini-2.0-flash-exp:free` | Gemini 2.0 Flash | Исправленный ID взамен сломанного |
| `meta-llama/llama-4-scout:free` | Llama 4 Scout | Новая, лёгкая, быстрая — для minimal режима |

### Итоговый список OpenRouter

```kotlin
// MainActivity.kt — заменить блок с моделями целиком
val aiModels = linkedMapOf(
    "deepseek/deepseek-v4-flash:free"        to "DeepSeek V4 Flash — быстрый (дефолт) — FREE",
    "qwen/qwen3-235b-a22b:free"              to "Qwen3 235B — лучший русский — FREE",
    "openai/gpt-oss-120b:free"               to "GPT-OSS 120B — высокое качество — FREE",
    "deepseek/deepseek-r1:free"              to "DeepSeek R1 — reasoning, balanced — FREE",
    "google/gemini-2.0-flash-exp:free"       to "Gemini 2.0 Flash (Google) — FREE",
    "meta-llama/llama-4-scout:free"          to "Llama 4 Scout — лёгкий, minimal — FREE",
    "meta-llama/llama-3.3-70b-instruct:free" to "Llama 3.3 70B — проверенный — FREE"
)
```

---

## 3. Добавление Groq как второго провайдера

### Зачем

OpenRouter free: ~80–150 токенов/сек, 200 запросов/день.  
Groq free: ~300–400 токенов/сек, 1000 запросов/день, без карты.  

Для 30 новостей в режиме AI-сжатия: OpenRouter ≈ 5 мин, Groq ≈ 1–2 мин.  
API полностью совместим с OpenAI-форматом — замена только base URL и ключа.

### Лимиты Groq free tier

| Параметр | Значение |
|---|---|
| Запросов/мин (RPM) | 30 |
| Токенов/мин (TPM) | 6 000 |
| Запросов/день (RPD) | 1 000 (большие модели) / 14 400 (8B) |
| Карта | Не требуется |
| Регистрация | `console.groq.com` |

### Модели Groq

| Model ID | Название | Скорость | Контекст |
|---|---|---|---|
| `llama-3.3-70b-versatile` | Llama 3.3 70B | ⚡⚡⚡ | 128K |
| `deepseek-r1-distill-llama-70b` | DeepSeek R1 Distill | ⚡⚡⚡ | 128K |
| `qwen-qwq-32b` | Qwen QwQ 32B | ⚡⚡⚡ | 131K |
| `llama-4-scout-17b-16e-instruct` | Llama 4 Scout | ⚡⚡⚡⚡ | 131K |
| `llama-3.1-8b-instant` | Llama 3.1 8B | ⚡⚡⚡⚡⚡ | 128K |

---

## 4. Изменения в коде

### 4.1 `build.gradle` — добавить GROQ_API_KEY

```groovy
// app/build.gradle — в блоке defaultConfig → buildConfigField

def groqApiKey = System.getenv("GROQ_API_KEY")
    ?: localProperties.getProperty("groq.api.key", "")

buildConfigField "String", "GROQ_API_KEY", "\"${groqApiKey}\""
```

В `local.properties` (не коммитить в git):

```properties
groq.api.key=gsk_xxxxxxxxxxxxxxxxxxxxxxxx
```

---

### 4.2 `PreferenceManager.kt` — добавить провайдер

```kotlin
// Добавить константы:
private const val KEY_AI_PROVIDER = "ai_provider"

// Добавить методы:
fun getAiProvider(context: Context): String =
    getPreferences(context).getString(KEY_AI_PROVIDER, "openrouter") ?: "openrouter"

fun setAiProvider(context: Context, provider: String) {
    getPreferences(context).edit().putString(KEY_AI_PROVIDER, provider).apply()
}

// Обновить дефолтную модель:
fun getAiModel(context: Context): String =
    getPreferences(context).getString(KEY_AI_MODEL, "deepseek/deepseek-v4-flash:free")
        ?: "deepseek/deepseek-v4-flash:free"
```

---

### 4.3 `AiProcessor.kt` — поддержка двух провайдеров

```kotlin
object AiProcessor {

    // Заменить одну константу API_URL на две:
    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val GROQ_URL       = "https://api.groq.com/openai/v1/chat/completions"

    // Добавить вспомогательную функцию выбора провайдера:
    private fun providerConfig(context: Context): Triple<String, String, String> {
        val provider = PreferenceManager.getAiProvider(context)
        val model    = PreferenceManager.getAiModel(context)
        return when (provider) {
            "groq" -> Triple(GROQ_URL, BuildConfig.GROQ_API_KEY, model)
            else   -> Triple(OPENROUTER_URL, BuildConfig.OPENROUTER_API_KEY, model)
        }
    }

    suspend fun summarizeNews(newsText: String, context: Context): String {
        val (apiUrl, apiKey, modelName) = providerConfig(context)

        if (apiKey.isBlank()) {
            Log.e(TAG, "API Key is missing for provider!")
            return "[AI Error: Key missing] $newsText"
        }

        // ... остальной код без изменений, заменить только:
        // API_URL → apiUrl
        // BuildConfig.OPENROUTER_API_KEY → apiKey
        // val modelName = PreferenceManager.getAiModel(context) → уже в providerConfig

        // Убрать заголовки специфичные для OpenRouter при использовании Groq:
        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)

        // Эти заголовки только для OpenRouter:
        if (PreferenceManager.getAiProvider(context) == "openrouter") {
            requestBuilder
                .addHeader("HTTP-Referer", "https://github.com/p2petrovich/TelegramNewsReader")
                .addHeader("X-Title", "TelegramNewsReader")
        }
        // ...
    }

    // testModelAvailability — аналогично обновить для поддержки обоих провайдеров
}
```

---

### 4.4 `MainActivity.kt` — UI для выбора провайдера и моделей

```kotlin
// Найти метод setupAiModelSpinner() или аналогичный блок (около строк 1550–1580)
// Заменить жёстко заданную карту моделей на динамическую:

private fun getAiModelsForProvider(provider: String): LinkedHashMap<String, String> {
    return when (provider) {
        "groq" -> linkedMapOf(
            "llama-3.3-70b-versatile"             to "Llama 3.3 70B — быстрый ⚡",
            "deepseek-r1-distill-llama-70b"        to "DeepSeek R1 Distill — reasoning ⚡",
            "qwen-qwq-32b"                         to "Qwen QwQ 32B — русский ⚡",
            "llama-4-scout-17b-16e-instruct"       to "Llama 4 Scout — новый ⚡",
            "llama-3.1-8b-instant"                 to "Llama 3.1 8B — сверхбыстрый ⚡"
        )
        else -> linkedMapOf(  // openrouter
            "deepseek/deepseek-v4-flash:free"        to "DeepSeek V4 Flash — дефолт — FREE",
            "qwen/qwen3-235b-a22b:free"              to "Qwen3 235B — лучший русский — FREE",
            "openai/gpt-oss-120b:free"               to "GPT-OSS 120B — качество — FREE",
            "deepseek/deepseek-r1:free"              to "DeepSeek R1 — reasoning — FREE",
            "google/gemini-2.0-flash-exp:free"       to "Gemini 2.0 Flash — Google — FREE",
            "meta-llama/llama-4-scout:free"          to "Llama 4 Scout — лёгкий — FREE",
            "meta-llama/llama-3.3-70b-instruct:free" to "Llama 3.3 70B — проверенный — FREE"
        )
    }
}

// В диалоге AI-настроек добавить RadioGroup или Spinner для выбора провайдера:
// "OpenRouter (27+ free моделей)" / "Groq (быстро, 1000 запросов/день)"
// При переключении провайдера — обновить список моделей в спиннере
// и сбросить выбранную модель на дефолт для этого провайдера
```

---

### 4.5 Дефолтная модель по провайдеру

```kotlin
// PreferenceManager.kt — добавить метод:
fun getDefaultModelForProvider(provider: String): String = when (provider) {
    "groq"       -> "llama-3.3-70b-versatile"
    "openrouter" -> "deepseek/deepseek-v4-flash:free"
    else         -> "deepseek/deepseek-v4-flash:free"
}

// При переключении провайдера в UI вызывать:
val newProvider = "groq" // или "openrouter"
PreferenceManager.setAiProvider(context, newProvider)
PreferenceManager.setAiModel(context, PreferenceManager.getDefaultModelForProvider(newProvider))
```

---

## 5. Итоговые списки моделей

### OpenRouter — 7 моделей (все бесплатные)

| Модель | ID | Стиль | Русский |
|---|---|---|---|
| DeepSeek V4 Flash | `deepseek/deepseek-v4-flash:free` | Все | ✅ |
| Qwen3 235B | `qwen/qwen3-235b-a22b:free` | balanced, extreme | ✅✅ |
| GPT-OSS 120B | `openai/gpt-oss-120b:free` | balanced, extreme | ✅✅ |
| DeepSeek R1 | `deepseek/deepseek-r1:free` | balanced | ✅✅ |
| Gemini 2.0 Flash | `google/gemini-2.0-flash-exp:free` | minimal, balanced | ✅ |
| Llama 4 Scout | `meta-llama/llama-4-scout:free` | minimal | ✅ |
| Llama 3.3 70B | `meta-llama/llama-3.3-70b-instruct:free` | Все | ✅ |

### Groq — 5 моделей (бесплатные, быстрые)

| Модель | ID | Скорость | Русский |
|---|---|---|---|
| Llama 3.3 70B | `llama-3.3-70b-versatile` | ⚡⚡⚡ | ✅ |
| DeepSeek R1 Distill | `deepseek-r1-distill-llama-70b` | ⚡⚡⚡ | ✅✅ |
| Qwen QwQ 32B | `qwen-qwq-32b` | ⚡⚡⚡ | ✅✅ |
| Llama 4 Scout | `llama-4-scout-17b-16e-instruct` | ⚡⚡⚡⚡ | ✅ |
| Llama 3.1 8B | `llama-3.1-8b-instant` | ⚡⚡⚡⚡⚡ | ➡️ minimal |

---

## Порядок выполнения

```
[ ] 1. Исправить сломанный ID Gemini (5 мин)
        MainActivity.kt строка 1557
        "google/gemini-flash-1.5-free" → "google/gemini-2.0-flash-exp:free"

[ ] 2. Удалить устаревшие модели (5 мин)
        Убрать glm-4.5-air и nemotron-3-super из списка

[ ] 3. Добавить новые OpenRouter-модели (10 мин)
        qwen3-235b, deepseek-r1, llama-4-scout

[ ] 4. Добавить GROQ_API_KEY в build.gradle (10 мин)
        + запись в local.properties

[ ] 5. Добавить getAiProvider/setAiProvider в PreferenceManager (15 мин)

[ ] 6. Обновить AiProcessor под два провайдера (30 мин)
        providerConfig(), убрать OR-специфичные заголовки для Groq

[ ] 7. Обновить UI диалога AI-настроек (30–45 мин)
        Переключатель провайдера + динамический список моделей

[ ] 8. Получить бесплатный API-ключ Groq (5 мин)
        console.groq.com → Create API Key
```

**Общая оценка:** ~2 часа работы.
