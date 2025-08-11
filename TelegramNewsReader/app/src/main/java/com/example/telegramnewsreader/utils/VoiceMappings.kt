package com.example.telegramnewsreader.models

import android.speech.tts.Voice
import android.util.Log

/**
 * Объект для маппинга системных имен голосов в понятные названия
 */
object VoiceMappings {

    /**
     * Предопределенные маппинги русских голосов
     * Обновлено на основе реальных логов приложения
     */
    private val voiceMappings = mapOf(
        // Google TTS локальные голоса
        "ru-ru-x-ruf-local" to VoiceEntry("ru-ru-x-ruf-local", "Александр", VoiceEntry.Gender.MALE, "ru", "RU"),
        "ru-ru-x-rue-local" to VoiceEntry("ru-ru-x-rue-local", "Елена", VoiceEntry.Gender.FEMALE, "ru", "RU"),
        "ru-ru-x-rud-local" to VoiceEntry("ru-ru-x-rud-local", "Максим", VoiceEntry.Gender.MALE, "ru", "RU"),
        "ru-ru-x-dfc-local" to VoiceEntry("ru-ru-x-dfc-local", "Анна", VoiceEntry.Gender.FEMALE, "ru", "RU"),
        "ru-ru-x-ruc-local" to VoiceEntry("ru-ru-x-ruc-local", "Ирина", VoiceEntry.Gender.FEMALE, "ru", "RU"),
        "ru-RU-language" to VoiceEntry("ru-RU-language", "Дмитрий", VoiceEntry.Gender.MALE, "ru", "RU"),

        // Google TTS сетевые голоса
        "ru-ru-x-ruf-network" to VoiceEntry("ru-ru-x-ruf-network", "Александр HD", VoiceEntry.Gender.MALE, "ru", "RU", true),
        "ru-ru-x-rue-network" to VoiceEntry("ru-ru-x-rue-network", "Елена HD", VoiceEntry.Gender.FEMALE, "ru", "RU", true),
        "ru-ru-x-rud-network" to VoiceEntry("ru-ru-x-rud-network", "Максим HD", VoiceEntry.Gender.MALE, "ru", "RU", true),
        "ru-ru-x-dfc-network" to VoiceEntry("ru-ru-x-dfc-network", "Анна HD", VoiceEntry.Gender.FEMALE, "ru", "RU", true),
        "ru-ru-x-ruc-network" to VoiceEntry("ru-ru-x-ruc-network", "Ирина HD", VoiceEntry.Gender.FEMALE, "ru", "RU", true),

        // Samsung TTS голоса
        "ru-RU-SMTf00" to VoiceEntry("ru-RU-SMTf00", "София", VoiceEntry.Gender.FEMALE, "ru", "RU"),
        "ru-RU-SMTm00" to VoiceEntry("ru-RU-SMTm00", "Михаил", VoiceEntry.Gender.MALE, "ru", "RU"),

        // Другие возможные варианты
        "ru_RU_female" to VoiceEntry("ru_RU_female", "Мария", VoiceEntry.Gender.FEMALE, "ru", "RU"),
        "ru_RU_male" to VoiceEntry("ru_RU_male", "Сергей", VoiceEntry.Gender.MALE, "ru", "RU"),
        "ru-ru-female" to VoiceEntry("ru-ru-female", "Екатерина", VoiceEntry.Gender.FEMALE, "ru", "RU"),
        "ru-ru-male" to VoiceEntry("ru-ru-male", "Николай", VoiceEntry.Gender.MALE, "ru", "RU")
    )

    /**
     * Получить VoiceEntry по системному имени голоса
     */


    /**
     * Преобразовать системный Voice в VoiceEntry с понятным названием
     */
    fun mapVoice(voice: Voice): VoiceEntry {
        // Сначала попробуем найти точное совпадение
        voiceMappings[voice.name]?.let {
            Log.d("VoiceMappings", "🎯 Использован предопределенный маппинг: ${voice.name} -> ${it.displayName}")
            return it
        }

        // Если точного совпадения нет, создаем автоматически
        Log.d("VoiceMappings", "🔧 Создаем автоматический маппинг для: ${voice.name}")
        return createVoiceEntryFromSystemVoice(voice)
    }

