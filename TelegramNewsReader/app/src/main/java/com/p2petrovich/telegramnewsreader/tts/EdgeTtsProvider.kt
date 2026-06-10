package com.p2petrovich.telegramnewsreader.tts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.util.Log
import com.p2petrovich.telegramnewsreader.ApiConfig
import com.p2petrovich.telegramnewsreader.utils.EdgeConfig
import com.p2petrovich.telegramnewsreader.utils.HttpClients
import com.p2petrovich.telegramnewsreader.utils.buildWavHeader
import com.p2petrovich.telegramnewsreader.utils.findDataChunkOffset
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
 * Выходной формат: MP3 (audio-24khz-48kbitrate-mono-mp3) — декодируется в WAV через MediaCodec.
 * Бесплатно, без API ключа.
 *
 * Алгоритм Sec-MS-GEC и SSML — повторяют эталон rany2/edge-tts.
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

        private val SSML_GEOMETRIC_PATTERN = Regex(
            "[\\u25A0-\\u25FF\\u2B00-\\u2BFF▪▫◻◼◽◾◦‣⁃•·∙▸▹►▻🔹🔸🔶🔷🔺🔻🟠🟡🟢🟣🟤🟥🟦🟧🟨🟩🟪🟫⬛⬜]"
        )
        private val SSML_VARIATION_SELECTOR_PATTERN = Regex("[\\uFE00-\\uFE0F\\u200D]")
        private val SSML_EMOJI_PATTERN = Regex("[\\p{So}\\p{Sk}]")
        private val SSML_CONTROL_PATTERN = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]")
        private val SSML_MULTI_SPACE_PATTERN = Regex("[ \\t]{2,}")
    }

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
            var turned   = false
            var resumed  = false

            fun finish(ws: WebSocket?, success: Boolean, reason: String) {
                if (resumed) return
                resumed = true
                turned = true
                Log.d(TAG, "finish: success=$success, reason=$reason, audioSize=${audioBuf.size()}")
                try { ws?.close(1000, "done") } catch (_: Exception) {}
                val bytes = synchronized(audioBuf) { audioBuf.toByteArray() }
                
                // Декодируем MP3 в WAV через MediaCodec
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

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val path = extractPath(text)
                    Log.d(TAG, "← text frame (path: $path)")
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

    private fun extractPath(header: String): String? {
        return header.lines().firstOrNull { it.startsWith("Path:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.ifEmpty { null }
    }

    private fun buildConfigMsg(timestamp: String): String {
        val header = "X-Timestamp:$timestamp\r\n" +
                     "Content-Type:application/json; charset=utf-8\r\n" +
                     "Path:speech.config\r\n\r\n"
        // Снова используем MP3, так как raw PCM не поддерживается через WebSocket
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

    private fun buildSsml(text: String): String {
        val rateStr  = formatRatePct(ratePct)
        val pitchStr = formatPitchHz(pitchHz)

        var safe = text
        safe = SSML_GEOMETRIC_PATTERN.replace(safe, " ")
        safe = SSML_VARIATION_SELECTOR_PATTERN.replace(safe, "")
        safe = SSML_EMOJI_PATTERN.replace(safe, " ")
        safe = SSML_CONTROL_PATTERN.replace(safe, "")
        safe = SSML_MULTI_SPACE_PATTERN.replace(safe, " ").trim()

        val escaped = safe
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

    // ─── Декодирование MP3 через MediaCodec ─────────────────────────────────

    private fun writeMp3ToWav(mp3Bytes: ByteArray, outputFile: File): Boolean {
        if (mp3Bytes.isEmpty()) {
            Log.e(TAG, "writeMp3ToWav: empty MP3 buffer for ${outputFile.name}")
            return false
        }
        return try {
            val pcm = decodeMp3ToPcm(mp3Bytes)
            if (pcm.isEmpty()) {
                Log.e(TAG, "writeMp3ToWav: MediaCodec returned empty PCM")
                return false
            }
            val header = buildWavHeader(
                pcmSize       = pcm.size,
                sampleRate    = 24000,
                channels      = 1,
                bitsPerSample = 16
            )
            FileOutputStream(outputFile).use { os ->
                os.write(header)
                os.write(pcm)
            }
            val ok = outputFile.exists() && outputFile.length() > 44
            Log.d(TAG, "writeMp3ToWav: wrote ${pcm.size} PCM bytes, ok=$ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "writeMp3ToWav failed: ${e.message}")
            false
        }
    }

    private fun decodeMp3ToPcm(mp3Bytes: ByteArray): ByteArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(object : android.media.MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                val remaining = (mp3Bytes.size - position).toInt()
                if (remaining <= 0) return -1
                val read = minOf(size, remaining)
                System.arraycopy(mp3Bytes, position.toInt(), buffer, offset, read)
                return read
            }
            override fun getSize() = mp3Bytes.size.toLong()
            override fun close() {}
        })

        var audioFormat: android.media.MediaFormat? = null
        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioFormat = fmt
                trackIndex = i
                break
            }
        }
        if (trackIndex < 0 || audioFormat == null) return ByteArray(0)
        extractor.selectTrack(trackIndex)

        val mime = audioFormat.getString(android.media.MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(audioFormat, null, null, 0)
        codec.start()

        val output = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawEOS = false

        while (true) {
            if (!sawEOS) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawEOS = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sampleSize,
                            extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                val buf = codec.getOutputBuffer(outIdx)!!
                val chunk = ByteArray(info.size)
                buf.get(chunk)
                output.write(chunk)
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            if (sawEOS && outIdx < 0) break
        }

        codec.stop()
        codec.release()
        extractor.release()
        return output.toByteArray()
    }

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

    private fun uuid() = UUID.randomUUID().toString().replace("-", "")

    private fun isoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

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
