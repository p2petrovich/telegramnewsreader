package com.p2petrovich.telegramnewsreader.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.p2petrovich.telegramnewsreader.databinding.ActivityAuthBinding
import com.p2petrovich.telegramnewsreader.telegram.TelegramClient
import com.p2petrovich.telegramnewsreader.telegram.TelegramClientManager
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var telegramClient: TelegramClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        telegramClient = TelegramClientManager.getTelegramClient(this)

        // 🔐 Обработка ожидания облачного пароля
        telegramClient.onPasswordRequired = {
            runOnUiThread {
                binding.etPassword.visibility = View.VISIBLE
                binding.btnVerifyPassword.visibility = View.VISIBLE
                Toast.makeText(this, "Введите облачный пароль", Toast.LENGTH_SHORT).show()
            }
        }

        // ✅ Переход в MainActivity только после полной авторизации
        telegramClient.onClientReady = {
            runOnUiThread {
                Toast.makeText(this, "Авторизация успешна", Toast.LENGTH_SHORT).show()
                PreferenceManager.setAuthorized(this, true)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }

        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.btnSendCode.setOnClickListener {
            val phone = binding.etPhone.text.toString().trim()
            if (phone.isNotEmpty()) {
                binding.progressBar.visibility = View.VISIBLE
                binding.tvError.visibility = View.GONE

                telegramClient.sendCode(phone) { success ->
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        if (success) {
                            Toast.makeText(this, "Код отправлен", Toast.LENGTH_SHORT).show()
                            binding.etCode.visibility = View.VISIBLE
                            binding.btnVerify.visibility = View.VISIBLE
                            binding.etCode.isEnabled = true
                            binding.btnVerify.isEnabled = true
                        } else {
                            binding.tvError.text = "Не удалось отправить код"
                            binding.tvError.visibility = View.VISIBLE
                        }
                    }
                }
            } else {
                binding.tvError.text = "Введите номер телефона"
                binding.tvError.visibility = View.VISIBLE
            }
        }

        binding.btnVerify.setOnClickListener {
            val code = binding.etCode.text.toString().trim()
            if (code.isNotEmpty()) {
                binding.progressBar.visibility = View.VISIBLE
                binding.tvError.visibility = View.GONE

                telegramClient.verifyCode(code) { success ->
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        if (success) {
                            Toast.makeText(this, "Код принят. Ожидание авторизации...", Toast.LENGTH_SHORT).show()
                            // ❗️ Переход в MainActivity будет выполнен через onClientReady
                        } else {
                            binding.tvError.text = "Неверный код"
                            binding.tvError.visibility = View.VISIBLE
                        }
                    }
                }
            } else {
                binding.tvError.text = "Введите код из SMS"
                binding.tvError.visibility = View.VISIBLE
            }
        }

        binding.btnVerifyPassword.setOnClickListener {
            val password = binding.etPassword.text.toString().trim()
            if (password.isEmpty()) {
                Toast.makeText(this, "Введите пароль", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.progressBar.visibility = View.VISIBLE
            binding.tvError.visibility = View.GONE

            telegramClient.verifyPassword(password) { success ->
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    if (success) {
                        Toast.makeText(this, "Авторизация завершена", Toast.LENGTH_SHORT).show()
                        // ✅ Переход в MainActivity выполнится через onClientReady
                    } else {
                        binding.tvError.text = "Неверный пароль"
                        binding.tvError.visibility = View.VISIBLE
                        Toast.makeText(this, "Ошибка при проверке пароля", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
