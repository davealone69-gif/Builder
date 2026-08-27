package com.swarmbuilder.app

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.swarmbuilder.app.models.AgentConfig
import com.swarmbuilder.app.models.LlmProvider
import com.swarmbuilder.app.models.UserSettings

class SwarmBuilderApp : Application() {

    lateinit var userSettings: UserSettings
        private set

    private lateinit var securePrefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        securePrefs = createSecurePrefs()
        migrateLegacyPlaintextKeys()
        userSettings = loadSettings()
    }

    private fun createSecurePrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(this, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            this, SECURE_PREFS_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun migrateLegacyPlaintextKeys() {
        if (securePrefs.getBoolean(KEY_MIGRATED_V1, false)) return
        val legacy = PreferenceManager.getDefaultSharedPreferences(this)
        val keysToMove = listOf(
            PREF_GROQ_KEY, PREF_HF_TOKEN, PREF_OR_KEY, PREF_GH_TOKEN, PREF_GH_USER, PREF_GH_REPO,
            PREF_PROVIDER, PREF_OLLAMA, PREF_OLLAMA_MODEL,
            PREF_LOCAL_OPENAI_URL, PREF_LOCAL_OPENAI_MODEL,
            PREF_CUSTOM_URL, PREF_CUSTOM_MODEL, PREF_CUSTOM_KEY,
            PREF_LOCAL_FIRST
        )
        val secureEdit = securePrefs.edit()
        val legacyEdit = legacy.edit()
        keysToMove.forEach { key ->
            if (legacy.contains(key)) {
                legacy.all[key]?.let { value ->
                    when (value) {
                        is String -> secureEdit.putString(key, value)
                        is Boolean -> secureEdit.putBoolean(key, value)
                        else -> Unit
                    }
                }
                legacyEdit.remove(key)
            }
        }
        // Also migrate per-agent keys if they exist
        listOf(
            PREF_ARCH_PROVIDER, PREF_ARCH_MODEL, PREF_ARCH_URL, PREF_ARCH_KEY, PREF_ARCH_PROMPT,
            PREF_CODER_PROVIDER, PREF_CODER_MODEL, PREF_CODER_URL, PREF_CODER_KEY, PREF_CODER_PROMPT,
            PREF_REVIEWER_PROVIDER, PREF_REVIEWER_MODEL, PREF_REVIEWER_URL, PREF_REVIEWER_KEY, PREF_REVIEWER_PROMPT
        ).forEach { key ->
            if (legacy.contains(key)) {
                legacy.all[key]?.let { value ->
                    when (value) {
                        is String -> secureEdit.putString(key, value)
                        else -> Unit
                    }
                }
                legacyEdit.remove(key)
            }
        }
        secureEdit.putBoolean(KEY_MIGRATED_V1, true).apply()
        legacyEdit.apply()
    }

    fun loadSettings(): UserSettings {
        val providerName = securePrefs.getString(PREF_PROVIDER, LlmProvider.OPENROUTER.name) ?: LlmProvider.OPENROUTER.name
        val provider = runCatching { LlmProvider.valueOf(providerName) }.getOrDefault(LlmProvider.OPENROUTER)

        fun loadAgentConfig(pPref: String, mPref: String, uPref: String, kPref: String, sPref: String): AgentConfig {
            val pName = securePrefs.getString(pPref, "") ?: ""
            val p = if (pName.isNotBlank()) runCatching { LlmProvider.valueOf(pName) }.getOrDefault(LlmProvider.GROQ) else LlmProvider.GROQ
            return AgentConfig(
                provider = if (pName.isNotBlank()) p else LlmProvider.GROQ,
                modelId = securePrefs.getString(mPref, "") ?: "",
                baseUrl = securePrefs.getString(uPref, "") ?: "",
                apiKey = securePrefs.getString(kPref, "") ?: "",
                systemPrompt = securePrefs.getString(sPref, "") ?: ""
            )
        }

        return UserSettings(
            groqApiKey = securePrefs.getString(PREF_GROQ_KEY, "") ?: "",
            huggingFaceToken = securePrefs.getString(PREF_HF_TOKEN, "") ?: "",
            openRouterApiKey = securePrefs.getString(PREF_OR_KEY, "") ?: "",
            githubToken = securePrefs.getString(PREF_GH_TOKEN, "") ?: "",
            githubUsername = securePrefs.getString(PREF_GH_USER, "") ?: "",
            githubRepoName = securePrefs.getString(PREF_GH_REPO, "") ?: "",
            preferredProvider = provider,
            useLocalOllama = securePrefs.getBoolean(PREF_OLLAMA, false),
            ollamaModel = securePrefs.getString(PREF_OLLAMA_MODEL, "llama3") ?: "llama3",
            localOpenAiBaseUrl = securePrefs.getString(PREF_LOCAL_OPENAI_URL, "http://127.0.0.1:8081/v1") ?: "http://127.0.0.1:8081/v1",
            localOpenAiModel = securePrefs.getString(PREF_LOCAL_OPENAI_MODEL, "") ?: "",
            customProviderUrl = securePrefs.getString(PREF_CUSTOM_URL, "") ?: "",
            customProviderModel = securePrefs.getString(PREF_CUSTOM_MODEL, "") ?: "",
            customProviderKey = securePrefs.getString(PREF_CUSTOM_KEY, "") ?: "",
            localFirst = securePrefs.getBoolean(PREF_LOCAL_FIRST, false),
            architectConfig = loadAgentConfig(PREF_ARCH_PROVIDER, PREF_ARCH_MODEL, PREF_ARCH_URL, PREF_ARCH_KEY, PREF_ARCH_PROMPT),
            coderConfig = loadAgentConfig(PREF_CODER_PROVIDER, PREF_CODER_MODEL, PREF_CODER_URL, PREF_CODER_KEY, PREF_CODER_PROMPT),
            reviewerConfig = loadAgentConfig(PREF_REVIEWER_PROVIDER, PREF_REVIEWER_MODEL, PREF_REVIEWER_URL, PREF_REVIEWER_KEY, PREF_REVIEWER_PROMPT)
        ).also { userSettings = it }
    }

    fun saveSettings(s: UserSettings) {
        securePrefs.edit()
            .putString(PREF_GROQ_KEY, s.groqApiKey)
            .putString(PREF_HF_TOKEN, s.huggingFaceToken)
            .putString(PREF_OR_KEY, s.openRouterApiKey)
            .putString(PREF_GH_TOKEN, s.githubToken)
            .putString(PREF_GH_USER, s.githubUsername)
            .putString(PREF_GH_REPO, s.githubRepoName)
            .putString(PREF_PROVIDER, s.preferredProvider.name)
            .putBoolean(PREF_OLLAMA, s.useLocalOllama)
            .putString(PREF_OLLAMA_MODEL, s.ollamaModel)
            .putString(PREF_LOCAL_OPENAI_URL, s.localOpenAiBaseUrl)
            .putString(PREF_LOCAL_OPENAI_MODEL, s.localOpenAiModel)
            .putString(PREF_CUSTOM_URL, s.customProviderUrl)
            .putString(PREF_CUSTOM_MODEL, s.customProviderModel)
            .putString(PREF_CUSTOM_KEY, s.customProviderKey)
            .putBoolean(PREF_LOCAL_FIRST, s.localFirst)
            // Per-agent
            .putString(PREF_ARCH_PROVIDER, s.architectConfig.provider.name)
            .putString(PREF_ARCH_MODEL, s.architectConfig.modelId)
            .putString(PREF_ARCH_URL, s.architectConfig.baseUrl)
            .putString(PREF_ARCH_KEY, s.architectConfig.apiKey)
            .putString(PREF_ARCH_PROMPT, s.architectConfig.systemPrompt)
            .putString(PREF_CODER_PROVIDER, s.coderConfig.provider.name)
            .putString(PREF_CODER_MODEL, s.coderConfig.modelId)
            .putString(PREF_CODER_URL, s.coderConfig.baseUrl)
            .putString(PREF_CODER_KEY, s.coderConfig.apiKey)
            .putString(PREF_CODER_PROMPT, s.coderConfig.systemPrompt)
            .putString(PREF_REVIEWER_PROVIDER, s.reviewerConfig.provider.name)
            .putString(PREF_REVIEWER_MODEL, s.reviewerConfig.modelId)
            .putString(PREF_REVIEWER_URL, s.reviewerConfig.baseUrl)
            .putString(PREF_REVIEWER_KEY, s.reviewerConfig.apiKey)
            .putString(PREF_REVIEWER_PROMPT, s.reviewerConfig.systemPrompt)
            .apply()
        userSettings = s
    }

    companion object {
        private const val SECURE_PREFS_FILE = "swarmbuilder_secure_prefs"
        private const val KEY_MIGRATED_V1 = "secure_prefs_migrated_v1"

        const val PREF_GROQ_KEY = "groq_api_key"
        const val PREF_HF_TOKEN = "hf_token"
        const val PREF_OR_KEY = "openrouter_api_key"
        const val PREF_GH_TOKEN = "github_token"
        const val PREF_GH_USER = "github_username"
        const val PREF_GH_REPO = "github_repo_name"
        const val PREF_PROVIDER = "preferred_provider"
        const val PREF_OLLAMA = "use_ollama"
        const val PREF_OLLAMA_MODEL = "ollama_model"
        const val PREF_LOCAL_OPENAI_URL = "local_openai_base_url"
        const val PREF_LOCAL_OPENAI_MODEL = "local_openai_model"
        const val PREF_CUSTOM_URL = "custom_provider_url"
        const val PREF_CUSTOM_MODEL = "custom_provider_model"
        const val PREF_CUSTOM_KEY = "custom_provider_key"
        const val PREF_LOCAL_FIRST = "local_first"
        // Per-agent
        const val PREF_ARCH_PROVIDER = "arch_provider"
        const val PREF_ARCH_MODEL = "arch_model"
        const val PREF_ARCH_URL = "arch_url"
        const val PREF_ARCH_KEY = "arch_key"
        const val PREF_ARCH_PROMPT = "arch_system_prompt"
        const val PREF_CODER_PROVIDER = "coder_provider"
        const val PREF_CODER_MODEL = "coder_model"
        const val PREF_CODER_URL = "coder_url"
        const val PREF_CODER_KEY = "coder_key"
        const val PREF_CODER_PROMPT = "coder_system_prompt"
        const val PREF_REVIEWER_PROVIDER = "reviewer_provider"
        const val PREF_REVIEWER_MODEL = "reviewer_model"
        const val PREF_REVIEWER_URL = "reviewer_url"
        const val PREF_REVIEWER_KEY = "reviewer_key"
        const val PREF_REVIEWER_PROMPT = "reviewer_system_prompt"
    }
}
