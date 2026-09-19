package com.p2petrovich.telegramnewsreader.fragments

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.p2petrovich.telegramnewsreader.R
import com.p2petrovich.telegramnewsreader.utils.AiModelRepository
import com.p2petrovich.telegramnewsreader.utils.AiProcessor
import com.p2petrovich.telegramnewsreader.utils.CachedModel
import com.p2petrovich.telegramnewsreader.utils.Logx
import com.p2petrovich.telegramnewsreader.utils.ModelLoadResult
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ModelItem(
    val id: String?,
    val label: String,
    val isCustom: Boolean = false
)

class AiSettingsDialogFragment : DialogFragment() {

    private var onDismissListener: (() -> Unit)? = null
    private var modelsLoadingJob: Job? = null
    private var selectedProvider: String = ""
    private var modelsReady = false
    private val draftModels = mutableMapOf<String, String>()

    private lateinit var repository: AiModelRepository
    private lateinit var currentModels: MutableList<ModelItem>
    private lateinit var modelStatuses: MutableMap<String, String>
    private lateinit var modelAdapter: ArrayAdapter<ModelItem>

    private lateinit var spinnerModel: Spinner
    private lateinit var btnRefresh: ImageButton
    private lateinit var tvLoadStatus: TextView
    private lateinit var tilCustomModel: TextInputLayout
    private lateinit var etCustomModel: TextInputEditText

    private lateinit var tilOpenRouterKey: TextInputLayout
    private lateinit var etOpenRouterKey: TextInputEditText
    private lateinit var tilGroqKey: TextInputLayout
    private lateinit var etGroqKey: TextInputEditText
    private lateinit var tilGeminiKey: TextInputLayout
    private lateinit var etGeminiKey: TextInputEditText

    fun setOnDismissListener(listener: () -> Unit) {
        onDismissListener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        repository = AiModelRepository(activity)
        val inflater = activity.layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_ai_settings, null)

        val switchEnabled = dialogView.findViewById<MaterialSwitch>(R.id.switch_ai_enabled)
        val spinnerProvider = dialogView.findViewById<Spinner>(R.id.spinner_ai_provider)
        spinnerModel = dialogView.findViewById(R.id.spinner_ai_model)
        btnRefresh = dialogView.findViewById(R.id.btn_refresh_ai_models)
        tvLoadStatus = dialogView.findViewById(R.id.tv_ai_models_load_status)
        tilCustomModel = dialogView.findViewById(R.id.til_custom_model)
        etCustomModel = dialogView.findViewById(R.id.et_custom_model_id)
        val spinnerStyle = dialogView.findViewById<Spinner>(R.id.spinner_ai_style)
        val btnTestManual = dialogView.findViewById<Button>(R.id.btn_test_ai_model)
        val tvStatusManual = dialogView.findViewById<TextView>(R.id.tv_ai_test_status)

        btnTestManual?.visibility = View.VISIBLE
        tvStatusManual?.visibility = View.VISIBLE

        switchEnabled.isChecked = PreferenceManager.isAiSummaryEnabled(activity)

        tilOpenRouterKey = dialogView.findViewById(R.id.til_openrouter_key)
        etOpenRouterKey  = dialogView.findViewById(R.id.et_openrouter_key)
        tilGroqKey       = dialogView.findViewById(R.id.til_groq_key)
        etGroqKey        = dialogView.findViewById(R.id.et_groq_key)
        tilGeminiKey     = dialogView.findViewById(R.id.til_gemini_key)
        etGeminiKey      = dialogView.findViewById(R.id.et_gemini_key)

        etOpenRouterKey?.setText(PreferenceManager.getOpenRouterApiKey(activity))
        etGroqKey?.setText(PreferenceManager.getGroqApiKey(activity))
        etGeminiKey?.setText(PreferenceManager.getGeminiApiKey(activity))

        val initialProvider = PreferenceManager.getAiProvider(activity)
        selectedProvider = initialProvider
        PreferenceManager.getAiModelOrNull(activity)?.let { draftModels[initialProvider] = it }

        fun updateKeyFieldVisibility(provider: String) {
            tilOpenRouterKey?.visibility = if (provider == "openrouter") View.VISIBLE else View.GONE
            tilGroqKey?.visibility       = if (provider == "groq")  View.VISIBLE else View.GONE
            tilGeminiKey?.visibility     = if (provider == "gemini") View.VISIBLE else View.GONE
        }

