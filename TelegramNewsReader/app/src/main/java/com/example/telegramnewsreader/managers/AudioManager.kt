package com.example.telegramnewsreader.managers

import java.io.File

class AudioManager {
    fun createMp3File(path: String, content: ByteArray) {
        val file = File(path)
        file.writeBytes(content)
    }

    fun deleteAudioFile(path: String) {
        File(path).delete()
    }
}
