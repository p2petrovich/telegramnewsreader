package com.p2petrovich.telegramnewsreader.tts

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Microsoft Edge Neural TTS провайдер.
 *
 * Использует неофициальный WebSocket API, тот же что Edge браузер.
 * Голоса: ru-RU-DmitryNeural (муж), ru-RU-SvetlanaNeural (жен).
 * Выходной формат: WAV (riff-24khz-16bit-mono-pcm) — совместим с текущим пайплайном.
 * Бесплатно, без API ключа.
 */
class EdgeTtsProvider(
    val voice: String = VOICE_DMITRY,
    val ratePct: Int = 0,   // скорость: -50..+100 (%)
    val pitchHz: Int = 0    // тон: -200..+200 (Hz)
) {

    companion object {
        private const val TAG = "EdgeTtsProvider"

        const val VOICE_DMITRY   = "ru-RU-DmitryNeural"
        const val VOICE_SVETLANA = "ru-RU-SvetlanaNeural"

        private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WS_BASE = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

        // Максимум символов на один запрос (Edge ограничивает ~10KB SSML)
        private const val MAX_CHARS = 3000
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // ─── Публичный метод: текст → WAV файл ───────────────────────────────────

    /**
     * Синтезирует [text] в WAV-файл [outputFile].
     * Если текст длиннее [MAX_CHARS] — разбивает на части и склеивает PCM.
     * @return true при успехе
     */
    suspend fun synthesizeToWav(text: String, outputFile: File): Boolean {
        if (text.isBlank()) return false

        return withTimeoutOrNull(60_000L) {
            if (text.length <= MAX_CHARS) {
                synthesizePart(text, outputFile)
            } else {
                synthesizeLong(text, outputFile)
            }
        } ?: run {
            Log.e(TAG, "synthesizeToWav timeout for: ${text.take(60)}")
            false
        }
    }

    // ─── Длинный текст: разбить → синтезировать части → склеить ─────────────

    private suspend fun synthesizeLong(text: String, outputFile: File): Boolean {
        val parts = splitText(text, MAX_CHARS)
        val tempFiles = mutableListOf<File>()

        try {
            for ((i, part) in parts.withIndex()) {
                val tmp = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_edge_part$i.wav")
                val ok = synthesizePart(part, tmp)
                if (!ok) {
                    Log.e(TAG, "Part $i synthesis failed")
                    return false
                }
                tempFiles.add(tmp)
            }
            return concatPcmWavFiles(tempFiles, outputFile)
        } finally {
            tempFiles.forEach { try { it.delete() } catch (_: Exception) {} }
        }
    }

    // ─── Синтез одной части через WebSocket ──────────────────────────────────

    private suspend fun synthesizePart(text: String, outputFile: File): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val connectionId = uuid()
            val requestId    = uuid()
            val timestamp    = isoTimestamp()
            val wsUrl        = "$WS_BASE?TrustedClientToken=$TOKEN&ConnectionId=$connectionId"

            val request = Request.Builder()
                .url(wsUrl)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0")
                .build()

            val audioBuf = ByteArrayOutputStream()
            var turned   = false  // получили turn.end?

            val ws = client.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(buildConfigMsg(timestamp))
                    webSocket.send(buildSsmlMsg(requestId, timestamp, text))
                }

                // Текстовые фреймы — служебные (turn.start / turn.end / metadata)
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end") && !turned) {
                        turned = true
                        webSocket.close(1000, "done")
                        val ok = writeWav(audioBuf.toByteArray(), outputFile)
                        if (continuation.isActive) continuation.resume(ok)
                    }
                }

                // Бинарные фреймы — аудио
                // Формат: 2 байта (big-endian) = длина заголовка, затем заголовок, затем PCM
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    if (data.size < 2) return
                    val headerLen = ((data[0].toInt() and 0xFF) shl 8) or
                                     (data[1].toInt() and 0xFF)
                    if (data.size < 2 + headerLen) return
                    val header = String(data, 2, headerLen, Charsets.UTF_8)
                    if (header.contains("Path:audio")) {
                        val audioStart = 2 + headerLen
                        audioBuf.write(data, audioStart, data.size - audioStart)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WS failure: ${t.message} (${response?.code})")
                    if (continuation.isActive) continuation.resume(false)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    // Если turn.end не пришёл, а соединение закрыто — пробуем записать что есть
                    if (!turned && continuation.isActive) {
                        turned = true
                        val ok = writeWav(audioBuf.toByteArray(), outputFile)
                        continuation.resume(ok)
                    }
                }
            })

            continuation.invokeOnCancellation {
                ws.cancel()
            }
        }
    }

    // ─── Построение WebSocket сообщений ──────────────────────────────────────

    private fun buildConfigMsg(timestamp: String): String {
        val header = "X-Timestamp:$timestamp\r\n" +
                     "Content-Type:application/json; charset=utf-8\r\n" +
                     "Path:speech.config\r\n\r\n"
        val body = """{"context":{"synthesis":{"audio":{"metadataoptions":""" +
                   """{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
                   """"outputFormat":"riff-24khz-16bit-mono-pcm"}}}}"""
        return header + body
    }

    private fun buildSsmlMsg(requestId: String, timestamp: String, text: String): String {
        val header = "X-RequestId:$requestId\r\n" +
                     "Content-Type:application/ssml+xml\r\n" +
                     "X-Timestamp:$timestamp\r\n" +
                     "Path:ssml\r\n\r\n"
        return header + buildSsml(text)
    }

    private fun buildSsml(text: String): String {
        val rateStr  = if (ratePct >= 0) "+${ratePct}%" else "${ratePct}%"
        val pitchStr = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
        val escaped  = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        return """<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="ru-RU">
