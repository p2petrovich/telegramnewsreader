package com.p2petrovich.telegramnewsreader.utils

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Нативный (чистый Kotlin) ресемплер/нормализатор WAV → 24kHz / mono / 16-bit PCM.
 *
 * Заменяет FFmpeg-ресемплинг для нормализации выхода Android System TTS. 
 * Формат выхода зависит от движка устройства (Google/Samsung/Yandex). 
 * Без приведения к единому формату склейка глав (Edge 24kHz + системный TTS) невозможна.
 *
 * Алгоритм:
 *  1. Парсим входной WAV (sampleRate / channels / bits / data-чанк).
 *  2. Приводим к 16-bit signed.
 *  3. Сводим в моно.
 *  4. Линейная интерполяция к целевому sample rate (24kHz).
 *  5. Пишем новый WAV с заголовком из WavUtils.
 */
object PcmResampler {

    private const val TAG = "PcmResampler"

    const val TARGET_SAMPLE_RATE = 24000
    const val TARGET_CHANNELS = 1
    const val TARGET_BITS = 16

    private data class WavData(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val pcm: ByteArray   // сырые data-байты
    )

    /**
     * Нормализует [input] к 24kHz/mono/16bit.
     * Если файл уже в целевом формате — не трогает его.
     */
    fun normalizeToTarget(input: File): File? {
        return try {
            val bytes = input.readBytes()
            val wav = parseWav(bytes) ?: run {
                Log.e(TAG, "normalizeToTarget: cannot parse ${input.name}")
                return null
            }

            if (wav.sampleRate == TARGET_SAMPLE_RATE &&
                wav.channels == TARGET_CHANNELS &&
                wav.bitsPerSample == TARGET_BITS
            ) {
                return input
            }

            Log.d(TAG, "Resampling ${input.name}: ${wav.sampleRate}Hz/${wav.channels}ch/${wav.bitsPerSample}bit -> $TARGET_SAMPLE_RATE/mono/16")

            val monoSamples = toMonoShorts(wav)
            val resampled = if (wav.sampleRate == TARGET_SAMPLE_RATE) monoSamples
                            else linearResample(monoSamples, wav.sampleRate, TARGET_SAMPLE_RATE)

            val outPcm = ByteArray(resampled.size * 2)
            var j = 0
            for (s in resampled) {
                outPcm[j++] = (s.toInt() and 0xFF).toByte()
                outPcm[j++] = ((s.toInt() shr 8) and 0xFF).toByte()
            }

            val out = File(input.parentFile, input.nameWithoutExtension + "_norm.wav")
            FileOutputStream(out).use { os ->
                os.write(buildWavHeader(outPcm.size, TARGET_SAMPLE_RATE, TARGET_CHANNELS, TARGET_BITS))
                os.write(outPcm)
            }

            if (out.exists() && out.length() > 44) {
                out
            } else {
                if (out.exists()) out.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "normalizeToTarget failed for ${input.name}: ${e.message}")
            null
        }
    }

    private fun toMonoShorts(wav: WavData): ShortArray {
        val ch = wav.channels.coerceAtLeast(1)
        return when (wav.bitsPerSample) {
            16 -> {
                val frameCount = wav.pcm.size / (2 * ch)
                ShortArray(frameCount) { frame ->
                    var acc = 0
                    for (c in 0 until ch) {
                        val idx = (frame * ch + c) * 2
                        if (idx + 1 < wav.pcm.size) {
                            val lo = wav.pcm[idx].toInt() and 0xFF
                            val hi = wav.pcm[idx + 1].toInt()
                            acc += (hi shl 8) or lo
                        }
                    }
                    (acc / ch).toShort()
                }
            }
            8 -> {
                val frameCount = wav.pcm.size / ch
                ShortArray(frameCount) { frame ->
                    var acc = 0
                    for (c in 0 until ch) {
                        val idx = frame * ch + c
                        if (idx < wav.pcm.size) {
                            val u = wav.pcm[idx].toInt() and 0xFF
                            acc += (u - 128) shl 8
                        }
                    }
                    (acc / ch).toShort()
                }
            }
            else -> {
                Log.w(TAG, "Unsupported bitsPerSample=${wav.bitsPerSample}, treating as 16-bit")
                val frameCount = wav.pcm.size / (2 * ch)
                ShortArray(frameCount) { frame ->
                    val idx = frame * ch * 2
                    if (idx + 1 < wav.pcm.size) {
                        val lo = wav.pcm[idx].toInt() and 0xFF
                        val hi = wav.pcm[idx + 1].toInt()
                        ((hi shl 8) or lo).toShort()
                    } else 0
                }
            }
        }
    }

    private fun linearResample(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (input.isEmpty()) return input
        val outLen = ((input.size.toLong() * dstRate) / srcRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val a = input[idx.coerceIn(0, input.size - 1)].toInt()
            val b = input[(idx + 1).coerceIn(0, input.size - 1)].toInt()
            out[i] = (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun parseWav(bytes: ByteArray): WavData? {
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) return null

        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0

        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val sz = (bytes[i + 4].toInt() and 0xFF) or
                     ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                     ((bytes[i + 6].toInt() and 0xFF) shl 16) or
                     ((bytes[i + 7].toInt() and 0xFF) shl 24)
            val body = i + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 <= bytes.size) {
                        channels = (bytes[body + 2].toInt() and 0xFF) or
                                   ((bytes[body + 3].toInt() and 0xFF) shl 8)
                        sampleRate = (bytes[body + 4].toInt() and 0xFF) or
                                     ((bytes[body + 5].toInt() and 0xFF) shl 8) or
                                     ((bytes[body + 6].toInt() and 0xFF) shl 16) or
                                     ((bytes[body + 7].toInt() and 0xFF) shl 24)
                        bits = (bytes[body + 14].toInt() and 0xFF) or
                               ((bytes[body + 15].toInt() and 0xFF) shl 8)
                    }
                }
                "data" -> {
                    dataOffset = body
                    dataSize = if (sz <= 0 || body + sz > bytes.size) bytes.size - body else sz
                }
            }
            if (id == "data") break
            i = body + sz + (sz and 1)
        }

        if (sampleRate == 0 || channels == 0 || bits == 0 || dataOffset < 0) return null

        val pcm = bytes.copyOfRange(dataOffset, dataOffset + dataSize)
        return WavData(sampleRate, channels, bits, pcm)
    }
}
