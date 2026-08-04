package com.swarmbuilder.app.models

/**
 * Represents a single agent in the AI swarm, backed by a free LLM provider.
 */
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
    REVIEWER("Reviews generated code for correctness and best practices"),
    BUILDER("Compiles source files and assembles the APK"),
    PUBLISHER("Pushes the project to GitHub")
}

enum class AgentStatus {
    IDLE, RUNNING, DONE, ERROR
}

enum class LlmProvider(val displayName: String, val baseUrl: String) {
    GROQ("Groq (Llama3)", "https://api.groq.com/openai/v1"),
    HUGGINGFACE("Hugging Face", "https://api-inference.huggingface.co/models"),
    OPENROUTER("OpenRouter (free tier)", "https://openrouter.ai/api/v1"),
    OLLAMA_LOCAL("Ollama (local)", "http://localhost:11434/api")
}

/**
 * Describes the Android app to be built.
 */
data class AppSpec(
    val prompt: String,
    val appName: String = "",
    val packageName: String = "",
    val description: String = "",
    val features: List<String> = emptyList()
)

/**
 * Holds a generated source file.
 */
data class SourceFile(
    val relativePath: String,  // e.g. "app/src/main/java/com/example/MainActivity.kt"
    val content: String
)

/**
 * Overall build result produced by the swarm.
 */
data class BuildResult(
    val success: Boolean,
    val appName: String,
    val apkPath: String? = null,
    val githubUrl: String? = null,
    val errorMessage: String? = null,
    val logs: List<String> = emptyList()
)

/**
 * A single log entry emitted during swarm execution.
 */
data class SwarmLog(
    val agentName: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO,
    val timestamp: Long = System.currentTimeMillis()
)

enum class LogLevel { INFO, SUCCESS, WARNING, ERROR }

/**
 * Settings stored by the user for API keys and GitHub credentials.
 */
data class UserSettings(
    val groqApiKey: String = "",
    val huggingFaceToken: String = "",
    val openRouterApiKey: String = "",
    val githubToken: String = "",
    val githubUsername: String = "",
    val preferredProvider: LlmProvider = LlmProvider.GROQ,
    val useLocalOllama: Boolean = false,
    val ollamaModel: String = "llama3"
)
