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

        telegramClient.onPasswordRequired = {
            runOnUiThread {
                binding.etPassword.visibility = View.VISIBLE
                binding.btnVerifyPassword.visibility = View.VISIBLE
                Toast.makeText(this, "Введите облачный пароль", Toast.LENGTH_SHORT).show()
            }
        }

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
            var phone = binding.etPhone.text.toString().trim()
            if (phone.isEmpty()) {
                showError("Введите номер телефона")
                return@setOnClickListener
            }

            // Telegram требует номер в международном формате (начиная с +)
            if (!phone.startsWith("+")) {
                phone = "+$phone"
                binding.etPhone.setText(phone)
            }

            showLoading(true)
            telegramClient.setPhoneNumber(phone) { success ->
                runOnUiThread {
                    showLoading(false)
                    if (success) {
                        Toast.makeText(this, "Код отправлен", Toast.LENGTH_SHORT).show()
                        binding.etCode.visibility = View.VISIBLE
                        binding.btnVerify.visibility = View.VISIBLE
                    } else {
                        showError("Не удалось отправить код")
                    }
                }
            }
        }

        binding.btnVerify.setOnClickListener {
            val code = binding.etCode.text.toString().trim()
            if (code.isEmpty()) {
                showError("Введите код из SMS")
                return@setOnClickListener
            }

            showLoading(true)
            telegramClient.checkAuthenticationCode(code) { success, message ->
                runOnUiThread {
                    showLoading(false)
                    if (success) {
                        Toast.makeText(this, "Код принят. Ожидание авторизации...", Toast.LENGTH_SHORT).show()
                    } else {
                        showError("Неверный код")
                    }
                }
            }
        }

        binding.btnVerifyPassword.setOnClickListener {
            val password = binding.etPassword.text.toString().trim()
            if (password.isEmpty()) {
                Toast.makeText(this, "Введите пароль", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            showLoading(true)
            telegramClient.checkAuthenticationPassword(password) { success, message ->
                runOnUiThread {
                    showLoading(false)
                    if (!success) {
                        showError("Неверный пароль")
                    }
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.tvError.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }
}