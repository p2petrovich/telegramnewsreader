package com.p2petrovich.telegramnewsreader.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.p2petrovich.telegramnewsreader.databinding.ActivityPlayerBinding
import com.p2petrovich.telegramnewsreader.services.AudioPlayerService

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Новости"
        binding.tvCurrentChannel.text = "Текущий канал: $title"
        binding.seekBar.isEnabled = false
        binding.tvTimeLabels.text = "0:00 / 0:00"
        binding.tvError.visibility = View.GONE

        binding.btnPlay.setOnClickListener {
            startService(Intent(this, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_PLAY))
        }
        binding.btnPause.setOnClickListener {
            startService(Intent(this, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_PAUSE))
        }
        binding.btnStop.setOnClickListener {
            startService(Intent(this, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_STOP))
        }
    }

    companion object {
        const val EXTRA_TITLE = "player_activity.EXTRA_TITLE"
    }
}
