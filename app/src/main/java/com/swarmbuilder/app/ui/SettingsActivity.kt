package com.swarmbuilder.app.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.swarmbuilder.app.R
import com.swarmbuilder.app.SwarmBuilderApp
import com.swarmbuilder.app.models.*

/**
 * SettingsActivity v3
 *
 * Each agent (Architect, Coder, Reviewer) has its OWN:
 * - Provider dropdown
 * - Model name field
 * - Base URL field (for custom endpoints / web chatbot proxies)
 * - API key field (optional — uses global key if blank)
 *
 * Also has:
 * - Global API keys (Groq, HF, OpenRouter)
 * - GitHub settings
 * - Provider toggle (to switch LLM on/off for offline use)
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var app: SwarmBuilderApp

    // ── Global keys ───────────────────────────────────
    private lateinit var groqKeyInput: EditText
    private lateinit var hfTokenInput: EditText
    private lateinit var openRouterKeyInput: EditText

    // ── GitHub ────────────────────────────────────────
    private lateinit var githubTokenInput: EditText
    private lateinit var githubUsernameInput: EditText
    private lateinit var githubRepoInput: EditText

    // ── Provider switch ───────────────────────────────
    private lateinit var providerSpinner: Spinner

    // ── Architect config ──────────────────────────────
    private lateinit var archProviderSpinner: Spinner
    private lateinit var archModelInput: EditText
    private lateinit var archUrlInput: EditText
    private lateinit var archKeyInput: EditText

    // ── Coder config ──────────────────────────────────
    private lateinit var coderProviderSpinner: Spinner
    private lateinit var coderModelInput: EditText
    private lateinit var coderUrlInput: EditText
    private lateinit var coderKeyInput: EditText

    // ── Reviewer config ───────────────────────────────
    private lateinit var reviewerProviderSpinner: Spinner
    private lateinit var reviewerModelInput: EditText
    private lateinit var reviewerUrlInput: EditText
    private lateinit var reviewerKeyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        app = getApplication<SwarmBuilderApp>()

        bindViews()
        populateSpinners()
        loadSettings()
    }

    private fun bindViews() {
        // Global keys
        groqKeyInput = findViewById(R.id.input_groq_key)
        hfTokenInput = findViewById(R.id.input_hf_token)
        openRouterKeyInput = findViewById(R.id.input_openrouter_key)

        // GitHub
        githubTokenInput = findViewById(R.id.input_github_token)
        githubUsernameInput = findViewById(R.id.input_github_username)
        githubRepoInput = findViewById(R.id.input_github_repo)

        // Provider
        providerSpinner = findViewById(R.id.spinner_provider)

        // Architect
        archProviderSpinner = findViewById(R.id.spinner_arch_provider)
        archModelInput = findViewById(R.id.input_arch_model)
        archUrlInput = findViewById(R.id.input_arch_url)
        archKeyInput = findViewById(R.id.input_arch_key)

        // Coder
        coderProviderSpinner = findViewById(R.id.spinner_coder_provider)
        coderModelInput = findViewById(R.id.input_coder_model)
        coderUrlInput = findViewById(R.id.input_coder_url)
        coderKeyInput = findViewById(R.id.input_coder_key)

        // Reviewer
        reviewerProviderSpinner = findViewById(R.id.spinner_reviewer_provider)
        reviewerModelInput = findViewById(R.id.input_reviewer_model)
        reviewerUrlInput = findViewById(R.id.input_reviewer_url)
        reviewerKeyInput = findViewById(R.id.input_reviewer_key)
    }

    private fun populateSpinners() {
        val providers = LlmProvider.values().map { it.displayName }.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        providerSpinner.adapter = adapter
        archProviderSpinner.adapter = adapter
        coderProviderSpinner.adapter = adapter
        reviewerProviderSpinner.adapter = adapter
    }

    private fun loadSettings() {
        val s = app.userSettings
        groqKeyInput.setText(s.groqApiKey)
        hfTokenInput.setText(s.huggingFaceToken)
        openRouterKeyInput.setText(s.openRouterApiKey)
        githubTokenInput.setText(s.githubToken)
        githubUsernameInput.setText(s.githubUsername)
        githubRepoInput.setText(s.githubRepoName)
        providerSpinner.setSelection(s.preferredProvider.ordinal)

        // Load per-agent configs
        loadAgentConfig(s.architectConfig, archProviderSpinner, archModelInput, archUrlInput, archKeyInput)
        loadAgentConfig(s.coderConfig, coderProviderSpinner, coderModelInput, coderUrlInput, coderKeyInput)
        loadAgentConfig(s.reviewerConfig, reviewerProviderSpinner, reviewerModelInput, reviewerUrlInput, reviewerKeyInput)
    }

    private fun loadAgentConfig(
        config: AgentConfig,
        spinner: Spinner,
        modelInput: EditText,
        urlInput: EditText,
        keyInput: EditText
    ) {
        spinner.setSelection(config.provider.ordinal)
        modelInput.setText(config.modelId)
        urlInput.setText(config.baseUrl)
        keyInput.setText(config.apiKey)
    }

    fun onSaveClicked(view: View) {
        val s = UserSettings(
            groqApiKey = groqKeyInput.text.toString().trim(),
            huggingFaceToken = hfTokenInput.text.toString().trim(),
            openRouterApiKey = openRouterKeyInput.text.toString().trim(),
            githubToken = githubTokenInput.text.toString().trim(),
            githubUsername = githubUsernameInput.text.toString().trim(),
            githubRepoName = githubRepoInput.text.toString().trim(),
            preferredProvider = LlmProvider.values()[providerSpinner.selectedItemPosition],
            architectConfig = readAgentConfig(archProviderSpinner, archModelInput, archUrlInput, archKeyInput),
            coderConfig = readAgentConfig(coderProviderSpinner, coderModelInput, coderUrlInput, coderKeyInput),
            reviewerConfig = readAgentConfig(reviewerProviderSpinner, reviewerModelInput, reviewerUrlInput, reviewerKeyInput)
        )
        app.saveSettings(s)
        Toast.makeText(this, "Settings saved ✓", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun readAgentConfig(
        spinner: Spinner,
        modelInput: EditText,
        urlInput: EditText,
        keyInput: EditText
    ): AgentConfig = AgentConfig(
        provider = LlmProvider.values()[spinner.selectedItemPosition],
        modelId = modelInput.text.toString().trim(),
        baseUrl = urlInput.text.toString().trim(),
        apiKey = keyInput.text.toString().trim()
    )
}
