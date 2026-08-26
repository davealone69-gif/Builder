package com.swarmbuilder.app.swarm

import com.swarmbuilder.app.models.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * SwarmBuilder v3 Orchestrator
 *
 * Each agent uses settings.preferredProvider with the correct model from LlmClient.
 * LlmClient handles auto-fallback when the preferred provider fails.
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

    suspend fun run(prompt: String): List<SourceFile> {
        val agents = buildAgents()

        // ─ Architect ──────────────────────────────────
        val architect = agents.first { it.role == AgentRole.ARCHITECT }
        architect.status = AgentStatus.RUNNING
        log(architect, "Analysing prompt and designing app architecture…")
        val spec = runArchitect(architect, prompt)
        architect.status = AgentStatus.DONE
        log(architect, "Architecture ready: ${spec.appName}", LogLevel.SUCCESS)

        // ── Coder ────────────────────────────────────
        val coder = agents.first { it.role == AgentRole.CODER }
        coder.status = AgentStatus.RUNNING
        log(coder, "Generating source files…")
        log(coder, "Using: ${coder.provider.displayName}, model=${coder.modelId}")
        val files = runCoder(coder, spec)
        coder.status = AgentStatus.DONE
        log(coder, "Generated ${files.size} source files", LogLevel.SUCCESS)

        return files
    }

    suspend fun repair(
        spec: AppSpec,
        files: List<SourceFile>,
        buildError: String,
        attempt: Int
    ): List<SourceFile> {
        val agents = buildAgents()
        val reviewer = agents.first { it.role == AgentRole.REVIEWER }
        reviewer.status = AgentStatus.RUNNING
        log(reviewer, "Compiler failure detected. Repair pass $attempt…")
        log(reviewer, "Using: ${reviewer.provider.displayName}, model=${reviewer.modelId}")

        val error = buildError.takeLast(9000)
        val filesJson = files.joinToString(",\n", "[", "]") { f ->
            """{"path":"${f.relativePath}","content":${JSONObject.quote(f.content)}}"""
        }
        val system = buildSystemPrompt(AgentRole.REVIEWER)
        val prompt = """
            App: ${spec.appName}
            Gradle error:
            $error
            Current files:
            $filesJson
        """.trimIndent()

        return try {
            val response = llm.complete(
                prompt = prompt,
                systemPrompt = system,
                provider = reviewer.provider,
                modelId = reviewer.modelId,
                maxOutputTokens = 2200
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

    private suspend fun runArchitect(agent: SwarmAgent, prompt: String): AppSpec {
        val system = buildSystemPrompt(AgentRole.ARCHITECT)
        val response = llm.complete(
            prompt = "Design an Android app for: $prompt",
            systemPrompt = system,
            provider = agent.provider,
            modelId = agent.modelId,
            maxOutputTokens = 900
        )
        return parseAppSpec(prompt, response)
    }

    private suspend fun runCoder(agent: SwarmAgent, spec: AppSpec): List<SourceFile> {
        val system = buildSystemPrompt(AgentRole.CODER)
        val prompt = """
            App: ${spec.appName}
            Package: ${spec.packageName}
            Description: ${spec.description}
            Features: ${spec.features.joinToString(", ")}
        """.trimIndent()

        val response = llm.complete(
            prompt = prompt,
            systemPrompt = system,
            provider = agent.provider,
            modelId = agent.modelId,
            maxOutputTokens = 3000
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
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) return cleaned.substring(start, end + 1)
        throw IllegalArgumentException("No JSON object found in model response")
    }

    private fun parseSourceFiles(json: String): List<SourceFile> {
        val cleaned = json.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) {
            throw IllegalArgumentException(
                "No JSON file array found in response. Preview: ${cleaned.take(300)}"
            )
        }
        val arr = JSONArray(cleaned.substring(start, end + 1))
        return buildList {
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val path = obj.optString("path", "UnknownPath$i.kt")
                    val content = obj.optString("content", "")
                    if (content.isNotBlank()) {
                        add(SourceFile(path, content))
                    }
                } catch (_: Exception) { }
            }
        }
    }

    /**
     * Build agents with the correct provider/model from settings.
     */
    private fun buildAgents(): List<SwarmAgent> {
        val provider = settings.preferredProvider
        val model = LlmClient.defaultModelFor(provider, settings)
        return listOf(
            SwarmAgent("architect", "Architect", AgentRole.ARCHITECT, provider, model),
            SwarmAgent("coder", "Coder", AgentRole.CODER, provider, model),
            SwarmAgent("reviewer", "Reviewer", AgentRole.REVIEWER, provider, model),
            SwarmAgent("builder", "Builder", AgentRole.BUILDER, provider, model),
            SwarmAgent("publisher", "Publisher", AgentRole.PUBLISHER, provider, model)
        )
    }

    /**
     * System prompts for each agent role — instructions the AI reads.
     */
    private fun buildSystemPrompt(role: AgentRole): String = when (role) {
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