<voice name="$voice">
<prosody rate="$rateStr" pitch="$pitchStr">$escaped</prosody>
</voice>
</speak>"""
    }

    // ─── WAV утилиты ─────────────────────────────────────────────────────────

    /**
     * Записывает PCM-данные в файл.
     * riff-24khz-16bit-mono-pcm уже приходит с RIFF-заголовком в первом фрейме,
     * поэтому просто сохраняем байты как есть.
     */
    private fun writeWav(audioBytes: ByteArray, outputFile: File): Boolean {
        if (audioBytes.isEmpty()) {
            Log.e(TAG, "Empty audio buffer for ${outputFile.name}")
            return false
        }
        return try {
            FileOutputStream(outputFile).use { it.write(audioBytes) }
            outputFile.exists() && outputFile.length() > 44  // минимум: RIFF заголовок
        } catch (e: Exception) {
            Log.e(TAG, "writeWav failed: ${e.message}")
            false
        }
    }

    /**
     * Склейка нескольких WAV (одинаковый формат 24kHz/16bit/mono).
     * Первый файл даёт RIFF-заголовок, остальные — только PCM-данные.
     */
    private fun concatPcmWavFiles(files: List<File>, output: File): Boolean {
        if (files.isEmpty()) return false
        if (files.size == 1) {
            files[0].copyTo(output, overwrite = true)
            return output.exists()
        }

        return try {
            val allPcm = ByteArrayOutputStream()

            for (f in files) {
                val bytes = f.readBytes()
                if (bytes.size < 44) continue
                // Пропускаем RIFF-заголовок (44 байта) — берём только PCM
                val dataStart = findDataChunkOffset(bytes)
                if (dataStart > 0 && dataStart < bytes.size) {
                    allPcm.write(bytes, dataStart, bytes.size - dataStart)
                } else {
                    allPcm.write(bytes, 44, bytes.size - 44)
                }
            }

            val pcmData   = allPcm.toByteArray()
            val wavHeader = buildWavHeader(
                pcmData.size,
                sampleRate = 24000,
                channels   = 1,
                bitsPerSample = 16
            )

            FileOutputStream(output).use { os ->
                os.write(wavHeader)
                os.write(pcmData)
            }
            output.exists() && output.length() > 44
        } catch (e: Exception) {
            Log.e(TAG, "concatPcmWavFiles: ${e.message}")
            false
        }
    }

    /** Находит смещение начала PCM-данных (после "data" chunk header). */
    private fun findDataChunkOffset(wav: ByteArray): Int {
        var i = 12  // пропускаем "RIFF....WAVE"
        while (i + 8 <= wav.size) {
            val chunkId   = String(wav, i, 4, Charsets.US_ASCII)
            val chunkSize = ((wav[i+4].toInt() and 0xFF))       or
                            ((wav[i+5].toInt() and 0xFF) shl 8)  or
                            ((wav[i+6].toInt() and 0xFF) shl 16) or
                            ((wav[i+7].toInt() and 0xFF) shl 24)
            i += 8
            if (chunkId == "data") return i  // данные начинаются здесь
            i += chunkSize
        }
        return -1
    }

    /** Строит стандартный 44-байтный WAV-заголовок. */
    private fun buildWavHeader(pcmSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate   = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header     = ByteArray(44)

        fun writeLE(value: Int, offset: Int, bytes: Int) {
            for (b in 0 until bytes) header[offset + b] = ((value shr (8 * b)) and 0xFF).toByte()
        }
        "RIFF".toByteArray().copyInto(header, 0)
        writeLE(36 + pcmSize, 4, 4)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        writeLE(16,           16, 4)   // субчанк fmt size
        writeLE(1,            20, 2)   // PCM = 1
        writeLE(channels,     22, 2)
        writeLE(sampleRate,   24, 4)
        writeLE(byteRate,     28, 4)
        writeLE(blockAlign,   32, 2)
        writeLE(bitsPerSample,34, 2)
        "data".toByteArray().copyInto(header, 36)
        writeLE(pcmSize,      40, 4)
        return header
    }

    // ─── Вспомогательные ─────────────────────────────────────────────────────

    private fun uuid() = UUID.randomUUID().toString().replace("-", "")

    private fun isoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    /** Разбивает текст по предложениям, не превышая maxChars. */
    private fun splitText(text: String, maxChars: Int): List<String> {
        val parts   = mutableListOf<String>()
        val current = StringBuilder()

        // Разбиваем по предложениям (. ! ? …)
        val sentences = text.split(Regex("(?<=[.!?…])\\s+"))

        for (sentence in sentences) {
            if (current.length + sentence.length + 1 > maxChars) {
                if (current.isNotEmpty()) {
                    parts.add(current.toString().trim())
                    current.clear()
                }
                if (sentence.length > maxChars) {
                    // Очень длинное предложение — режем по словам
                    sentence.chunked(maxChars).forEach { parts.add(it) }
                } else {
                    current.append(sentence)
                }
            } else {
                if (current.isNotEmpty()) current.append(" ")
                current.append(sentence)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString().trim())
        return parts.filter { it.isNotBlank() }
    }
}
