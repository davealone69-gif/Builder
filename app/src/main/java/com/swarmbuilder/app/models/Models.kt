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

enum class LlmProvider(val displayName: String, val baseUrl: String) {
    HERMES_AGENT("Hermes Agent (local)", "http://localhost:8642/v1"),
    XAI_GROK("xAI Grok", "https://api.x.ai/v1"),
    GROQ("Groq", "https://api.groq.com/openai/v1"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1"),
    HUGGINGFACE("Hugging Face", "https://api-inference.huggingface.co/models"),
    OLLAMA_LOCAL("Ollama (local)", "http://localhost:11434/api"),
    OPENAI_COMPAT_LOCAL("Local OpenAI-Compatible", "http://127.0.0.1:8081/v1"),
    CUSTOM("Custom (your own URL)", "https://api.openai.com/v1");

    val supportsSystemPrompt: Boolean get() = this != HUGGINGFACE

    val requiresApiKey: Boolean
        get() = this == XAI_GROK || this == GROQ || this == HUGGINGFACE ||
            this == OPENROUTER || this == CUSTOM
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

data class UserSettings(
    val groqApiKey: String = "",
    val xaiApiKey: String = "",
    val huggingFaceToken: String = "",
    val openRouterApiKey: String = "",

    val githubToken: String = "",
    val githubUsername: String = "",
    val githubRepoName: String = "",

    val preferredProvider: LlmProvider = LlmProvider.HERMES_AGENT,

    val useLocalOllama: Boolean = false,
    val ollamaModel: String = "llama3",
    val localOpenAiBaseUrl: String = "http://127.0.0.1:8081/v1",
    val localOpenAiModel: String = "",

    val customProviderUrl: String = "",
    val customProviderModel: String = "",
    val customProviderKey: String = "",

    val localFirst: Boolean = false,

    val architectConfig: AgentConfig = AgentConfig(),
    val coderConfig: AgentConfig = AgentConfig(),
    val reviewerConfig: AgentConfig = AgentConfig()
) {

    fun resolveApiKey(provider: LlmProvider, agentOverride: String = ""): String {
        if (agentOverride.isNotBlank()) return agentOverride
        return when (provider) {
            LlmProvider.HERMES_AGENT -> "change-me-local-dev"
            LlmProvider.XAI_GROK -> xaiApiKey
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
            !localOpenAiBaseUrl.contains("localhost")
        ) {
            return localOpenAiBaseUrl.removeSuffix("/v1").removeSuffix("/api").trimEnd('/')
        }
        return provider.baseUrl.trimEnd('/')
    }

    fun isProviderAvailable(provider: LlmProvider): Boolean {
        if (!provider.requiresApiKey) return true
        return resolveApiKey(provider).isNotBlank()
    }

    fun availableProviders(): List<LlmProvider> =
        LlmProvider.values().filter { isProviderAvailable(it) }

    fun getFallbackChain(exclude: LlmProvider): List<LlmProvider> {
        val available = availableProviders().filter { it != exclude }
        val ordered = mutableListOf<LlmProvider>()
        ordered.addAll(available.filter { it == LlmProvider.HERMES_AGENT })
        ordered.addAll(available.filter { it == LlmProvider.OLLAMA_LOCAL })
        ordered.addAll(available.filter { it == LlmProvider.OPENAI_COMPAT_LOCAL })
        ordered.addAll(available.filter { it == LlmProvider.XAI_GROK })
        ordered.addAll(available.filter { it == LlmProvider.GROQ })
        ordered.addAll(available.filter { it == LlmProvider.OPENROUTER })
        ordered.addAll(available.filter { it == LlmProvider.HUGGINGFACE })
        ordered.addAll(available.filter { it == LlmProvider.CUSTOM })
        return ordered
    }
}