    /**
     * Преобразовать список системных Voice в VoiceEntry только для русских голосов
     */
    fun mapVoices(voices: List<Voice>): List<VoiceEntry> {
        Log.d("VoiceMappings", "🔄 Маппинг ${voices.size} голосов...")

        val russianVoiceEntries = voices
            .filter { voice ->
                // Фильтруем только русские голоса
                val isRussian = voice.locale.language == "ru" ||
                        voice.locale.toString().startsWith("ru", ignoreCase = true)
                if (isRussian) {
                    Log.d("VoiceMappings", "🇷🇺 Русский голос: ${voice.name} (${voice.locale})")
                }
                isRussian
            }
            .map { voice -> mapVoice(voice) }
            .distinctBy { it.systemName } // Убираем дубликаты
            .sortedWith(compareBy<VoiceEntry> { it.gender.ordinal }.thenBy { it.displayName })

        Log.d("VoiceMappings", "✅ Замаплено ${russianVoiceEntries.size} русских голосов")
        return russianVoiceEntries
    }

    /**
     * Создать VoiceEntry из системного Voice для русских голосов
     */
    private fun createVoiceEntryFromSystemVoice(voice: Voice): VoiceEntry {
        val systemName = voice.name
        val isNetwork = systemName.contains("network", ignoreCase = true)

        // Определяем пол по имени голоса
        val gender = when {
            // Женские паттерны
            systemName.contains("female", ignoreCase = true) ||
                    systemName.contains("f00", ignoreCase = true) ||
                    systemName.contains("rue", ignoreCase = true) ||
                    systemName.contains("dfc", ignoreCase = true) ||
                    systemName.contains("ruc", ignoreCase = true) -> VoiceEntry.Gender.FEMALE  // 🔥 ruc = женский!

            // Мужские паттерны
            systemName.contains("male", ignoreCase = true) ||
                    systemName.contains("m00", ignoreCase = true) ||
                    systemName.contains("ruf", ignoreCase = true) ||
                    systemName.contains("rud", ignoreCase = true) -> VoiceEntry.Gender.MALE

            else -> VoiceEntry.Gender.NEUTRAL
        }

        // Генерируем русское имя
        val displayName = generateRussianName(systemName, gender, isNetwork)

        Log.d("VoiceMappings", "🔤 Автоматический маппинг: $systemName -> $displayName ($gender)")

        return VoiceEntry(
            systemName = systemName,
            displayName = displayName,
            gender = gender,
            language = voice.locale.language,
            country = voice.locale.country,
            isNetwork = isNetwork
        )
    }

    /**
     * Генерировать русское имя для голоса
     */
    private fun generateRussianName(systemName: String, gender: VoiceEntry.Gender, isNetwork: Boolean): String {
        val baseName = when (gender) {
            VoiceEntry.Gender.MALE -> {
                // Список мужских русских имен
                val maleNames = listOf("Владимир", "Сергей", "Алексей", "Павел", "Игорь", "Петр", "Артем", "Евгений")
                // Выбираем имя на основе хеш-кода, чтобы было стабильно
                maleNames[systemName.hashCode().rem(maleNames.size).let { if (it < 0) -it else it }]
            }
            VoiceEntry.Gender.FEMALE -> {
                // Список женских русских имен
                val femaleNames = listOf("Ольга", "Татьяна", "Наталья", "Ирина", "Светлана", "Юлия", "Марина", "Людмила")
                femaleNames[systemName.hashCode().rem(femaleNames.size).let { if (it < 0) -it else it }]
            }
            VoiceEntry.Gender.NEUTRAL -> "Голос"
        }

        val suffix = if (isNetwork) " HD" else ""
        return baseName + suffix
    }
}