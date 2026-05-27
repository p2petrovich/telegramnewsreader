package com.p2petrovich.telegramnewsreader.tts

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
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
 *
 * Edge присылает MP3 (audio-24khz-48kbitrate-mono-mp3) — единственный формат,
 * поддерживаемый бесплатным эндпоинтом. PCM/RIFF возвращает ошибку 1007.
 * Мы декодируем MP3 в WAV 24kHz/16bit/mono через FFmpegKit, чтобы остальной
 * пайплайн работал с WAV без изменений.
 *
 * Алгоритм Sec-MS-GEC и SSML — повторяют эталон rany2/edge-tts.
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

        // Sec-MS-GEC — версия Chromium должна быть актуальной (синхронизировано с rany2/edge-tts)
        private const val CHROMIUM_FULL_VERSION  = "143.0.3650.75"
        private const val CHROMIUM_MAJOR_VERSION = "143"
        private const val SEC_MS_GEC_VERSION     = "1-$CHROMIUM_FULL_VERSION"
        private const val WIN_EPOCH              = 11_644_473_600L

        // Единственный поддерживаемый формат для бесплатного эндпоинта
        private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

        // Целевой формат WAV после декодирования (для совместимости с остальным пайплайном)
        private const val TARGET_SAMPLE_RATE = 24000
        private const val TARGET_CHANNELS    = 1
        private const val TARGET_BITS        = 16

        private const val MAX_CHARS = 3000

        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        fun formatRatePct(rate: Int): String = if (rate >= 0) "+${rate}%" else "${rate}%"
        fun formatPitchHz(pitch: Int): String = if (pitch >= 0) "+${pitch}Hz" else "${pitch}Hz"
    }

    // ─── Публичный метод: текст → WAV файл ───────────────────────────────────

    /**
     * Синтезирует [text] в WAV-файл [outputFile].
     * Внутренне получает MP3 от Edge, потом декодирует в WAV через FFmpeg.
     * @return true при успехе
     */
    suspend fun synthesizeToWav(text: String, outputFile: File): Boolean {
        if (text.isBlank()) return false

        return withTimeoutOrNull(60_000L) {
            if (text.length <= MAX_CHARS) {
                synthesizePartToWav(text, outputFile)
            } else {
                synthesizeLongToWav(text, outputFile)
            }
        } ?: run {
            Log.e(TAG, "synthesizeToWav overall timeout for: ${text.take(60)}")
            false
        }
    }

    // ─── Один кусок: MP3 → WAV ──────────────────────────────────────────────

    private suspend fun synthesizePartToWav(text: String, outputFile: File): Boolean {
        val mp3File = File(outputFile.parentFile, outputFile.nameWithoutExtension + "_edge.mp3")
        return try {
            val gotMp3 = synthesizePartToMp3(text, mp3File)
            if (!gotMp3 || !mp3File.exists() || mp3File.length() == 0L) {
                Log.e(TAG, "MP3 synthesis failed")
                return false
            }
            val converted = convertMp3ToWav(mp3File, outputFile)
            if (!converted) Log.e(TAG, "MP3→WAV conversion failed")
            converted
        } finally {
            try { mp3File.delete() } catch (_: Exception) {}
        }
    }

    // ─── Длинный текст: каждая часть → свой MP3 → склейка MP3 → один WAV ────

    private suspend fun synthesizeLongToWav(text: String, outputFile: File): Boolean {
        val parts = splitText(text, MAX_CHARS)
        val tempMp3s = mutableListOf<File>()

        return try {
            for ((i, part) in parts.withIndex()) {
                val tmp = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_edge_part$i.mp3")
                val ok = synthesizePartToMp3(part, tmp)
                if (!ok || !tmp.exists() || tmp.length() == 0L) {
                    Log.e(TAG, "Part $i synthesis failed")
                    return false
                }
                tempMp3s.add(tmp)
            }

            // Склейка MP3 — простая конкатенация байтов (для CBR MP3 это работает)
            val mergedMp3 = File(outputFile.parentFile, outputFile.nameWithoutExtension + "_edge_merged.mp3")
            try {
                FileOutputStream(mergedMp3).use { out ->
                    for (f in tempMp3s) out.write(f.readBytes())
                }
                convertMp3ToWav(mergedMp3, outputFile)
            } finally {
                try { mergedMp3.delete() } catch (_: Exception) {}
            }
        } finally {
            tempMp3s.forEach { try { it.delete() } catch (_: Exception) {} }
        }
    }

    // ─── FFmpeg: MP3 → WAV ───────────────────────────────────────────────────

    private fun convertMp3ToWav(mp3: File, wav: File): Boolean {
        if (wav.exists()) wav.delete()
        val cmd = arrayOf(
            "-y",
            "-i", mp3.absolutePath,
            "-ar", TARGET_SAMPLE_RATE.toString(),
            "-ac", TARGET_CHANNELS.toString(),
            "-sample_fmt", "s16",
            wav.absolutePath
        )
        val session = FFmpegKit.executeWithArguments(cmd)
        val ok = ReturnCode.isSuccess(session.returnCode) && wav.exists() && wav.length() > 44
        if (!ok) {
            Log.e(TAG, "FFmpeg MP3→WAV failed: rc=${session.returnCode}, logs=${session.allLogsAsString?.take(500)}")
        }
        return ok
    }

    /**
     * Генерирует Sec-MS-GEC токен (см. drm.py rany2/edge-tts).
     */
    private fun generateSecMsGec(): String {
        var ticks: Double = System.currentTimeMillis() / 1000.0
        ticks += WIN_EPOCH
        ticks -= ticks % 300.0
        ticks *= 1e9 / 100.0

        val ticksStr = String.format(Locale.US, "%.0f", ticks)
        val strToHash = "$ticksStr$TOKEN"

        val digest = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(strToHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    // ─── Синтез одной части в MP3 через WebSocket ───────────────────────────

    private suspend fun synthesizePartToMp3(text: String, outputMp3: File): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val connectionId = uuid()
            val requestId    = uuid()
            val timestamp    = isoTimestamp()
            val secMsGec     = generateSecMsGec()

            val wsUrl = "$WS_BASE" +
                    "?TrustedClientToken=$TOKEN" +
                    "&Sec-MS-GEC=$secMsGec" +
                    "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION" +
                    "&ConnectionId=$connectionId"

            Log.d(TAG, "synthesizePartToMp3 start, text='${text.take(40)}', voice=$voice")

            val request = Request.Builder()
                .url(wsUrl)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 " +
                    "Edg/$CHROMIUM_MAJOR_VERSION.0.0.0")
                .build()

            val audioBuf = ByteArrayOutputStream()
            var turned   = false
            var resumed  = false

            fun finish(ws: WebSocket?, success: Boolean, reason: String) {
                if (resumed) return
                resumed = true
                turned = true
                val size = synchronized(audioBuf) { audioBuf.size() }
                Log.d(TAG, "finish: success=$success, reason=$reason, mp3Size=$size")
                try { ws?.close(1000, "done") } catch (_: Exception) {}
                val ok = if (success && size > 0) {
                    val bytes = synchronized(audioBuf) { audioBuf.toByteArray() }
                    try {
                        FileOutputStream(outputMp3).use { it.write(bytes) }
                        outputMp3.exists() && outputMp3.length() > 0
                    } catch (e: Exception) {
                        Log.e(TAG, "writeMp3 failed: ${e.message}")
                        false
                    }
                } else false
                if (continuation.isActive) continuation.resume(ok)
            }

            val ws = sharedClient.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WS opened (http=${response.code})")
                    val cfg  = buildConfigMsg(timestamp)
                    val ssml = buildSsmlMsg(requestId, timestamp, text)
                    webSocket.send(cfg)
                    webSocket.send(ssml)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "← text frame:\n${text.take(300)}")
                    val path = extractPath(text)
                    if (path == "turn.end") {
                        finish(webSocket, success = true, reason = "turn.end (text)")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    if (data.size < 2) return

                    val headerLen = ((data[0].toInt() and 0xFF) shl 8) or
                                     (data[1].toInt() and 0xFF)
                    if (data.size < 2 + headerLen) return

                    val header = String(data, 2, headerLen, Charsets.UTF_8)
                    val path = extractPath(header)

                    when (path) {
                        "audio" -> {
                            val audioStart = 2 + headerLen
                            val payloadSize = data.size - audioStart
                            if (payloadSize > 0) {
                                val isFirst = synchronized(audioBuf) { audioBuf.size() == 0 }
                                if (isFirst) Log.d(TAG, "← first audio frame, payloadSize=$payloadSize")
                                synchronized(audioBuf) {
                                    audioBuf.write(data, audioStart, payloadSize)
                                }
                            }
                        }
                        "turn.end" -> {
                            Log.d(TAG, "← turn.end (binary)")
                            finish(webSocket, success = true, reason = "turn.end (binary)")
                        }
                        else -> {
                            Log.d(TAG, "← binary frame, path='$path', header='${header.take(160)}'")
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WS failure code=${response?.code} msg=${response?.message}", t)
                    if (!resumed) {
                        resumed = true
                        if (continuation.isActive) continuation.resume(false)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WS closing code=$code reason='$reason' turned=$turned audioSize=${audioBuf.size()}")
                    try { webSocket.close(1000, null) } catch (_: Exception) {}
                    if (!resumed) {
                        val hasAudio = audioBuf.size() > 0
                        finish(null, success = hasAudio, reason = "onClosing")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WS closed code=$code reason='$reason' turned=$turned audioSize=${audioBuf.size()}")
                    if (!resumed) {
                        val hasAudio = audioBuf.size() > 0
                        finish(null, success = hasAudio, reason = "onClosed")
                    }
                }
            })

            continuation.invokeOnCancellation {
                ws.cancel()
            }
        }
    }

    /** Извлекает значение Path: из заголовка фрейма. */
    private fun extractPath(header: String): String? {
        val marker = "Path:"
        val idx = header.indexOf(marker)
        if (idx < 0) return null
        val start = idx + marker.length
        var end = start
        while (end < header.length) {
            val c = header[end]
            if (c == '\r' || c == '\n') break
            end++
        }
        return header.substring(start, end).trim().ifEmpty { null }
    }

    // ─── Построение WebSocket сообщений ──────────────────────────────────────

    private fun buildConfigMsg(timestamp: String): String {
        val header = "X-Timestamp:$timestamp\r\n" +
                     "Content-Type:application/json; charset=utf-8\r\n" +
                     "Path:speech.config\r\n\r\n"
        val body = """{"context":{"synthesis":{"audio":{"metadataoptions":""" +
                   """{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
                   """"outputFormat":"$OUTPUT_FORMAT"}}}}"""
        return header + body
    }

    private fun buildSsmlMsg(requestId: String, timestamp: String, text: String): String {
        val header = "X-RequestId:$requestId\r\n" +
                     "Content-Type:application/ssml+xml\r\n" +
                     "X-Timestamp:$timestamp\r\n" +
                     "Path:ssml\r\n\r\n"
        return header + buildSsml(text)
    }

    /**
     * SSML в формате rany2/edge-tts: одна строка, одинарные кавычки, xml:lang='en-US',
     * атрибуты в порядке pitch → rate → volume.
     */
    private fun buildSsml(text: String): String {
        val rateStr  = formatRatePct(ratePct)
        val pitchStr = formatPitchHz(pitchHz)
        val escaped  = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
               "<voice name='$voice'>" +
               "<prosody pitch='$pitchStr' rate='$rateStr' volume='+0%'>" +
               escaped +
               "</prosody></voice></speak>"
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

        val sentences = text.split(Regex("(?<=[.!?…])\\s+"))

        for (sentence in sentences) {
            if (current.length + sentence.length + 1 > maxChars) {
                if (current.isNotEmpty()) {
                    parts.add(current.toString().trim())
                    current.clear()
                }
                if (sentence.length > maxChars) {
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
