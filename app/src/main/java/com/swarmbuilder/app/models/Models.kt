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
