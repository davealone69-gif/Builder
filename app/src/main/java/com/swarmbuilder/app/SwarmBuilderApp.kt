package com.swarmbuilder.app

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.swarmbuilder.app.models.LlmProvider
import com.swarmbuilder.app.models.UserSettings

class SwarmBuilderApp : Application() {

    lateinit var userSettings: UserSettings
        private set

    // API keys live in encrypted prefs (Android Keystore-backed); the default
    // prefs file is only read once for a legacy migration and then scrubbed.
    private lateinit var securePrefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        securePrefs = createSecurePrefs()
        migrateLegacyPlaintextKeys()
        userSettings = loadSettings()
    }

    private fun createSecurePrefs(): SharedPreferences {
        // Values are encrypted with AES256-GCM, keys with AES256-SIV.
        val masterKey = MasterKey.Builder(this, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            this,
            SECURE_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * One-time migration: copies any keys previously stored in the plaintext
     * default SharedPreferences into the encrypted store, then scrubs them.
     */
    private fun migrateLegacyPlaintextKeys() {
        if (securePrefs.getBoolean(KEY_MIGRATED_V1, false)) return
        val legacy = PreferenceManager.getDefaultSharedPreferences(this)
        val keysToMove = listOf(
            PREF_GROQ_KEY, PREF_HF_TOKEN, PREF_OR_KEY, PREF_GH_TOKEN, PREF_GH_USER,
            PREF_PROVIDER, PREF_OLLAMA, PREF_OLLAMA_MODEL,
            PREF_LOCAL_OPENAI_URL, PREF_LOCAL_OPENAI_MODEL
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
        secureEdit.putBoolean(KEY_MIGRATED_V1, true).apply()
        legacyEdit.apply()
    }

    fun loadSettings(): UserSettings {
        val providerName = securePrefs.getString(PREF_PROVIDER, LlmProvider.GROQ.name) ?: LlmProvider.GROQ.name
        val provider = runCatching { LlmProvider.valueOf(providerName) }.getOrDefault(LlmProvider.GROQ)
        return UserSettings(
            groqApiKey = securePrefs.getString(PREF_GROQ_KEY, "") ?: "",
            huggingFaceToken = securePrefs.getString(PREF_HF_TOKEN, "") ?: "",
            openRouterApiKey = securePrefs.getString(PREF_OR_KEY, "") ?: "",
            githubToken = securePrefs.getString(PREF_GH_TOKEN, "") ?: "",
            githubUsername = securePrefs.getString(PREF_GH_USER, "") ?: "",
            preferredProvider = provider,
            useLocalOllama = securePrefs.getBoolean(PREF_OLLAMA, false),
            ollamaModel = securePrefs.getString(PREF_OLLAMA_MODEL, "llama3") ?: "llama3",
            localOpenAiBaseUrl = securePrefs.getString(PREF_LOCAL_OPENAI_URL, "http://127.0.0.1:8081/v1")
                ?: "http://127.0.0.1:8081/v1",
            localOpenAiModel = securePrefs.getString(PREF_LOCAL_OPENAI_MODEL, "") ?: ""
        ).also { userSettings = it }
    }

    fun saveSettings(s: UserSettings) {
        securePrefs.edit()
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
        private const val SECURE_PREFS_FILE = "swarmbuilder_secure_prefs"
        private const val KEY_MIGRATED_V1 = "secure_prefs_migrated_v1"

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
