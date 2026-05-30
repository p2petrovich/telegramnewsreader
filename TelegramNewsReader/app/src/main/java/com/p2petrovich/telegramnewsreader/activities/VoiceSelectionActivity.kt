package com.p2petrovich.telegramnewsreader.activities

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.adapters.VoiceAdapter
import com.p2petrovich.telegramnewsreader.tts.EdgeTtsProvider
import com.p2petrovich.telegramnewsreader.tts.TTSManagerSingleton
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import com.p2petrovich.telegramnewsreader.models.VoiceEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VoiceSelectionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var voiceAdapter: VoiceAdapter
    private var pendingVoiceRestore: Runnable? = null

    // Edge TTS UI
    private lateinit var rgTtsEngine: RadioGroup
    private lateinit var rbAndroid: RadioButton
    private lateinit var rbEdge: RadioButton
    private lateinit var layoutEdgeSettings: LinearLayout
    private lateinit var layoutAndroidSettings: LinearLayout
    private lateinit var spinnerEdgeVoice: Spinner
    private lateinit var sbEdgeRate: SeekBar
    private lateinit var tvEdgeRate: TextView
    private lateinit var sbEdgePitch: SeekBar
    private lateinit var tvEdgePitch: TextView
    private lateinit var btnTestEdge: Button

    private val edgeVoices = listOf(
        EdgeTtsProvider.VOICE_DMITRY   to "Dmitry — мужской (рекомендуется)",
        EdgeTtsProvider.VOICE_SVETLANA to "Svetlana — женский"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_voice_selection)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        supportActionBar?.apply {
            title = "Голос и речь"
            setDisplayHomeAsUpEnabled(true)
        }

        recyclerView = findViewById(R.id.recyclerVoices)
        recyclerView.layoutManager = LinearLayoutManager(this)

        bindEdgeViews()
        setupEngineSelector()
        loadVoices()
    }

    // ── Инициализация Edge UI ─────────────────────────────────────────────────

    private fun bindEdgeViews() {
        rgTtsEngine          = findViewById(R.id.rgTtsEngine)
        rbAndroid            = findViewById(R.id.rbAndroid)
        rbEdge               = findViewById(R.id.rbEdge)
        layoutEdgeSettings   = findViewById(R.id.layoutEdgeSettings)
        layoutAndroidSettings = findViewById(R.id.layoutAndroidSettings)
        spinnerEdgeVoice     = findViewById(R.id.spinnerEdgeVoice)
        sbEdgeRate           = findViewById(R.id.sbEdgeRate)
        tvEdgeRate           = findViewById(R.id.tvEdgeRate)
        sbEdgePitch          = findViewById(R.id.sbEdgePitch)
        tvEdgePitch          = findViewById(R.id.tvEdgePitch)
        btnTestEdge          = findViewById(R.id.btnTestEdge)
    }

    private fun setupEngineSelector() {
        val ttsManager = TTSManagerSingleton.getInstance(this)

        // ── RadioGroup: выбор движка ──
        val savedEngine = PreferenceManager.getTtsEngine(this)
        if (savedEngine == "edge") rbEdge.isChecked = true else rbAndroid.isChecked = true
        applyEngineVisibility(savedEngine)

        rgTtsEngine.setOnCheckedChangeListener { _, checkedId ->
            val engine = if (checkedId == R.id.rbEdge) "edge" else "android"
            PreferenceManager.saveTtsEngine(this, engine)
            applyEngineVisibility(engine)
            ttsManager.refreshEdgeProvider()
            val label = if (engine == "edge") "Edge TTS включён" else "Android TTS включён"
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }

        // ── Spinner голоса Edge ──
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            edgeVoices.map { it.second }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerEdgeVoice.adapter = spinnerAdapter

        val savedVoice = PreferenceManager.getEdgeVoice(this)
        val voiceIndex = edgeVoices.indexOfFirst { it.first == savedVoice }.coerceAtLeast(0)
        spinnerEdgeVoice.setSelection(voiceIndex)

        spinnerEdgeVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                PreferenceManager.saveEdgeVoice(this@VoiceSelectionActivity, edgeVoices[pos].first)
                ttsManager.refreshEdgeProvider()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ── SeekBar скорости Edge ──
        val savedRate = PreferenceManager.getEdgeRate(this)
        sbEdgeRate.max      = 150
        sbEdgeRate.progress = savedRate + 50
        tvEdgeRate.text     = EdgeTtsProvider.formatRatePct(savedRate)

        sbEdgeRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val rate = progress - 50
                tvEdgeRate.text = EdgeTtsProvider.formatRatePct(rate)
                if (fromUser) PreferenceManager.saveEdgeRate(this@VoiceSelectionActivity, rate)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                ttsManager.refreshEdgeProvider()
            }
        })

        // ── SeekBar тона Edge ──
        val savedPitch = PreferenceManager.getEdgePitch(this)
        sbEdgePitch.max      = 400
        sbEdgePitch.progress = savedPitch + 200
        tvEdgePitch.text     = EdgeTtsProvider.formatPitchHz(savedPitch)

        sbEdgePitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val pitch = progress - 200
                tvEdgePitch.text = EdgeTtsProvider.formatPitchHz(pitch)
                if (fromUser) PreferenceManager.saveEdgePitch(this@VoiceSelectionActivity, pitch)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                ttsManager.refreshEdgeProvider()
            }
        })

        // ── Кнопка теста Edge ──
        btnTestEdge.setOnClickListener {
            val voice = edgeVoices[spinnerEdgeVoice.selectedItemPosition].first
            val rate  = sbEdgeRate.progress - 50
            testEdgeVoice(voice, rate)
        }
    }

    private fun applyEngineVisibility(engine: String) {
        if (engine == "edge") {
            layoutEdgeSettings.visibility    = View.VISIBLE
            layoutAndroidSettings.visibility = View.VISIBLE // Оставляем список Android видимым
        } else {
            layoutEdgeSettings.visibility    = View.GONE
            layoutAndroidSettings.visibility = View.VISIBLE
        }
    }

    // Удален дубликат formatRatePct, используется EdgeTtsProvider.formatRatePct

    // ── Тест Edge голоса (синтез в фоне + воспроизведение через Android MediaPlayer) ──

    private fun testEdgeVoice(voice: String, rate: Int) {
        btnTestEdge.isEnabled = false
        btnTestEdge.text      = "⏳ Синтезирую..."
        val pitch = sbEdgePitch.progress - 200

        CoroutineScope(Dispatchers.IO).launch {
            val provider = EdgeTtsProvider(context = this@VoiceSelectionActivity, voice = voice, ratePct = rate, pitchHz = pitch)
            val outFile  = File(cacheDir, "edge_test_preview.wav")
            val ok = provider.synthesizeToWav(
                "Привет! Это голос Edge TTS. Качество звучания Microsoft Neural.", outFile
            )
            withContext(Dispatchers.Main) {
                btnTestEdge.isEnabled = true
                btnTestEdge.text      = "▶ Тест Edge голоса"
                if (ok && outFile.exists()) {
                    playPreviewWav(outFile)
                } else {
                    Toast.makeText(
                        this@VoiceSelectionActivity,
                        "Не удалось синтезировать — проверьте интернет",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun playPreviewWav(file: File) {
        var mp: android.media.MediaPlayer? = null
        try {
            mp = android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener { release() }
                setOnErrorListener { _, _, _ ->
                    release()
                    true
                }
            }
        } catch (e: Exception) {
            Log.e("VoiceSelection", "Preview playback failed: ${e.message}")
            mp?.release()
        }
    }

    // ── Android TTS список ────────────────────────────────────────────────────

    private fun loadVoices() {
        CoroutineScope(Dispatchers.Main).launch {
            val ttsManager = TTSManagerSingleton.getInstance(this@VoiceSelectionActivity)
            
            // Ждем инициализации Android TTS, иначе список voices будет неполным
            val isReady = ttsManager.waitInit()
            if (!isReady) {
                Log.e("VoiceSelection", "TTS initialization timed out")
            }

            val allVoiceEntries = ttsManager.getAvailableVoiceEntries()
            val russianVoices = allVoiceEntries.filter {
                it.language == "ru" || it.language.startsWith("ru", ignoreCase = true)
            }

            if (russianVoices.isEmpty()) {
                if (isReady) {
                    Toast.makeText(this@VoiceSelectionActivity, "Русские голоса TTS не найдены", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val savedVoiceName = PreferenceManager.getTtsVoiceName(this@VoiceSelectionActivity)
            val savedVoiceIsRussian = russianVoices.any { it.systemName == savedVoiceName }

            val currentSelectedVoice = if (savedVoiceIsRussian) {
                savedVoiceName
            } else {
                russianVoices.firstOrNull()?.systemName.also {
                    if (it != null) PreferenceManager.saveTtsVoiceName(this@VoiceSelectionActivity, it)
                }
            }

            voiceAdapter = VoiceAdapter(
                voiceEntries = russianVoices,
                selectedVoiceName = currentSelectedVoice,
                onVoiceSelected = { voiceEntry -> onVoiceSelected(voiceEntry) },
                onVoicePlay = { voiceEntry -> onVoicePlay(voiceEntry) }
            )

            recyclerView.adapter = voiceAdapter
        }
    }

    private fun onVoiceSelected(voiceEntry: VoiceEntry) {
        val ttsManager = TTSManagerSingleton.getInstance(this)
        ttsManager.setVoiceByEntry(voiceEntry)
        ttsManager.applyVoiceSettings(voiceEntry.systemName)
        PreferenceManager.saveTtsVoiceName(this, voiceEntry.systemName)
        Toast.makeText(this, "Голос изменён: ${voiceEntry.displayName}", Toast.LENGTH_SHORT).show()
    }

    private fun onVoicePlay(voiceEntry: VoiceEntry) {
        val ttsManager = TTSManagerSingleton.getInstance(this)
        val currentVoice = PreferenceManager.getTtsVoiceName(this)

        pendingVoiceRestore?.let { recyclerView.removeCallbacks(it) }

        ttsManager.setVoiceByEntry(voiceEntry)
        ttsManager.applyVoiceSettings(voiceEntry.systemName)
        ttsManager.speak("Привет! Это голос ${voiceEntry.displayName}. Как вам качество звучания?")

        if (currentVoice != null && currentVoice != voiceEntry.systemName) {
            pendingVoiceRestore = Runnable {
                ttsManager.setVoiceByName(currentVoice)
                ttsManager.applyVoiceSettings(currentVoice)
            }
            recyclerView.postDelayed(pendingVoiceRestore!!, 3000)
        }

        Toast.makeText(this, "Тестирую: ${voiceEntry.displayName}", Toast.LENGTH_SHORT).show()
    }

    // ── Навигация / lifecycle ─────────────────────────────────────────────────

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingVoiceRestore?.let { recyclerView.removeCallbacks(it) }
        pendingVoiceRestore = null
    }
}
