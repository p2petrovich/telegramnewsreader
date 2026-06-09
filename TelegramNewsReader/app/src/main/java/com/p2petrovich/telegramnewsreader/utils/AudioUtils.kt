package com.p2petrovich.telegramnewsreader.utils

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object AudioUtils {

    private const val TAG = "AudioUtils"

    // Целевой формат пайплайна. После устранения FFmpeg все части приводятся
    // к единому формату на этапе синтеза: Edge запрашивается как
    // raw-24khz-16bit-mono-pcm, а выход Android System TTS нормализуется
    // через PcmResampler. Поэтому склейка идёт без перекодирования.
    private const val TARGET_SAMPLE_RATE = 24000
    private const val TARGET_CHANNELS = 1
    private const val TARGET_BITS = 16

    /**
     * Склейка нескольких WAV (одинаковый формат 24kHz/16bit/mono) в один файл.
     *
     * Раньше здесь использовался FFmpegKit с перекодированием pcm_s16le. После
     * устранения FFmpeg склейка делается на чистом Kotlin: вырезаем PCM-данные
     * каждого файла (по data-чанку) и пишем единый WAV с новым заголовком.
     * Это безопасно, потому что весь пайплайн теперь одноформатный.
     */
    fun concatWavFiles(parts: List<File>, output: File): Boolean {
        if (parts.isEmpty()) return false

        // Один файл — просто копируем, без разбора заголовков.
        if (parts.size == 1) {
            return try {
                parts[0].copyTo(output, overwrite = true)
                output.exists() && output.length() > 44
            } catch (e: Exception) {
                Log.e(TAG, "WAV single-copy exception", e)
                false
            }
        }

        return try {
            val allPcm = ByteArrayOutputStream()

            for (f in parts) {
                val bytes = f.readBytes()
                if (bytes.size < 44) continue
                val dataStart = findDataChunkOffset(bytes)
                if (dataStart in 1 until bytes.size) {
                    allPcm.write(bytes, dataStart, bytes.size - dataStart)
                } else {
                    // Fallback на стандартный 44-байтный заголовок, если data-чанк
                    // не найден парсером (нестандартная структура WAV).
                    allPcm.write(bytes, 44, bytes.size - 44)
                }
            }

            val pcmData = allPcm.toByteArray()
            if (pcmData.isEmpty()) {
                Log.e(TAG, "WAV concat: no PCM data collected")
                return false
            }

            val wavHeader = buildWavHeader(
                pcmData.size,
                sampleRate = TARGET_SAMPLE_RATE,
                channels = TARGET_CHANNELS,
                bitsPerSample = TARGET_BITS
            )

            FileOutputStream(output).use { os ->
                os.write(wavHeader)
                os.write(pcmData)
            }

            val ok = output.exists() && output.length() > 44
            if (ok) {
                Log.d(TAG, "WAV concat success: ${output.name} (${output.length() / 1024} KB)")
            } else {
                Log.e(TAG, "WAV concat produced invalid file")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "WAV concat exception", e)
            false
        }
    }

    /** Находит смещение начала PCM-данных (после заголовка "data" chunk). */
    private fun findDataChunkOffset(wav: ByteArray): Int {
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

    /** Строит стандартный 44-байтный WAV-заголовок. */
    private fun buildWavHeader(pcmSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)

        fun writeLE(value: Int, offset: Int, bytes: Int) {
            for (b in 0 until bytes) header[offset + b] = ((value shr (8 * b)) and 0xFF).toByte()
        }
        "RIFF".toByteArray().copyInto(header, 0)
        writeLE(36 + pcmSize, 4, 4)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        writeLE(16, 16, 4)
        writeLE(1, 20, 2)
        writeLE(channels, 22, 2)
        writeLE(sampleRate, 24, 4)
        writeLE(byteRate, 28, 4)
        writeLE(blockAlign, 32, 2)
        writeLE(bitsPerSample, 34, 2)
        "data".toByteArray().copyInto(header, 36)
        writeLE(pcmSize, 40, 4)
        return header
    }
}
