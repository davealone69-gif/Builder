package com.swarmbuilder.app.models

/**
 * Represents a single agent in the AI swarm, backed by a free LLM provider.
 *
 * v3 update: Added jobDescription field (with default) so it doesn't break
 * existing call sites.
 */
data class SwarmAgent(
    val id: String,
    val name: String,
    val role: AgentRole,
    val provider: LlmProvider,
    val modelId: String,
    var status: AgentStatus = AgentStatus.IDLE,
    val jobDescription: String = role.description,   // NEW (with safe default)
    val systemPrompt: String = ""                     // NEW (with safe default)
)

enum class AgentRole(val description: String) {
    ARCHITECT("Designs overall app architecture and file structure"),
    CODER("Writes Kotlin/XML source code for Android"),
    REVIEWER("Reviews generated code for correctness and best practices"),
    BUILDER("Compiles source files and assembles the APK"),
    PUBLISHER("Pushes the project to GitHub")
}

enum class AgentStatus {
    IDLE, RUNNING, DONE, ERROR
}

/**
 * v3: Each agent (Architect / Coder / Reviewer) can have its OWN AI config:
 * - Provider (Groq, Ollama, etc.)
 * - Model name (blank = use provider default)
 * - Base URL (blank = use provider default; e.g. http://192.168.1.5:11434)
 * - API key (blank = use global key from UserSettings)
 *
 * This lets you point different agents at different AIs — e.g. Architect
 * on Groq (fast JSON), Coder on Ollama (free/unlimited), Reviewer on OpenRouter.
 */
data class AgentConfig(
    val provider: LlmProvider = LlmProvider.GROQ,
    val modelId: String = "",
    val baseUrl: String = "",       // empty = use provider's default baseUrl
    val apiKey: String = ""         // empty = use global key from UserSettings
)

enum class LlmProvider(val displayName: String, val baseUrl: String) {
    GROQ("Groq", "https://api.groq.com/openai/v1"),
    HUGGINGFACE("Hugging Face", "https://api-inference.huggingface.co/models"),
    OPENROUTER("OpenRouter (free tier)", "https://openrouter.ai/api/v1"),
    OLLAMA_LOCAL("Ollama (local)", "http://localhost:11434/api"),
    OPENAI_COMPAT_LOCAL("Local OpenAI-Compatible", "http://127.0.0.1:8081/v1"),
    CUSTOM("Custom (your own URL)", "https://api.openai.com/v1");

    /** v3: Does this provider accept a system prompt in chat completions? */
    val supportsSystemPrompt: Boolean
        get() = this != HUGGINGFACE

    /** v3: Does this provider need an API key to work? */
    val requiresApiKey: Boolean
        get() = this == GROQ || this == HUGGINGFACE || this == OPENROUTER || this == CUSTOM
}

data class AppSpec(
    val prompt: String,
    val appName: String = "",
    val packageName: String = "",
    val description: String = "",
    val features: List<String> = emptyList()
)

data class SourceFile(
    val relativePath: String,
    val content: String
)

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
 * v3: UserSettings now supports per-agent AI config.
 * All original fields kept exactly as they were — old code keeps working.
 * New fields added at the end with safe defaults.
 */
data class UserSettings(
    // ─ Original fields (unchanged) ─────────────────
    val groqApiKey: String = "",
    val huggingFaceToken: String = "",
    val openRouterApiKey: String = "",
    val githubToken: String = "",
    val githubUsername: String = "",
    val preferredProvider: LlmProvider = LlmProvider.OPENROUTER,
    val useLocalOllama: Boolean = false,
    val ollamaModel: String = "llama3",
    val localOpenAiBaseUrl: String = "http://127.0.0.1:8081/v1",
    val localOpenAiModel: String = "",

    // ── NEW v3: GitHub repo name (for push button) ──
    val githubRepoName: String = "",

    // ── NEW v4: Custom provider (ANY URL + ANY model + ANY key) ──
    val customProviderUrl: String = "",
    val customProviderModel: String = "",
    val customProviderKey: String = "",

    // ── NEW v3: Per-agent AI overrides ──────────────
    val architectConfig: AgentConfig = AgentConfig(),
    val coderConfig: AgentConfig = AgentConfig(),
    val reviewerConfig: AgentConfig = AgentConfig()
) {

    // ── NEW v3: Helper methods ──────────────────────

    /**
     * Resolve the API key to use for a provider.
     * Priority: agent override > global key > custom provider key.
     */
    fun resolveApiKey(provider: LlmProvider, agentOverride: String = ""): String {
        if (agentOverride.isNotBlank()) return agentOverride
        return when (provider) {
            LlmProvider.GROQ -> groqApiKey
            LlmProvider.HUGGINGFACE -> huggingFaceToken
            LlmProvider.OPENROUTER -> openRouterApiKey
            LlmProvider.CUSTOM -> customProviderKey
            LlmProvider.OLLAMA_LOCAL,
            LlmProvider.OPENAI_COMPAT_LOCAL -> ""  // local = no key needed
        }
    }

    /**
     * Resolve the base URL to use for a provider.
     * Priority: agent override > custom provider URL > provider default.
     * Special case: Ollama on Android needs user's PC LAN IP, not localhost.
     */
    fun resolveBaseUrl(provider: LlmProvider, agentOverride: String = ""): String {
        if (agentOverride.isNotBlank()) return agentOverride.trimEnd('/')
        // Custom provider always uses user-configured URL
        if (provider == LlmProvider.CUSTOM) {
            return customProviderUrl.ifBlank { provider.baseUrl }.trimEnd('/')
        }
        // If Ollama and user configured a non-localhost URL, use that
        if (provider == LlmProvider.OLLAMA_LOCAL &&
            localOpenAiBaseUrl.isNotBlank() &&
            !localOpenAiBaseUrl.contains("127.0.0.1") &&
            !localOpenAiBaseUrl.contains("localhost")) {
            return localOpenAiBaseUrl.removeSuffix("/v1").removeSuffix("/api").trimEnd('/')
        }
        return provider.baseUrl.trimEnd('/')
    }

    /**
     * Check if a provider is actually usable with current settings.
     * Cloud providers need a key; local providers always work.
     */
    fun isProviderAvailable(provider: LlmProvider): Boolean {
        if (!provider.requiresApiKey) return true
        return resolveApiKey(provider).isNotBlank()
    }

    /**
     * Get the list of providers that are currently usable.
     * Used to build fallback chains in LlmClient.
     */
    fun availableProviders(): List<LlmProvider> =
        LlmProvider.values().filter { isProviderAvailable(it) }

    /**
     * Pick the best fallback provider when the preferred one fails.
     * Prefers: another cloud provider with a key > local providers.
     */
    fun pickFallbackProvider(exclude: LlmProvider): LlmProvider? {
        val candidates = availableProviders().filter { it != exclude }
        // Prefer fast cloud providers first
        candidates.firstOrNull { it == LlmProvider.GROQ }?.let { return it }
        candidates.firstOrNull { it == LlmProvider.OPENROUTER }?.let { return it }
        candidates.firstOrNull { it == LlmProvider.HUGGINGFACE }?.let { return it }
        // Then local providers (unlimited but slower)
        candidates.firstOrNull { it == LlmProvider.OLLAMA_LOCAL }?.let { return it }
        candidates.firstOrNull { it == LlmProvider.OPENAI_COMPAT_LOCAL }?.let { return it }
        return null
    }
}
