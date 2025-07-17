package com.example.telegramnewsreader.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.telegramnewsreader.R
import com.example.telegramnewsreader.telegram.TelegramClient

class AuthActivity : AppCompatActivity() {

    private lateinit var telegramClient: TelegramClient
    private lateinit var phoneInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var sendCodeButton: Button
    private lateinit var verifyCodeButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var errorTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        // Инициализация TelegramClient
        telegramClient = TelegramClient(this)

        // Инициализация UI-элементов
        phoneInput = findViewById(R.id.et_phone)
        codeInput = findViewById(R.id.et_code)
        sendCodeButton = findViewById(R.id.btn_send_code)
        verifyCodeButton = findViewById(R.id.btn_verify)
        progressBar = findViewById(R.id.progress_bar)
        errorTextView = findViewById(R.id.tv_error)

        // Обработчик кнопки отправки номера телефона
        sendCodeButton.setOnClickListener {
            val phone = phoneInput.text.toString().trim()
            if (phone.isNotEmpty()) {
                progressBar.visibility = View.VISIBLE
                errorTextView.visibility = View.GONE
                telegramClient.sendCode(phone) { success ->
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        if (success) {
                            Toast.makeText(this, "Code sent successfully", Toast.LENGTH_SHORT).show()
                            codeInput.visibility = View.VISIBLE
                            verifyCodeButton.visibility = View.VISIBLE
                            codeInput.isEnabled = true
                            verifyCodeButton.isEnabled = true
                        } else {
                            errorTextView.text = "Failed to send code"
                            errorTextView.visibility = View.VISIBLE
                            Toast.makeText(this, "Failed to send code", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                errorTextView.text = "Please enter a phone number"
                errorTextView.visibility = View.VISIBLE
                Toast.makeText(this, "Please enter a phone number", Toast.LENGTH_SHORT).show()
            }
        }

        // Обработчик кнопки верификации кода
        verifyCodeButton.setOnClickListener {
            val code = codeInput.text.toString().trim()
            if (code.isNotEmpty()) {
                progressBar.visibility = View.VISIBLE
                errorTextView.visibility = View.GONE
                telegramClient.verifyCode(code) { success ->
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        if (success) {
                            Toast.makeText(this, "Verification successful", Toast.LENGTH_SHORT).show()
                            // Переход к следующей активности, например, MainActivity
                            // startActivity(Intent(this, MainActivity::class.java))
                            // finish()
                        } else {
                            errorTextView.text = "Verification failed"
                            errorTextView.visibility = View.VISIBLE
                            Toast.makeText(this, "Verification failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                errorTextView.text = "Please enter the verification code"
                errorTextView.visibility = View.VISIBLE
                Toast.makeText(this, "Please enter the verification code", Toast.LENGTH_SHORT).show()
            }
        }
    }
}