        val providers = listOf(
            "openrouter" to "OpenRouter",
            "groq"       to "Groq",
            "gemini"     to "Google Gemini"
        )
        val providerAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, providers.map { it.second })
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerProvider.adapter = providerAdapter

        spinnerProvider.setSelection(providers.indexOfFirst { it.first == initialProvider }.coerceAtLeast(0))
        updateKeyFieldVisibility(initialProvider)

        currentModels = mutableListOf()
        modelStatuses = mutableMapOf()

        modelAdapter = object : ArrayAdapter<ModelItem>(
            activity, R.layout.item_model_status, currentModels
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return createViewFromResource(position, convertView, parent, R.layout.item_model_status)
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return createViewFromResource(position, convertView, parent, R.layout.item_model_status)
            }
            private fun createViewFromResource(position: Int, convertView: View?, parent: ViewGroup, resource: Int): View {
                val view = convertView ?: inflater.inflate(resource, parent, false)
                val item = if (position < count) getItem(position) else null
                val tvName = view.findViewById<TextView>(R.id.tv_model_name)
                val tvStatus = view.findViewById<TextView>(R.id.tv_model_status)

                tvName.text = item?.label ?: ""
                val status = if (item?.isCustom == true) "" else (modelStatuses[item?.id] ?: "")
                tvStatus.text = status

                when (status) {
                    "✓" -> tvStatus.setTextColor(Color.GREEN)
                    "✗" -> tvStatus.setTextColor(Color.RED)
                    else -> tvStatus.setTextColor(Color.GRAY)
                }
                return view
            }
        }
        spinnerModel.adapter = modelAdapter

        spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val item = currentModels.getOrNull(position)
                if (item?.isCustom == true) {
                    tilCustomModel.visibility = View.VISIBLE
                } else {
                    tilCustomModel.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newProvider = providers[position].first
                updateKeyFieldVisibility(newProvider)
                if (newProvider == selectedProvider && modelsLoadingJob != null) return
                if (modelsReady) rememberDraft(selectedProvider)
                selectedProvider = newProvider
                loadModels(newProvider, forceRefresh = false)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnRefresh.setOnClickListener {
            loadModels(selectedProvider, forceRefresh = true)
        }

        btnTestManual?.setOnClickListener {
            val selectedModel = resolveSelectedModelId() ?: return@setOnClickListener
            if (selectedModel.isBlank()) {
                if (tilCustomModel.visibility == View.VISIBLE) {
                    tilCustomModel.error = getString(R.string.ai_model_id_required)
                }
                return@setOnClickListener
            }
            tilCustomModel.error = null

            persistKey(selectedProvider)

            tvStatusManual?.visibility = View.VISIBLE
            tvStatusManual?.text = getString(R.string.proxy_status_checking)
            tvStatusManual?.setTextColor(Color.GRAY)

            lifecycleScope.launch {
                val result = AiProcessor.testModelAvailability(selectedProvider, selectedModel, activity)
                tvStatusManual?.text = if (result.first) getString(R.string.ai_model_available) else result.second
                tvStatusManual?.setTextColor(if (result.first) Color.GREEN else Color.RED)

                if (currentModels.any { it.id == selectedModel }) {
                    modelStatuses[selectedModel] = if (result.first) "✓" else "✗"
                    modelAdapter.notifyDataSetChanged()
                }
            }
        }

        val styles = listOf(
            "minimal" to getString(R.string.ai_style_minimal),
            "balanced" to getString(R.string.ai_style_balanced),
            "extreme" to getString(R.string.ai_style_extreme)
        )

        val styleAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, styles.map { it.second })
        styleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerStyle.adapter = styleAdapter

        val currentStyle = PreferenceManager.getAiStyle(activity)
        val styleIdx = styles.indexOfFirst { it.first == currentStyle }.coerceAtLeast(0)
        spinnerStyle.setSelection(styleIdx)

        if (modelsLoadingJob == null) {
            loadModels(selectedProvider, forceRefresh = false)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel) { _, _ -> onDismissListener?.invoke() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val modelId = resolveSelectedModelId()
                if (modelId.isNullOrBlank()) {
                    tilCustomModel.visibility = View.VISIBLE
                    tilCustomModel.error = getString(R.string.ai_model_id_required)
                    return@setOnClickListener
                }
                tilCustomModel.error = null

                PreferenceManager.saveOpenRouterApiKey(activity, etOpenRouterKey?.text?.toString()?.trim() ?: "")
                PreferenceManager.saveGroqApiKey(activity, etGroqKey?.text?.toString()?.trim() ?: "")
                PreferenceManager.saveGeminiApiKey(activity, etGeminiKey?.text?.toString()?.trim() ?: "")
                PreferenceManager.setAiSummaryEnabled(activity, switchEnabled.isChecked)
                PreferenceManager.setAiProvider(activity, selectedProvider)
                PreferenceManager.setAiModel(activity, modelId)

                val stylePos = spinnerStyle.selectedItemPosition
                if (stylePos >= 0 && stylePos < styles.size) {
                    PreferenceManager.setAiStyle(activity, styles[stylePos].first)
                }

                Toast.makeText(activity, getString(R.string.ai_settings_saved), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                onDismissListener?.invoke()
            }
        }

        return dialog
    }

    private fun loadModels(provider: String, forceRefresh: Boolean) {
        if (modelsReady) rememberDraft(selectedProvider)
        persistKey(provider)
        modelsLoadingJob?.cancel()

        modelStatuses.clear()
        currentModels.clear()
        val customItem = ModelItem(null, getString(R.string.ai_model_custom), isCustom = true)
        currentModels += customItem
        modelAdapter.notifyDataSetChanged()
        modelsReady = false
        
        tvLoadStatus.visibility = View.VISIBLE
        tvLoadStatus.text = getString(R.string.ai_models_loading)
        tvLoadStatus.setTextColor(Color.GRAY)
        btnRefresh.isEnabled = false

        modelsLoadingJob = lifecycleScope.launch {
            try {
                val result = when (provider) {
                    "openrouter" -> repository.getOpenRouterFreeModels(forceRefresh)
                    "groq"       -> repository.getGroqModels(forceRefresh)
                    else         -> repository.getGeminiModels(forceRefresh)
                }
                if (provider != selectedProvider) return@launch

                val apiModels = when (result) {
                    is ModelLoadResult.Success -> {
                        tvLoadStatus.visibility = View.GONE
                        result.models
                    }
                    is ModelLoadResult.Error -> {
                        showLoadError(result)
                        result.cachedModels
                    }
                }
                applyModels(provider, apiModels)
                checkAvailability(provider)
            } finally {
                if (provider == selectedProvider) {
                    btnRefresh.isEnabled = true
                }
            }
        }
    }

    private fun applyModels(provider: String, apiModels: List<CachedModel>) {
        currentModels.clear()
        apiModels.forEach { currentModels += ModelItem(it.id, it.label) }
        currentModels += ModelItem(null, getString(R.string.ai_model_custom), isCustom = true)
        modelAdapter.notifyDataSetChanged()
        modelsReady = true

        val wanted = draftModels[provider]
        when {
            wanted != null && apiModels.any { it.id == wanted } -> selectModelById(wanted)
            wanted != null -> selectCustom(wanted)
            apiModels.isNotEmpty() -> spinnerModel.setSelection(0)
            else -> selectCustom(null)
        }
    }

    private suspend fun checkAvailability(provider: String) = coroutineScope {
        val apiItems = currentModels.filterNot { it.isCustom }
        apiItems.forEach { item -> item.id?.let { modelStatuses[it] = "..." } }
        modelAdapter.notifyDataSetChanged()

        val semaphore = Semaphore(3)
        apiItems.map { item ->
            async {
                semaphore.withPermit {
                    val id = item.id ?: return@withPermit
                    val status = try {
                        AiProcessor.testModelAvailability(provider, id, requireActivity())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (provider == selectedProvider && status != null) {
                        modelStatuses[id] = if (status.first) "✓" else "✗"
                        modelAdapter.notifyDataSetChanged()
                    }
                }
            }
        }.awaitAll()
    }

    private fun rememberDraft(provider: String) {
        resolveSelectedModelId()?.takeIf { it.isNotBlank() }?.let { draftModels[provider] = it }
    }

    private fun selectModelById(id: String) {
        val idx = currentModels.indexOfFirst { it.id == id }
        if (idx >= 0) {
            spinnerModel.setSelection(idx)
            tilCustomModel.visibility = View.GONE
        }
    }

    private fun selectCustom(id: String?) {
        val idx = currentModels.indexOfFirst { it.isCustom }
        if (idx >= 0) {
            spinnerModel.setSelection(idx)
            tilCustomModel.visibility = View.VISIBLE
            etCustomModel.setText(id ?: "")
            tilCustomModel.error = null
        }
    }

    private fun resolveSelectedModelId(): String? {
        if (!modelsReady) return draftModels[selectedProvider]
        val pos = spinnerModel.selectedItemPosition
        val item = currentModels.getOrNull(pos) ?: return null
        return if (item.isCustom) etCustomModel.text?.toString()?.trim() else item.id
    }

    private fun persistKey(provider: String) {
        val activity = requireActivity()
        when (provider) {
            "openrouter" -> PreferenceManager.saveOpenRouterApiKey(activity, etOpenRouterKey?.text?.toString()?.trim() ?: "")
            "groq"       -> PreferenceManager.saveGroqApiKey(activity, etGroqKey?.text?.toString()?.trim() ?: "")
            "gemini"     -> PreferenceManager.saveGeminiApiKey(activity, etGeminiKey?.text?.toString()?.trim() ?: "")
        }
    }

    private fun showLoadError(result: ModelLoadResult.Error) {
        tvLoadStatus.visibility = View.VISIBLE
        tvLoadStatus.setTextColor(Color.RED)
        tvLoadStatus.text = when {
            result.isMissingKey -> getString(R.string.ai_models_key_missing)
            result.cachedModels.isNotEmpty() -> getString(R.string.ai_models_load_failed_cached)
            else -> getString(R.string.ai_models_load_failed)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        modelsLoadingJob?.cancel()
        modelsLoadingJob = null
        super.onDismiss(dialog)
    }

    override fun onDestroy() {
        modelsLoadingJob?.cancel()
        super.onDestroy()
    }
}
