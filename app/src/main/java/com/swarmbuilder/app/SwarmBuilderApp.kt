package com.swarmbuilder.app

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.swarmbuilder.app.models.LlmProvider
import com.swarmbuilder.app.models.UserSettings

class SwarmBuilderApp : Application() {

    lateinit var userSettings: UserSettings
        private set

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        userSettings = loadSettings()
    }

    fun loadSettings(): UserSettings {
        val providerName = prefs.getString(PREF_PROVIDER, LlmProvider.GROQ.name) ?: LlmProvider.GROQ.name
        val provider = runCatching { LlmProvider.valueOf(providerName) }.getOrDefault(LlmProvider.GROQ)
        return UserSettings(
            groqApiKey = prefs.getString(PREF_GROQ_KEY, "") ?: "",
            huggingFaceToken = prefs.getString(PREF_HF_TOKEN, "") ?: "",
            openRouterApiKey = prefs.getString(PREF_OR_KEY, "") ?: "",
            githubToken = prefs.getString(PREF_GH_TOKEN, "") ?: "",
            githubUsername = prefs.getString(PREF_GH_USER, "") ?: "",
            preferredProvider = provider,
            useLocalOllama = prefs.getBoolean(PREF_OLLAMA, false),
            ollamaModel = prefs.getString(PREF_OLLAMA_MODEL, "llama3") ?: "llama3",
            localOpenAiBaseUrl = prefs.getString(PREF_LOCAL_OPENAI_URL, "http://127.0.0.1:8081/v1")
                ?: "http://127.0.0.1:8081/v1",
            localOpenAiModel = prefs.getString(PREF_LOCAL_OPENAI_MODEL, "") ?: ""
        ).also { userSettings = it }
    }

    fun saveSettings(s: UserSettings) {
        prefs.edit()
            .putString(PREF_GROQ_KEY, s.groqApiKey)
            .putString(PREF_HF_TOKEN, s.huggingFaceToken)
            .putString(PREF_OR_KEY, s.openRouterApiKey)
            .putString(PREF_GH_TOKEN, s.githubToken)
            .putString(PREF_GH_USER, s.githubUsername)
            .putString(PREF_PROVIDER, s.preferredProvider.name)
            .putBoolean(PREF_OLLAMA, s.useLocalOllama)
            .putString(PREF_OLLAMA_MODEL, s.ollamaModel)
            .putString(PREF_LOCAL_OPENAI_URL, s.localOpenAiBaseUrl)
            .putString(PREF_LOCAL_OPENAI_MODEL, s.localOpenAiModel)
            .apply()
        userSettings = s
    }

    companion object {
        const val PREF_GROQ_KEY = "groq_api_key"
        const val PREF_HF_TOKEN = "hf_token"
        const val PREF_OR_KEY = "openrouter_api_key"
        const val PREF_GH_TOKEN = "github_token"
        const val PREF_GH_USER = "github_username"
        const val PREF_PROVIDER = "preferred_provider"
        const val PREF_OLLAMA = "use_ollama"
        const val PREF_OLLAMA_MODEL = "ollama_model"
        const val PREF_LOCAL_OPENAI_URL = "local_openai_base_url"
        const val PREF_LOCAL_OPENAI_MODEL = "local_openai_model"
    }
}
