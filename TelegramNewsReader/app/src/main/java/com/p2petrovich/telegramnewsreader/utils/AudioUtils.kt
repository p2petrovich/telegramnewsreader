package com.p2petrovich.telegramnewsreader.utils

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

object AudioUtils {

    fun concatWavFiles(parts: List<File>, output: File): Boolean {
        if (parts.isEmpty()) return false

        try {
            // 🔧 Создаем список .wav в виде файла input.txt для FFmpeg
            val concatFile = File(output.parentFile, "concat_input.txt")
            concatFile.writeText(parts.joinToString("\n") { "file '${it.absolutePath}'" })

            val cmd = arrayOf(
                "-f", "concat",
                "-safe", "0",
                "-i", concatFile.absolutePath,
                "-c", "copy",
                output.absolutePath
            )

            val session = FFmpegKit.executeWithArguments(cmd)

            val success = ReturnCode.isSuccess(session.returnCode)
            if (success) {
                Log.d("AudioUtils", "✅ Объединение WAV успешно: ${output.name}")
            } else {
                Log.e("AudioUtils", "❌ Ошибка объединения WAV: ${session.returnCode}")
            }

            concatFile.delete()
            return success
        } catch (e: Exception) {
            Log.e("AudioUtils", "❌ Исключение при объединении WAV", e)
            return false
        }
    }
}
