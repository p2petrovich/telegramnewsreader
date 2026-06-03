package com.p2petrovich.telegramnewsreader.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.view.autofill.AutofillManager
import androidx.appcompat.app.AlertDialog
import com.p2petrovich.telegramnewsreader.R
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
                Toast.makeText(this, getString(R.string.enter_cloud_password), Toast.LENGTH_SHORT).show()
            }
        }

        telegramClient.onClientReady = {
            runOnUiThread {
                Toast.makeText(this, getString(R.string.auth_success), Toast.LENGTH_SHORT).show()
                PreferenceManager.setAuthorized(this, true)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }

        telegramClient.onFatalError = { message ->
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(R.string.security_error)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.exit) { _, _ -> finish() }
                    .show()
            }
        }

        setupClickListeners()
        
        // Настройка автозаполнения для Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.etPhone.setAutofillHints(View.AUTOFILL_HINT_PHONE)
            // SMS_OTP доступен с API 28+, для старых используем общую подсказку
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                binding.etCode.setAutofillHints("smsOTP")
            }
            binding.etPassword.setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
            
            // Принудительный запрос при фокусе
            binding.etPhone.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.context.getSystemService(AutofillManager::class.java)?.requestAutofill(v)
                }
            }
            binding.etPassword.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.context.getSystemService(AutofillManager::class.java)?.requestAutofill(v)
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnSendCode.setOnClickListener {
            var phone = binding.etPhone.text.toString().trim()
            if (phone.isEmpty()) {
                showError(getString(R.string.enter_phone_error))
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
                        Toast.makeText(this, getString(R.string.code_sent), Toast.LENGTH_SHORT).show()
                        binding.etCode.visibility = View.VISIBLE
                        binding.btnVerify.visibility = View.VISIBLE
                    } else {
                        showError(getString(R.string.code_send_error))
                    }
                }
            }
        }

        binding.btnVerify.setOnClickListener {
            val code = binding.etCode.text.toString().trim()
            if (code.isEmpty()) {
                showError(getString(R.string.enter_code_error))
                return@setOnClickListener
            }

            showLoading(true)
            telegramClient.checkAuthenticationCode(code) { success, message ->
                runOnUiThread {
                    showLoading(false)
                    if (success) {
                        Toast.makeText(this, getString(R.string.code_accepted), Toast.LENGTH_SHORT).show()
                    } else {
                        showError(getString(R.string.invalid_code))
                    }
                }
            }
        }

        binding.btnVerifyPassword.setOnClickListener {
            val password = binding.etPassword.text.toString().trim()
            if (password.isEmpty()) {
                Toast.makeText(this, getString(R.string.enter_password), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            showLoading(true)
            telegramClient.checkAuthenticationPassword(password) { success, message ->
                runOnUiThread {
                    showLoading(false)
                    if (!success) {
                        showError(getString(R.string.invalid_password))
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