package com.swarmbuilder.app.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.swarmbuilder.app.R
import com.swarmbuilder.app.SwarmBuilderApp
import com.swarmbuilder.app.databinding.ActivitySettingsBinding
import com.swarmbuilder.app.models.AgentConfig
import com.swarmbuilder.app.models.LlmProvider
import com.swarmbuilder.app.models.UserSettings

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Settings"

        val app = application as SwarmBuilderApp
        loadSettings(app.userSettings)
        binding.btnSave.setOnClickListener { saveSettings(app) }
    }

    private fun loadSettings(s: UserSettings) {
        // Global keys
        binding.etGroqKey.setText(s.groqApiKey)
        binding.etHfToken.setText(s.huggingFaceToken)
        binding.etOrKey.setText(s.openRouterApiKey)
        // GitHub
        binding.etGhToken.setText(s.githubToken)
        binding.etGhUser.setText(s.githubUsername)
        // Local
        binding.switchOllama.isChecked = s.useLocalOllama
        binding.etOllamaModel.setText(s.ollamaModel)
        binding.etLocalOpenAiUrl.setText(s.localOpenAiBaseUrl)
        binding.etLocalOpenAiModel.setText(s.localOpenAiModel)
        // Custom provider
        binding.etCustomUrl.setText(s.customProviderUrl)
        binding.etCustomModel.setText(s.customProviderModel)
        binding.etCustomKey.setText(s.customProviderKey)
        // Local-first toggle
        binding.switchLocalFirst.isChecked = s.localFirst

        // Provider spinners
        val providers = LlmProvider.values().map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providers)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerProvider.adapter = adapter
        binding.spArchProvider.adapter = adapter
        binding.spCoderProvider.adapter = adapter
        binding.spReviewerProvider.adapter = adapter

        // Set selections
        binding.spinnerProvider.setSelection(s.preferredProvider.ordinal)
        binding.spArchProvider.setSelection(s.architectConfig.provider.ordinal)
        binding.spCoderProvider.setSelection(s.coderConfig.provider.ordinal)
        binding.spReviewerProvider.setSelection(s.reviewerConfig.provider.ordinal)

        // Per-agent fields
        binding.etArchModel.setText(s.architectConfig.modelId)
        binding.etArchUrl.setText(s.architectConfig.baseUrl)
        binding.etArchKey.setText(s.architectConfig.apiKey)
        binding.etArchPrompt.setText(s.architectConfig.systemPrompt)

        binding.etCoderModel.setText(s.coderConfig.modelId)
        binding.etCoderUrl.setText(s.coderConfig.baseUrl)
        binding.etCoderKey.setText(s.coderConfig.apiKey)
        binding.etCoderPrompt.setText(s.coderConfig.systemPrompt)

        binding.etReviewerModel.setText(s.reviewerConfig.modelId)
        binding.etReviewerUrl.setText(s.reviewerConfig.baseUrl)
        binding.etReviewerKey.setText(s.reviewerConfig.apiKey)
        binding.etReviewerPrompt.setText(s.reviewerConfig.systemPrompt)

        // Hide custom provider fields unless "Custom" is selected
        updateCustomFieldsVisibility(s.preferredProvider.ordinal)
        binding.spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCustomFieldsVisibility(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateCustomFieldsVisibility(selectedIdx: Int) {
        val provider = LlmProvider.values().getOrElse(selectedIdx) { LlmProvider.GROQ }
        val show = provider == LlmProvider.CUSTOM
        val visible = if (show) android.view.View.VISIBLE else android.view.View.GONE
        binding.etCustomUrl.parent?.parent?.let { it.visibility = visible }
        binding.etCustomModel.parent?.parent?.let { it.visibility = visible }
        binding.etCustomKey.parent?.parent?.let { it.visibility = visible }
    }

    private fun saveSettings(app: SwarmBuilderApp) {
        fun readAgentConfig(spinner: android.widget.Spinner, modelField: android.widget.EditText, urlField: android.widget.EditText, keyField: android.widget.EditText, promptField: android.widget.EditText): AgentConfig {
            val provider = LlmProvider.values().getOrElse(spinner.selectedItemPosition) { LlmProvider.GROQ }
            return AgentConfig(
                provider = provider,
                modelId = modelField.text.toString().trim(),
                baseUrl = urlField.text.toString().trim(),
                apiKey = keyField.text.toString().trim(),
                systemPrompt = promptField.text.toString().trim()
            )
        }

        val selectedIdx = binding.spinnerProvider.selectedItemPosition
        val provider = LlmProvider.values().getOrElse(selectedIdx) { LlmProvider.GROQ }
        val settings = UserSettings(
            groqApiKey = binding.etGroqKey.text.toString().trim(),
            huggingFaceToken = binding.etHfToken.text.toString().trim(),
            openRouterApiKey = binding.etOrKey.text.toString().trim(),
            githubToken = binding.etGhToken.text.toString().trim(),
            githubUsername = binding.etGhUser.text.toString().trim(),
            preferredProvider = provider,
            useLocalOllama = binding.switchOllama.isChecked,
            ollamaModel = binding.etOllamaModel.text.toString().trim().ifBlank { "llama3" },
            localOpenAiBaseUrl = binding.etLocalOpenAiUrl.text.toString().trim().ifBlank { "http://127.0.0.1:8081/v1" },
            localOpenAiModel = binding.etLocalOpenAiModel.text.toString().trim(),
            customProviderUrl = binding.etCustomUrl.text.toString().trim(),
            customProviderModel = binding.etCustomModel.text.toString().trim(),
            customProviderKey = binding.etCustomKey.text.toString().trim(),
            localFirst = binding.switchLocalFirst.isChecked,
            architectConfig = readAgentConfig(binding.spArchProvider, binding.etArchModel, binding.etArchUrl, binding.etArchKey, binding.etArchPrompt),
            coderConfig = readAgentConfig(binding.spCoderProvider, binding.etCoderModel, binding.etCoderUrl, binding.etCoderKey, binding.etCoderPrompt),
            reviewerConfig = readAgentConfig(binding.spReviewerProvider, binding.etReviewerModel, binding.etReviewerUrl, binding.etReviewerKey, binding.etReviewerPrompt)
        )
        app.saveSettings(settings)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
