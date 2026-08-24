package com.swarmbuilder.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.swarmbuilder.app.SwarmBuilderApp
import com.swarmbuilder.app.databinding.ActivitySettingsBinding
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
        binding.etGroqKey.setText(s.groqApiKey)
        binding.etHfToken.setText(s.huggingFaceToken)
        binding.etOrKey.setText(s.openRouterApiKey)
        binding.etGhToken.setText(s.githubToken)
        binding.etGhUser.setText(s.githubUsername)
        binding.switchOllama.isChecked = s.useLocalOllama
        binding.etOllamaModel.setText(s.ollamaModel)
        binding.etLocalOpenAiUrl.setText(s.localOpenAiBaseUrl)
        binding.etLocalOpenAiModel.setText(s.localOpenAiModel)

        val providers = LlmProvider.values().map { it.displayName }
        val idx = LlmProvider.values().indexOfFirst { it == s.preferredProvider }
        binding.spinnerProvider.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            providers
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        if (idx >= 0) binding.spinnerProvider.setSelection(idx)
    }

    private fun saveSettings(app: SwarmBuilderApp) {
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
            localOpenAiBaseUrl = binding.etLocalOpenAiUrl.text.toString().trim()
                .ifBlank { "http://127.0.0.1:8081/v1" },
            localOpenAiModel = binding.etLocalOpenAiModel.text.toString().trim()
        )
        app.saveSettings(settings)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
