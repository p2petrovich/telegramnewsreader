package com.example.telegramnewsreader.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.telegramnewsreader.databinding.ActivityAuthBinding
import com.example.telegramnewsreader.telegram.TelegramClient
import com.example.telegramnewsreader.utils.PreferenceManager

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var telegramClient: TelegramClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация TelegramClient
        telegramClient = TelegramClient(this)

        setupClickListeners()
    }

    private fun setupClickListeners() {

        // Обработчик кнопки отправки номера телефона
        binding.btnSendCode.setOnClickListener {
            val phone = binding.etPhone.text.toString().trim()
            if (phone.isNotEmpty()) {
                binding.progressBar.visibility = View.VISIBLE
                binding.tvError.visibility = View.GONE
                telegramClient.sendCode(phone) { success ->
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        if (success) {
                            Toast.makeText(this, "Code sent successfully", Toast.LENGTH_SHORT).show()
                            binding.etCode.visibility = View.VISIBLE
                            binding.btnVerify.visibility = View.VISIBLE
                            binding.etCode.isEnabled = true
                            binding.btnVerify.isEnabled = true
                        } else {
                            binding.tvError.text = "Failed to send code"
                            binding.tvError.visibility = View.VISIBLE
                            Toast.makeText(this, "Failed to send code", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                binding.tvError.text = "Please enter a phone number"
                binding.tvError.visibility = View.VISIBLE
                Toast.makeText(this, "Please enter a phone number", Toast.LENGTH_SHORT).show()
            }
        }

        // Обработчик кнопки верификации кода
        binding.btnVerify.setOnClickListener {
            val code = binding.etCode.text.toString().trim()
            if (code.isNotEmpty()) {
                binding.progressBar.visibility = View.VISIBLE
                binding.tvError.visibility = View.GONE
                telegramClient.verifyCode(code) { success ->
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        if (success) {
                            Toast.makeText(this, "Verification successful", Toast.LENGTH_SHORT).show()

                            // Сохраняем статус авторизации
                            PreferenceManager.setAuthorized(this, true)

                            // Переход к главному экрану
                            val intent = Intent(this, MainActivity::class.java)
                            startActivity(intent)
                            finish() // Закрываем активити авторизации
                        } else {
                            binding.tvError.text = "Verification failed"
                            binding.tvError.visibility = View.VISIBLE
                            Toast.makeText(this, "Verification failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                binding.tvError.text = "Please enter the verification code"
                binding.tvError.visibility = View.VISIBLE
                Toast.makeText(this, "Please enter the verification code", Toast.LENGTH_SHORT).show()
            }
        }
    }
}