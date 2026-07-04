package com.p2petrovich.telegramnewsreader.models

import android.content.Context

object VoiceMappings {

    private val voiceMappings = mapOf(
        "ru-ru-x-ruf-local" to VoiceEntry("ru-ru-x-ruf-local", "Александр", VoiceEntry.Gender.MALE),
        "ru-ru-x-rue-local" to VoiceEntry("ru-ru-x-rue-local", "Елена", VoiceEntry.Gender.FEMALE),
        "ru-ru-x-rud-local" to VoiceEntry("ru-ru-x-rud-local", "Максим", VoiceEntry.Gender.MALE),
        "ru-ru-x-dfc-local" to VoiceEntry("ru-ru-x-dfc-local", "Анна", VoiceEntry.Gender.FEMALE),
        "ru-ru-x-ruc-local" to VoiceEntry("ru-ru-x-ruc-local", "Ирина", VoiceEntry.Gender.FEMALE),
        "ru-RU-language" to VoiceEntry("ru-RU-language", "Дмитрий", VoiceEntry.Gender.MALE),
        "ru-ru-x-ruf-network" to VoiceEntry("ru-ru-x-ruf-network", "Александр НД сеть", VoiceEntry.Gender.MALE, isNetwork = true),
        "ru-ru-x-rue-network" to VoiceEntry("ru-ru-x-rue-network", "Елена HD", VoiceEntry.Gender.FEMALE, isNetwork = true),
        "ru-ru-x-rud-network" to VoiceEntry("ru-ru-x-rud-network", "Максим HD", VoiceEntry.Gender.MALE, isNetwork = true),
        "ru-ru-x-dfc-network" to VoiceEntry("ru-ru-x-dfc-network", "Анна HD", VoiceEntry.Gender.FEMALE, isNetwork = true),
        "ru-ru-x-ruc-network" to VoiceEntry("ru-ru-x-ruc-network", "Ирина HD", VoiceEntry.Gender.FEMALE, isNetwork = true),
        "ru-RU-SMTf00" to VoiceEntry("ru-RU-SMTf00", "София", VoiceEntry.Gender.FEMALE),
        "ru-RU-SMTm00" to VoiceEntry("ru-RU-SMTm00", "Михаил", VoiceEntry.Gender.MALE)
    )

    fun mapVoice(context: Context, voice: android.speech.tts.Voice): VoiceEntry {
        voiceMappings[voice.name]?.let { return it }
        return createVoiceEntryFromSystemVoice(context, voice)
    }

    fun mapVoices(context: Context, voices: List<android.speech.tts.Voice>): List<VoiceEntry> {
        return voices
            .map { mapVoice(context, it) }
            .distinctBy { it.systemName }
            .sortedWith(compareBy<VoiceEntry> { it.language }.thenBy { it.gender.ordinal }.thenBy { it.displayName })
    }

    private fun createVoiceEntryFromSystemVoice(context: Context, voice: android.speech.tts.Voice): VoiceEntry {
        val systemName = voice.name
        val isNetwork = systemName.contains("network", ignoreCase = true)

        val gender = when {
            systemName.contains("female", true) || systemName.contains("f00", true) ||
            systemName.contains("rue", true) || systemName.contains("dfc", true) ||
            systemName.contains("ruc", true) -> VoiceEntry.Gender.FEMALE
            systemName.contains("male", true) || systemName.contains("m00", true) ||
            systemName.contains("ruf", true) || systemName.contains("rud", true) -> VoiceEntry.Gender.MALE
            else -> VoiceEntry.Gender.NEUTRAL
        }

        val displayName = generateRussianName(context, systemName, gender, isNetwork)

        return VoiceEntry(
            systemName = systemName,
            displayName = displayName,
            gender = gender,
            language = voice.locale.language,
            country = voice.locale.country,
            isNetwork = isNetwork
        )
    }

    private fun generateRussianName(context: Context, systemName: String, gender: VoiceEntry.Gender, isNetwork: Boolean): String {
        val baseName = when (gender) {
            VoiceEntry.Gender.MALE -> {
                val names = listOf("Владимир", "Сергей", "Алексей", "Павел", "Игорь", "Петр", "Артем", "Евгений")
                names[(systemName.hashCode() and 0x7FFFFFFF) % names.size]
            }
            VoiceEntry.Gender.FEMALE -> {
                val names = listOf("Ольга", "Татьяна", "Наталья", "Ирина", "Светлана", "Юлия", "Марина", "Людмила")
                names[(systemName.hashCode() and 0x7FFFFFFF) % names.size]
            }
            VoiceEntry.Gender.NEUTRAL -> context.getString(com.p2petrovich.telegramnewsreader.R.string.voice)
        }
        return if (isNetwork) "$baseName HD" else baseName
    }
}
