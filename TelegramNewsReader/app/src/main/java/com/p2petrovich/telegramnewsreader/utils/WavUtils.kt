package com.p2petrovich.telegramnewsreader.utils

/**
 * Утилиты для работы с форматом WAV.
 */

/** Строит стандартный 44-байтный WAV-заголовок. */
internal fun buildWavHeader(
    pcmSize: Int,
    sampleRate: Int,
    channels: Int,
    bitsPerSample: Int
): ByteArray {
    val byteRate   = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val header     = ByteArray(44)

    fun writeLE(value: Int, offset: Int, bytes: Int) {
        for (b in 0 until bytes) {
            header[offset + b] = ((value shr (8 * b)) and 0xFF).toByte()
        }
    }

    "RIFF".toByteArray().copyInto(header, 0)
    writeLE(36 + pcmSize, 4, 4)
    "WAVE".toByteArray().copyInto(header, 8)
    "fmt ".toByteArray().copyInto(header, 12)
    writeLE(16,            16, 4)
    writeLE(1,             20, 2)
    writeLE(channels,      22, 2)
    writeLE(sampleRate,    24, 4)
    writeLE(byteRate,      28, 4)
    writeLE(blockAlign,    32, 2)
    writeLE(bitsPerSample, 34, 2)
    "data".toByteArray().copyInto(header, 36)
    writeLE(pcmSize,       40, 4)

    return header
}

/** Находит смещение начала PCM-данных (после заголовка "data" chunk). */
internal fun findDataChunkOffset(wav: ByteArray): Int {
    var i = 12
    while (i + 8 <= wav.size) {
        val chunkId = String(wav, i, 4, Charsets.US_ASCII)
        val chunkSize = ((wav[i + 4].toInt() and 0xFF)) or
                        ((wav[i + 5].toInt() and 0xFF) shl 8) or
                        ((wav[i + 6].toInt() and 0xFF) shl 16) or
                        ((wav[i + 7].toInt() and 0xFF) shl 24)
        i += 8
        if (chunkId == "data") return i
        i += chunkSize + (chunkSize and 1) // чанки выровнены по 2 байта
    }
    return -1
}
