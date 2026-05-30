package com.p2petrovich.telegramnewsreader.utils

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

object AudioUtils {

    private const val TAG = "AudioUtils"

    fun concatWavFiles(parts: List<File>, output: File): Boolean {
        if (parts.isEmpty()) return false

        return try {
            val concatFile = File(output.parentFile, "concat_input.txt")
            concatFile.writeText(parts.joinToString("\n") { "file '${it.absolutePath}'" })

            // Используем перекодирование pcm_s16le вместо прямого копирования байтов.
            // Это гарантирует стабильность при разных структурах заголовков WAV.
            val cmd = arrayOf(
                "-y", "-f", "concat", "-safe", "0",
                "-i", concatFile.absolutePath,
                "-c:a", "pcm_s16le", "-ar", "24000", "-ac", "1",
                output.absolutePath
            )

            val session = FFmpegKit.executeWithArguments(cmd)
            val success = ReturnCode.isSuccess(session.returnCode)

            if (success) {
                Log.d(TAG, "WAV concat success: ${output.name}")
            } else {
                Log.e(TAG, "WAV concat failed: ${session.returnCode}")
            }

            concatFile.delete()
            success
        } catch (e: Exception) {
            Log.e(TAG, "WAV concat exception", e)
            false
        }
    }
}
