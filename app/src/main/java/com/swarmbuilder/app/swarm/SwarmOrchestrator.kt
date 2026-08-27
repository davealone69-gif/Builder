package com.swarmbuilder.app.swarm

import com.swarmbuilder.app.models.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * v4 SwarmOrchestrator with:
 * - Per-agent AI configs (Architect / Coder / Reviewer)
 * - Custom system prompts per agent
 * - Local-first routing (when settings.localFirst = true)
 */
class SwarmOrchestrator(
    private val settings: UserSettings,
    private val llm: LlmClient = LlmClient(settings)
) {
    private val _logs = MutableSharedFlow<SwarmLog>(replay = 256)
    val logs: SharedFlow<SwarmLog> = _logs

    private suspend fun log(agent: SwarmAgent, msg: String, level: LogLevel = LogLevel.INFO) {
        _logs.emit(SwarmLog(agent.name, msg, level))
    }

    /**
     * Resolve system prompt for an agent:
     * custom override > hardcoded default
     */
    private fun getSystemPrompt(role: AgentRole, customOverride: String = ""): String {
        if (customOverride.isNotBlank()) return customOverride
        return buildDefaultSystemPrompt(role)
    }

    suspend fun run(prompt: String): List<SourceFile> {
        // ─ Architect ────────────────────────────────
        val archConfig = settings.architectConfig
        val archProvider = archConfig.provider
        val archModel = archConfig.modelId.ifBlank { LlmClient.defaultModelFor(archProvider, settings) }
        val architect = SwarmAgent("architect", "Architect", AgentRole.ARCHITECT, archProvider, archModel)

        architect.status = AgentStatus.RUNNING
        log(architect, "Analysing prompt and designing app architecture…")
        log(architect, "Provider: ${archProvider.displayName}, Model: $archModel")
        if (settings.localFirst) log(architect, "Local-first: ON (trying Ollama first)")

        val spec = runArchitect(architect, prompt, archConfig)
        architect.status = AgentStatus.DONE
        log(architect, "Architecture ready: ${spec.appName}", LogLevel.SUCCESS)

        // ── Coder ───────────────────────────────────
        val coderConfig = settings.coderConfig
        val coderProvider = coderConfig.provider
        val coderModel = coderConfig.modelId.ifBlank { LlmClient.defaultModelFor(coderProvider, settings) }
        val coder = SwarmAgent("coder", "Coder", AgentRole.CODER, coderProvider, coderModel)

        coder.status = AgentStatus.RUNNING
        log(coder, "Generating source files…")
        log(coder, "Provider: ${coderProvider.displayName}, Model: $coderModel")

        val files = runCoder(coder, spec, coderConfig)
        coder.status = AgentStatus.DONE
        log(coder, "Generated ${files.size} source files", LogLevel.SUCCESS)

        return files
    }

    suspend fun repair(
        spec: AppSpec, files: List<SourceFile>, buildError: String, attempt: Int
    ): List<SourceFile> {
        val revConfig = settings.reviewerConfig
        val revProvider = revConfig.provider
        val revModel = revConfig.modelId.ifBlank { LlmClient.defaultModelFor(revProvider, settings) }
        val reviewer = SwarmAgent("reviewer", "Reviewer", AgentRole.REVIEWER, revProvider, revModel)

        reviewer.status = AgentStatus.RUNNING
        log(reviewer, "Compiler failure detected. Repair pass $attempt…")
        log(reviewer, "Provider: ${revProvider.displayName}, Model: $revModel")

        val error = buildError.takeLast(9000)
        val filesJson = files.joinToString(",\n", "[", "]") { f ->
            """{"path":"${f.relativePath}","content":${JSONObject.quote(f.content)}}"""
        }
        val system = getSystemPrompt(AgentRole.REVIEWER, revConfig.systemPrompt)
        val prompt = """
            App: ${spec.appName}
            Gradle error:
            $error
            Current files:
            $filesJson
        """.trimIndent()

        return try {
            val response = llm.complete(
                prompt = prompt, systemPrompt = system,
                provider = revProvider, modelId = revModel,
                maxOutputTokens = 2200, useLocalFirst = settings.localFirst
            )
            parseSourceFiles(response).also {
                reviewer.status = AgentStatus.DONE
                log(reviewer, "Repair pass $attempt produced ${it.size} files", LogLevel.SUCCESS)
            }
        } catch (e: Exception) {
            reviewer.status = AgentStatus.ERROR
            log(reviewer, "Repair response failed: ${e.message}. Keeping last files.", LogLevel.WARNING)
            files
        }
    }

    private suspend fun runArchitect(agent: SwarmAgent, prompt: String, config: AgentConfig): AppSpec {
        val system = getSystemPrompt(AgentRole.ARCHITECT, config.systemPrompt)
        val response = llm.complete(
            prompt = "Design an Android app for: $prompt",
            systemPrompt = system,
            provider = agent.provider, modelId = agent.modelId,
            maxOutputTokens = 900, useLocalFirst = settings.localFirst
        )
        return parseAppSpec(prompt, response)
    }

    private suspend fun runCoder(agent: SwarmAgent, spec: AppSpec, config: AgentConfig): List<SourceFile> {
        val system = getSystemPrompt(AgentRole.CODER, config.systemPrompt)
        val prompt = """
            App: ${spec.appName}
            Package: ${spec.packageName}
            Description: ${spec.description}
            Features: ${spec.features.joinToString(", ")}
        """.trimIndent()
        val response = llm.complete(
            prompt = prompt, systemPrompt = system,
            provider = agent.provider, modelId = agent.modelId,
            maxOutputTokens = 3000, useLocalFirst = settings.localFirst
        )
        return parseSourceFiles(response)
    }

    // ─── Parsing ──────────────────────────────────────

    private fun parseAppSpec(originalPrompt: String, json: String): AppSpec {
        return try {
            val cleaned = recoverJsonObject(json)
            val obj = JSONObject(cleaned)
            AppSpec(
                prompt = originalPrompt,
                appName = obj.optString("appName", "MyApp"),
                packageName = obj.optString("packageName", "com.example.myapp"),
                description = obj.optString("description", ""),
                features = buildList {
                    val arr = obj.optJSONArray("features") ?: return@buildList
                    for (i in 0 until arr.length()) add(arr.getString(i))
                }
            )
        } catch (_: Exception) {
            AppSpec(originalPrompt, "MyApp", "com.example.myapp", originalPrompt)
        }
    }

    private fun recoverJsonObject(raw: String): String {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        val start = cleaned.indexOf('{'); val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) return cleaned.substring(start, end + 1)
        throw IllegalArgumentException("No JSON object found in model response")
    }

    private fun parseSourceFiles(json: String): List<SourceFile> {
        val cleaned = json.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        val start = cleaned.indexOf('['); val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) {
            throw IllegalArgumentException("No JSON file array found. Preview: ${cleaned.take(300)}")
        }
        val arr = JSONArray(cleaned.substring(start, end + 1))
        return buildList {
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val path = obj.optString("path", "UnknownPath$i.kt")
                    val content = obj.optString("content", "")
                    if (content.isNotBlank()) add(SourceFile(path, content))
                } catch (_: Exception) { }
            }
        }
    }

    /**
     * Default system prompts for each agent role.
     * Used when the agent's custom systemPrompt is blank.
     */
    private fun buildDefaultSystemPrompt(role: AgentRole): String = when (role) {
        AgentRole.ARCHITECT -> """
            You are an expert Android app architect.
            Given a user's app idea, return ONLY a compact valid JSON object:
            {"appName":"MyApp","packageName":"com.example.myapp","description":"...","features":["feature1","feature2"]}
            - appName: short, camelCase name
            - packageName: reverse-domain format
            - description: one sentence
            - features: 3-6 key features
            No markdown, no explanation, just JSON.
        """.trimIndent()

        AgentRole.CODER -> """
            You are an expert Android developer. Generate a SMALL, COMPLETE, COMPILABLE Kotlin Android project.
            Return ONLY a JSON array of {"path":"relative/path/file.kt","content":"...file content..."}.
            
            Rules:
            - Use Material Design 3 and Jetpack Compose
            - Use Kotlin coroutines for async work
            - Keep it minimal — only files needed for requested features
            - Include AndroidManifest.xml, build.gradle, MainActivity.kt at minimum
            - All code must compile on API 26+
            - No markdown, no explanation, just the JSON array
        """.trimIndent()

        AgentRole.REVIEWER -> """
            You are an Android build-fix agent. You receive Gradle build errors and the current source files.
            Fix ONLY the errors indicated by the compiler output.
            Return ONLY a JSON array of {"path":"relative/path/file.kt","content":"...fixed content..."}.
            Preserve working files and requested features. No explanation, just the JSON array.
        """.trimIndent()

        else -> "You are a helpful Android assistant."
    }
}
