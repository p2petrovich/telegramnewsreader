package com.p2petrovich.telegramnewsreader.tts

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.p2petrovich.telegramnewsreader.ApiConfig
import com.p2petrovich.telegramnewsreader.utils.EdgeConfig
import com.p2petrovich.telegramnewsreader.utils.HttpClients
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
 *
 * Алгоритм Sec-MS-GEC и SSML — повторяют эталон rany2/edge-tts.
 *
 * Важно: в новой версии протокола Microsoft turn.end может приходить как бинарный фрейм
 * (с заголовком Path:turn.end), а не как текстовый. Бинарный обработчик это учитывает.
 */
class EdgeTtsProvider(
    private val context: Context,
    val voice: String = VOICE_DMITRY,
    val ratePct: Int = 0,   // скорость: -50..+100 (%)
    val pitchHz: Int = 0    // тон: -200..+200 (Hz)
) {

    companion object {
        private const val TAG = "EdgeTtsProvider"

        // Russian voices
        const val VOICE_DMITRY   = "ru-RU-DmitryNeural"
        const val VOICE_SVETLANA = "ru-RU-SvetlanaNeural"

        // English (US) voices
        const val VOICE_GUY         = "en-US-GuyNeural"
        const val VOICE_ARIA        = "en-US-AriaNeural"
        const val VOICE_JENNY       = "en-US-JennyNeural"
        const val VOICE_ERIC        = "en-US-EricNeural"
        const val VOICE_DAVIS       = "en-US-DavisNeural"
        const val VOICE_JANE        = "en-US-JaneNeural"
        const val VOICE_JASON       = "en-US-JasonNeural"
        const val VOICE_SARA        = "en-US-SaraNeural"
        const val VOICE_TONY        = "en-US-TonyNeural"
        const val VOICE_NANCY       = "en-US-NancyNeural"
        const val VOICE_AMBER       = "en-US-AmberNeural"
        const val VOICE_ANA         = "en-US-AnaNeural"
        const val VOICE_ASHLEY      = "en-US-AshleyNeural"
        const val VOICE_BRANDON     = "en-US-BrandonNeural"
        const val VOICE_CHRISTOPHER = "en-US-ChristopherNeural"
        const val VOICE_CORA        = "en-US-CoraNeural"
        const val VOICE_ELIZABETH   = "en-US-ElizabethNeural"
        const val VOICE_JACOB       = "en-US-JacobNeural"
        const val VOICE_MICHELLE    = "en-US-MichelleNeural"
        const val VOICE_MONICA      = "en-US-MonicaNeural"
        const val VOICE_ROGER       = "en-US-RogerNeural"
        const val VOICE_RYAN        = "en-US-RyanMultilingualNeural"
        const val VOICE_STEFFAN     = "en-US-SteffanNeural"

        // English (GB) voices
        const val VOICE_LIBBY    = "en-GB-LibbyNeural"
        const val VOICE_MAISIE   = "en-GB-MaisieNeural"
        const val VOICE_RYAN_GB  = "en-GB-RyanNeural"
        const val VOICE_SONIA    = "en-GB-SoniaNeural"
        const val VOICE_THOMAS   = "en-GB-ThomasNeural"

        private const val TOKEN = ApiConfig.EDGE_TOKEN
        private const val WS_BASE = ApiConfig.EDGE_WS_BASE

        private const val WIN_EPOCH              = 11_644_473_600L

        private const val MAX_CHARS = 3000

        private val sharedClient = HttpClients.shared

        fun formatRatePct(rate: Int): String = if (rate >= 0) "+${rate}%" else "${rate}%"
        fun formatPitchHz(pitch: Int): String = if (pitch >= 0) "+${pitch}Hz" else "${pitch}Hz"
    }

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
            Log.e(TAG, "synthesizeToWav overall timeout (length: ${text.length})")
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

    /**
     * Генерирует Sec-MS-GEC токен.
     *
     * Алгоритм (1-в-1 с rany2/edge-tts drm.py):
     *  1. Unix timestamp (секунды, double)
     *  2. + WIN_EPOCH (переход к Windows file time, 1601-01-01)
     *  3. округление вниз до 5 минут (% 300)
     *  4. * 1e9 / 100 — перевод в 100-нс интервалы
     *  5. форматирование как целое
     *  6. SHA-256(ticksStr + TRUSTED_CLIENT_TOKEN) → uppercase hex
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

    // ─── Синтез одной части через WebSocket ──────────────────────────────────

    private suspend fun synthesizePart(text: String, outputFile: File): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val connectionId = uuid()
            val requestId    = uuid()
            val timestamp    = isoTimestamp()
            val secMsGec     = generateSecMsGec()
            
            val fullVer = EdgeConfig.fullVersion(context)
            val majorVer = EdgeConfig.majorVersion(context)

            val wsUrl = "$WS_BASE" +
                    "?TrustedClientToken=$TOKEN" +
                    "&Sec-MS-GEC=$secMsGec" +
                    "&Sec-MS-GEC-Version=1-$fullVer" +
                    "&ConnectionId=$connectionId"

            Log.d(TAG, "synthesizePart start, voice=$voice, length=${text.length}")

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
                    "Chrome/$majorVer.0.0.0 Safari/537.36 " +
                    "Edg/$majorVer.0.0.0")
                .build()

            val audioBuf = ByteArrayOutputStream()
            var turned   = false  // получили turn.end?
            var resumed  = false

            // Единая точка завершения с защитой от двойного вызова
            fun finish(ws: WebSocket?, success: Boolean, reason: String) {
                if (resumed) return
                resumed = true
                turned = true
                Log.d(TAG, "finish: success=$success, reason=$reason, audioSize=${audioBuf.size()}")
                try { ws?.close(1000, "done") } catch (_: Exception) {}
                val bytes = synchronized(audioBuf) { audioBuf.toByteArray() }
                val ok = if (success) writeMp3ToWav(bytes, outputFile) else false
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

                // Текстовые фреймы — служебные (turn.start / turn.end / response)
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val path = extractPath(text)
                    Log.d(TAG, "← text frame (path: $path)")
                    if (path == "turn.end") {
                        finish(webSocket, success = true, reason = "turn.end (text)")
                    }
                }

                // Бинарные фреймы — аудио ИЛИ служебные (turn.end иногда приходит как binary)
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
                            val isFirst = synchronized(audioBuf) { audioBuf.size() == 0 }
                            if (isFirst) Log.d(TAG, "← first audio frame, frameSize=${data.size}, headerLen=$headerLen")
                            val audioStart = 2 + headerLen
                            synchronized(audioBuf) {
                                audioBuf.write(data, audioStart, data.size - audioStart)
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
                    val code = response?.code
                    Log.e(TAG, "WS failure code=$code msg=${response?.message}", t)
                    if (code == 403) {
                        Log.w(TAG, "403 Forbidden received. Invalidating Chromium version.")
                        EdgeConfig.invalidate(context)
                    }
                    if (!resumed) {
                        resumed = true
                        if (continuation.isActive) continuation.resume(false)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WS closing code=$code reason='$reason' turned=$turned audioSize=${audioBuf.size()}")
                    // Подтверждаем закрытие со стороны клиента
                    try { webSocket.close(1000, null) } catch (_: Exception) {}
                    // Если уже получили аудио, но turn.end не пришёл — записываем что есть
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

    /**
     * Извлекает значение Path: из заголовка фрейма.
     * Заголовки приходят в формате "Header:value\r\nHeader2:value2\r\n\r\n".
     */
    private fun extractPath(header: String): String? {
        return header.lines().firstOrNull { it.startsWith("Path:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.ifEmpty { null }
    }

    // ─── Построение WebSocket сообщений ──────────────────────────────────────

    private fun buildConfigMsg(timestamp: String): String {
        val header = "X-Timestamp:$timestamp\r\n" +
                     "Content-Type:application/json; charset=utf-8\r\n" +
                     "Path:speech.config\r\n\r\n"
        val body = """{"context":{"synthesis":{"audio":{"metadataoptions":""" +
                   """{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
                   """"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
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

    // ─── WAV утилиты ─────────────────────────────────────────────────────────

    /**
     * Сохраняет MP3-байты во временный файл, конвертирует в WAV через FFmpegKit,
     * удаляет временный MP3. Выход: 24kHz / 16bit / mono — совместим с пайплайном.
     */
    private fun writeMp3ToWav(mp3Bytes: ByteArray, outputFile: File): Boolean {
        if (mp3Bytes.isEmpty()) {
            Log.e(TAG, "writeMp3ToWav: empty MP3 buffer for ${outputFile.name}")
            return false
        }
        val mp3Tmp = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_raw.mp3")
        return try {
            mp3Tmp.writeBytes(mp3Bytes)
            Log.d(TAG, "writeMp3ToWav: saved ${mp3Bytes.size} bytes to ${mp3Tmp.name}, converting...")

            val cmd = arrayOf(
                "-y",
                "-i", mp3Tmp.absolutePath,
                "-ar", "24000",
                "-ac", "1",
                "-sample_fmt", "s16",
                outputFile.absolutePath
            )
            val session = FFmpegKit.executeWithArguments(cmd)
            val ok = ReturnCode.isSuccess(session.returnCode) &&
                     outputFile.exists() && outputFile.length() > 44
            Log.d(TAG, "writeMp3ToWav: FFmpeg ok=$ok, outSize=${outputFile.length()}")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "writeMp3ToWav failed: ${e.message}")
            false
        } finally {
            try { mp3Tmp.delete() } catch (_: Exception) {}
        }
    }

    /**
     * Склейка нескольких WAV (одинаковый формат 24kHz/16bit/mono).
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
        var i = 12
        while (i + 8 <= wav.size) {
            val chunkId   = String(wav, i, 4, Charsets.US_ASCII)
            val chunkSize = ((wav[i+4].toInt() and 0xFF))       or
                            ((wav[i+5].toInt() and 0xFF) shl 8)  or
                            ((wav[i+6].toInt() and 0xFF) shl 16) or
                            ((wav[i+7].toInt() and 0xFF) shl 24)
            i += 8
            if (chunkId == "data") return i
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
        writeLE(16,           16, 4)
        writeLE(1,            20, 2)
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
