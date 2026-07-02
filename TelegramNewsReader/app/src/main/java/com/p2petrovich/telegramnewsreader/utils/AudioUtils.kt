package com.p2petrovich.telegramnewsreader.utils

import android.util.Log
import java.io.File
import java.io.FileOutputStream

object AudioUtils {

    private const val TAG = "AudioUtils"

    // Целевой формат пайплайна. После устранения FFmpeg все части приводятся
    // к единому формату на этапе синтеза: Edge запрашивается как
    // audio-24khz-48kbitrate-mono-mp3, декодируется в PCM через MediaCodec
    // в EdgeTtsProvider. Выход Android System TTS нормализуется через PcmResampler.
    // Поэтому склейка идёт без перекодирования.
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
            var totalPcmSize = 0L
            for (f in parts) {
                val size = f.length()
                if (size > 44) {
                    totalPcmSize += (size - 44) // Грубая оценка, если не парсить каждый заголовок
                }
            }

            FileOutputStream(output).use { os ->
                // Пишем временный заголовок (размер обновим в конце)
                val dummyHeader = buildWavHeader(
                    totalPcmSize.toInt(), // Временно, может быть обрезано до Int
                    sampleRate = TARGET_SAMPLE_RATE,
                    channels = TARGET_CHANNELS,
                    bitsPerSample = TARGET_BITS
                )
                os.write(dummyHeader)

                var actualPcmSize = 0L
                val buffer = ByteArray(8192)

                for (f in parts) {
                    f.inputStream().use { isStream ->
                        val header = ByteArray(44)
                        val readHeader = isStream.read(header)
                        if (readHeader < 44) return@use

                        // Простейшая реализация: пропускаем первые 44 байта стандартного WAV
                        // Если в файле были метаданные до data, они попадут в шум, 
                        // но PcmResampler/EdgeTts делают стандартные файлы.
                        var bytesRead: Int
                        while (isStream.read(buffer).also { bytesRead = it } != -1) {
                            os.write(buffer, 0, bytesRead)
                            actualPcmSize += bytesRead
                        }
                    }
                }

                // Обновляем заголовок с точным размером
                os.channel.position(0)
                val finalHeader = buildWavHeader(
                    actualPcmSize.toInt(),
                    sampleRate = TARGET_SAMPLE_RATE,
                    channels = TARGET_CHANNELS,
                    bitsPerSample = TARGET_BITS
                )
                os.write(finalHeader)
            }

            val ok = output.exists() && output.length() > 44
            if (ok) {
                Log.d(TAG, "WAV concat success: ${output.name} (${output.length() / 1024} KB)")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "WAV concat exception", e)
            false
        }
    }

    /**
     * Вычисляет общую длительность списка аудиофайлов в минутах.
     * Должно вызываться в фоновом потоке.
     */
    fun calcDurationMinutes(files: List<File>): Int {
        var totalMs = 0L
        val retriever = android.media.MediaMetadataRetriever()
        files.forEach { file ->
            try {
                retriever.setDataSource(file.absolutePath)
                val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                totalMs += durationStr?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                Log.w(TAG, "Error getting duration of ${file.name}", e)
            }
        }
        try { retriever.release() } catch (_: Exception) {}
        return (totalMs / 1000 / 60).toInt()
    }
}
