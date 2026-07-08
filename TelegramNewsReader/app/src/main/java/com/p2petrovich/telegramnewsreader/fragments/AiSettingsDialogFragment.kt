package com.p2petrovich.telegramnewsreader.fragments

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
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
import com.p2petrovich.telegramnewsreader.utils.AiProcessor
import com.p2petrovich.telegramnewsreader.utils.Logx
import com.p2petrovich.telegramnewsreader.utils.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiSettingsDialogFragment : DialogFragment() {

    private var onDismissListener: (() -> Unit)? = null

    fun setOnDismissListener(listener: () -> Unit) {
        onDismissListener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val inflater = activity.layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_ai_settings, null)

        val switchEnabled = dialogView.findViewById<MaterialSwitch>(R.id.switch_ai_enabled)
        val spinnerProvider = dialogView.findViewById<Spinner>(R.id.spinner_ai_provider)
        val spinnerModel = dialogView.findViewById<Spinner>(R.id.spinner_ai_model)
        val spinnerStyle = dialogView.findViewById<Spinner>(R.id.spinner_ai_style)
        val btnTestManual = dialogView.findViewById<Button>(R.id.btn_test_ai_model)
        val tvStatusManual = dialogView.findViewById<TextView>(R.id.tv_ai_test_status)

        btnTestManual?.visibility = View.VISIBLE
        tvStatusManual?.visibility = View.VISIBLE

        switchEnabled.isChecked = PreferenceManager.isAiSummaryEnabled(activity)

        val tilOpenRouterKey = dialogView.findViewById<TextInputLayout>(R.id.til_openrouter_key)
        val etOpenRouterKey  = dialogView.findViewById<TextInputEditText>(R.id.et_openrouter_key)
        val tilGroqKey       = dialogView.findViewById<TextInputLayout>(R.id.til_groq_key)
        val etGroqKey        = dialogView.findViewById<TextInputEditText>(R.id.et_groq_key)
        val tilGeminiKey      = dialogView.findViewById<TextInputLayout>(R.id.til_gemini_key)
        val etGeminiKey       = dialogView.findViewById<TextInputEditText>(R.id.et_gemini_key)

        etOpenRouterKey?.setText(PreferenceManager.getOpenRouterApiKey(activity))
        etGroqKey?.setText(PreferenceManager.getGroqApiKey(activity))
        etGeminiKey?.setText(PreferenceManager.getGeminiApiKey(activity))

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

        val currentProvider = PreferenceManager.getAiProvider(activity)
        spinnerProvider.setSelection(providers.indexOfFirst { it.first == currentProvider }.coerceAtLeast(0))
        updateKeyFieldVisibility(currentProvider)

        fun getModelsForProvider(provider: String): List<Pair<String, String>> = when (provider) {
            "gemini" -> listOf(
                "gemini-2.0-flash-exp" to "Gemini 2.0 Flash (AI Studio) — FREE"
            )
            "groq" -> listOf(
                "llama-3.3-70b-versatile"                  to getString(R.string.ai_model_llama_fast),
                "llama-3.1-8b-instant"                     to getString(R.string.ai_model_llama_instant),
                "meta-llama/llama-4-scout-17b-16e-instruct" to getString(R.string.ai_model_llama_new)
            )
            else -> listOf(
                "openai/gpt-3.5-turbo:free"             to getString(R.string.ai_model_gpt_oss_120b_free),
                "google/gemma-2-9b-it:free"             to getString(R.string.ai_model_gemma_4_26b_free),
                "nvidia/nemotron-3-super-120b-a12b:free" to getString(R.string.ai_model_nemotron_3_super_free)
            )
        }

        val currentModels = getModelsForProvider(currentProvider).toMutableList()
        val modelStatuses = mutableMapOf<String, String>()

        val modelAdapter = object : ArrayAdapter<Pair<String, String>>(
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

                tvName.text = item?.second ?: ""
                val status = modelStatuses[item?.first] ?: ""
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

        fun updateModelsAndCheck(provider: String) {
            currentModels.clear()
            currentModels.addAll(getModelsForProvider(provider))
            modelAdapter.notifyDataSetChanged()

            val savedModel = PreferenceManager.getAiModel(activity)
            val modelIdx = currentModels.indexOfFirst { it.first == savedModel }.coerceAtLeast(0)
            spinnerModel.setSelection(modelIdx)

            // [FIX] Используем lifecycleScope фрагмента вместо viewLifecycleOwner
            // так как в onCreateDialog вью еще не прикреплена к фрагменту
            lifecycleScope.launch {
                currentModels.forEach { modelPair ->
                    modelStatuses[modelPair.first] = "..."
                }
                modelAdapter.notifyDataSetChanged()

                currentModels.map { modelPair ->
                    async {
                        try {
                            val result = AiProcessor.testModelAvailability(modelPair.first, activity)
                            modelStatuses[modelPair.first] = if (result.first) "✓" else "✗"
                            withContext(Dispatchers.Main) { modelAdapter.notifyDataSetChanged() }
                        } catch (e: Exception) {
                            Logx.e("AiDialog", "Check failed", e)
                        }
                    }
                }.awaitAll()
            }
        }

        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newProvider = providers[position].first
                updateKeyFieldVisibility(newProvider)
                if (newProvider != PreferenceManager.getAiProvider(activity)) {
                    PreferenceManager.setAiProvider(activity, newProvider)
                    PreferenceManager.setAiModel(activity, PreferenceManager.getDefaultModelForProvider(newProvider))
                    updateModelsAndCheck(newProvider)
                } else {
                    updateModelsAndCheck(currentProvider)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnTestManual?.setOnClickListener {
            val selectedModel = currentModels.getOrNull(spinnerModel.selectedItemPosition)?.first ?: return@setOnClickListener

            PreferenceManager.saveOpenRouterApiKey(activity, etOpenRouterKey?.text?.toString()?.trim() ?: "")
            PreferenceManager.saveGroqApiKey(activity, etGroqKey?.text?.toString()?.trim() ?: "")

            tvStatusManual?.visibility = View.VISIBLE
            tvStatusManual?.text = getString(R.string.proxy_status_checking)
            tvStatusManual?.setTextColor(Color.GRAY)

            lifecycleScope.launch {
                val result = AiProcessor.testModelAvailability(selectedModel, activity)
                tvStatusManual?.text = if (result.first) getString(R.string.ai_model_available) else result.second
                tvStatusManual?.setTextColor(if (result.first) Color.GREEN else Color.RED)

                modelStatuses[selectedModel] = if (result.first) "✓" else "✗"
                modelAdapter.notifyDataSetChanged()
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

        return AlertDialog.Builder(activity)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                PreferenceManager.saveOpenRouterApiKey(activity, etOpenRouterKey?.text?.toString()?.trim() ?: "")
                PreferenceManager.saveGroqApiKey(activity, etGroqKey?.text?.toString()?.trim() ?: "")
                PreferenceManager.saveGeminiApiKey(activity, etGeminiKey?.text?.toString()?.trim() ?: "")
                PreferenceManager.setAiSummaryEnabled(activity, switchEnabled.isChecked)
                
                val modelPos = spinnerModel.selectedItemPosition
                if (modelPos >= 0 && modelPos < currentModels.size) {
                    PreferenceManager.setAiModel(activity, currentModels[modelPos].first)
                }
                
                val stylePos = spinnerStyle.selectedItemPosition
                if (stylePos >= 0 && stylePos < styles.size) {
                    PreferenceManager.setAiStyle(activity, styles[stylePos].first)
                }

                Toast.makeText(activity, getString(R.string.ai_settings_saved), Toast.LENGTH_SHORT).show()
                onDismissListener?.invoke()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onDismissListener?.invoke() }
            .create()
    }
}
