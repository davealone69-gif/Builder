package com.swarmbuilder.app.models

data class SwarmAgent(
    val id: String,
    val name: String,
    val role: AgentRole,
    val provider: LlmProvider,
    val modelId: String,
    var status: AgentStatus = AgentStatus.IDLE
)

enum class AgentRole(val description: String) {
    ARCHITECT("Designs overall app architecture and file structure"),
    CODER("Writes Kotlin/XML source code for Android"),
    REVIEWER("Reviews generated code and fixes Gradle build errors"),
    BUILDER("Compiles source files and assembles the APK"),
    PUBLISHER("Pushes the project to GitHub")
}

enum class AgentStatus { IDLE, RUNNING, DONE, ERROR }

data class AgentConfig(
    val provider: LlmProvider = LlmProvider.HERMES_AGENT,
    val modelId: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val systemPrompt: String = ""
)

/**
 * All LLM providers the app can talk to.
 * HERMES_AGENT is first because it works out-of-the-box with the local
 * Hermes-agent app — no API key needed.
 */
enum class LlmProvider(val displayName: String, val baseUrl: String) {
    HERMES_AGENT("Hermes Agent (local)", "http://localhost:8642/v1"),
    GROQ("Groq", "https://api.groq.com/openai/v1"),
    OPENROUTER("OpenRouter (free tier)", "https://openrouter.ai/api/v1"),
    HUGGINGFACE("Hugging Face", "https://api-inference.huggingface.co/models"),
    OLLAMA_LOCAL("Ollama (local)", "http://localhost:11434/api"),
    OPENAI_COMPAT_LOCAL("Local OpenAI-Compatible", "http://127.0.0.1:8081/v1"),
    CUSTOM("Custom (your own URL)", "https://api.openai.com/v1");

    val supportsSystemPrompt: Boolean get() = this != HUGGINGFACE

    /**
     * HERMES_AGENT has a built-in key ("change-me-local-dev").
     * Ollama and local endpoints don't need keys.
     * Groq/HF/OpenRouter/Custom need user-supplied keys.
     */
    val requiresApiKey: Boolean get() = this == GROQ || this == HUGGINGFACE || this == OPENROUTER || this == CUSTOM
}

data class AppSpec(
    val prompt: String,
    val appName: String = "",
    val packageName: String = "",
    val description: String = "",
    val features: List<String> = emptyList()
)

data class SourceFile(val relativePath: String, val content: String)

data class BuildResult(
    val success: Boolean,
    val appName: String,
    val apkPath: String? = null,
    val githubUrl: String? = null,
    val errorMessage: String? = null,
    val logs: List<String> = emptyList()
)

data class SwarmLog(
    val agentName: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO,
    val timestamp: Long = System.currentTimeMillis()
)

enum class LogLevel { INFO, SUCCESS, WARNING, ERROR }

/**
 * Full app settings.
 *
 * KEY DESIGN: resolveApiKey() and isProviderAvailable() drive automatic
 * fallback. If the user's saved provider has no valid key, the app
 * automatically falls back to HERMES_AGENT (which always works if the
 * local Hermes-agent app is running).
 */
data class UserSettings(
    // ── Global API keys (user enters these in Settings) ──
    val groqApiKey: String = "",
    val huggingFaceToken: String = "",
    val openRouterApiKey: String = "",

    // ── GitHub ─────────────────────────────────────────
    val githubToken: String = "",
    val githubUsername: String = "",
    val githubRepoName: String = "",

    // ── Default provider (fallback when others fail) ──
    val preferredProvider: LlmProvider = LlmProvider.HERMES_AGENT,

    // ── Local / Ollama ─────────────────────────────────
    val useLocalOllama: Boolean = false,
    val ollamaModel: String = "llama3",
    val localOpenAiBaseUrl: String = "http://127.0.0.1:8081/v1",
    val localOpenAiModel: String = "",

    // ── Custom provider ────────────────────────────────
    val customProviderUrl: String = "",
    val customProviderModel: String = "",
    val customProviderKey: String = "",

    // ── v4: Local-first routing ────────────────────────
    val localFirst: Boolean = false,

    // ── v4: Per-agent AI overrides ─────────────────────
    val architectConfig: AgentConfig = AgentConfig(),
    val coderConfig: AgentConfig = AgentConfig(),
    val reviewerConfig: AgentConfig = AgentConfig()
) {

    /**
     * Resolve the API key for a provider.
     * HERMES_AGENT always returns its built-in key.
     * Local providers return empty string (no key needed).
     * Cloud providers return the user's saved key.
     */
    fun resolveApiKey(provider: LlmProvider, agentOverride: String = ""): String {
        if (agentOverride.isNotBlank()) return agentOverride
        return when (provider) {
            LlmProvider.HERMES_AGENT -> "change-me-local-dev"
            LlmProvider.GROQ -> groqApiKey
            LlmProvider.HUGGINGFACE -> huggingFaceToken
            LlmProvider.OPENROUTER -> openRouterApiKey
            LlmProvider.CUSTOM -> customProviderKey
            LlmProvider.OLLAMA_LOCAL, LlmProvider.OPENAI_COMPAT_LOCAL -> ""
        }
    }

    fun resolveBaseUrl(provider: LlmProvider, agentOverride: String = ""): String {
        if (agentOverride.isNotBlank()) return agentOverride.trimEnd('/')
        if (provider == LlmProvider.CUSTOM) {
            return customProviderUrl.ifBlank { provider.baseUrl }.trimEnd('/')
        }
        if (provider == LlmProvider.OLLAMA_LOCAL &&
            localOpenAiBaseUrl.isNotBlank() &&
            !localOpenAiBaseUrl.contains("127.0.0.1") &&
            !localOpenAiBaseUrl.contains("localhost")) {
            return localOpenAiBaseUrl.removeSuffix("/v1").removeSuffix("/api").trimEnd('/')
        }
        return provider.baseUrl.trimEnd('/')
    }

    /**
     * Is this provider actually usable RIGHT NOW?
     * - HERMES_AGENT: always available (no key needed)
     * - Ollama/local: always available (no key needed)
     * - Cloud providers: only if user entered an API key
     */
    fun isProviderAvailable(provider: LlmProvider): Boolean {
        if (!provider.requiresApiKey) return true
        return resolveApiKey(provider).isNotBlank()
    }

    /**
     * Get all providers that could work right now.
     * Used by LlmClient for automatic fallback.
     */
    fun availableProviders(): List<LlmProvider> =
        LlmProvider.values().filter { isProviderAvailable(it) }

    /**
     * Get the fallback chain for a given provider.
     * Tries: HERMES_AGENT → other cloud providers with keys → local providers.
     * This is what makes the app work even if the user's saved provider fails.
     */
    fun getFallbackChain(exclude: LlmProvider): List<LlmProvider> {
        val available = availableProviders().filter { it != exclude }
        // Order: local first (free), then cloud
        val ordered = mutableListOf<LlmProvider>()
        ordered.addAll(available.filter { it == LlmProvider.HERMES_AGENT })
        ordered.addAll(available.filter { it == LlmProvider.OLLAMA_LOCAL })
        ordered.addAll(available.filter { it == LlmProvider.OPENAI_COMPAT_LOCAL })
        ordered.addAll(available.filter { it == LlmProvider.GROQ })
        ordered.addAll(available.filter { it == LlmProvider.OPENROUTER })
        ordered.addAll(available.filter { it == LlmProvider.HUGGINGFACE })
        ordered.addAll(available.filter { it == LlmProvider.CUSTOM })
        return ordered
    }
}
