package com.swarmbuilder.app.swarm

import com.swarmbuilder.app.models.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Cost-aware swarm: Architect -> Coder -> real Gradle compiler -> targeted AI repair. */
class SwarmOrchestrator(
    private val settings: UserSettings,
    private val llm: LlmClient = LlmClient(settings)
) {
    private val _logs = MutableSharedFlow<SwarmLog>(replay = 128)
    val logs: SharedFlow<SwarmLog> = _logs

    private suspend fun log(agent: SwarmAgent, msg: String, level: LogLevel = LogLevel.INFO) {
        _logs.emit(SwarmLog(agent.name, msg, level))
    }

    /** First pass uses only Architect + Coder. */
    suspend fun run(prompt: String): List<SourceFile> {
        val agents = buildAgents()
        val architect = agents.first { it.role == AgentRole.ARCHITECT }
        architect.status = AgentStatus.RUNNING
        log(architect, "Analysing prompt and designing app architecture…")
        val spec = runArchitect(architect, prompt)
        architect.status = AgentStatus.DONE
        log(architect, "Architecture ready: ${spec.appName}", LogLevel.SUCCESS)

        val coder = agents.first { it.role == AgentRole.CODER }
        coder.status = AgentStatus.RUNNING
        log(coder, "Generating source files…")
        val files = runCoder(coder, spec)
        coder.status = AgentStatus.DONE
        log(coder, "Generated ${files.size} source files", LogLevel.SUCCESS)
        return files
    }

    /** Spend another LLM call only after Gradle has actually failed. */
    suspend fun repair(
        spec: AppSpec,
        files: List<SourceFile>,
        buildError: String,
        attempt: Int
    ): List<SourceFile> {
        val agent = SwarmAgent(
            "repair-$attempt", "Repair", AgentRole.REVIEWER,
            settings.preferredProvider, LlmClient.defaultModelFor(settings.preferredProvider, settings)
        )
        agent.status = AgentStatus.RUNNING
        log(agent, "Compiler failure detected. Repair pass $attempt…")

        val error = buildError.takeLast(9000)
        val filesJson = files.joinToString(",\n", "[", "]") { f ->
            """{"path":"${f.relativePath}","content":${org.json.JSONObject.quote(f.content)}}"""
        }
        val system = """
            You are an Android build-fix agent. Fix ONLY errors indicated by Gradle output.
            Return ONLY a JSON array of {"path":"...","content":"..."}.
            Preserve working files and requested features. No explanation.
        """.trimIndent()
        val prompt = """
            App: ${spec.appName}
            Gradle error:
            $error
            Current files:
            $filesJson
        """.trimIndent()

        val response = llm.complete(
            prompt = prompt,
            systemPrompt = system,
            provider = agent.provider,
            modelId = agent.modelId,
            maxOutputTokens = 2200
        )
        return try {
            parseSourceFiles(response).also {
                agent.status = AgentStatus.DONE
                log(agent, "Repair pass $attempt produced ${it.size} files", LogLevel.SUCCESS)
            }
        } catch (e: Exception) {
            agent.status = AgentStatus.ERROR
            log(agent, "Invalid repair response: ${e.message}", LogLevel.ERROR)
            files
        }
    }

    private suspend fun runArchitect(agent: SwarmAgent, prompt: String): AppSpec {
        val system = """
            Expert Android architect. Return ONLY compact valid JSON:
            {"appName":"MyApp","packageName":"com.example.myapp","description":"...","features":["..."]}
            Keep description/features concise. No markdown.
        """.trimIndent()
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
        val system = """
            Expert Android developer. Generate a SMALL, COMPLETE, COMPILABLE Kotlin Android project.
            Return ONLY JSON array of {"path":"...","content":"..."}. No markdown.
            Include only files required for requested features.
        """.trimIndent()
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
            maxOutputTokens = 2800
        )
        return parseSourceFiles(response)
    }

    private fun parseAppSpec(originalPrompt: String, json: String): AppSpec = try {
        val obj = org.json.JSONObject(json.trim())
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

    private fun parseSourceFiles(json: String): List<SourceFile> {
        val cleaned = json.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = org.json.JSONArray(cleaned)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(SourceFile(obj.getString("path"), obj.getString("content")))
            }
        }
    }

    private fun buildAgents(): List<SwarmAgent> {
        val primary = settings.preferredProvider
        return listOf(
            SwarmAgent("architect", "Architect", AgentRole.ARCHITECT, primary, LlmClient.defaultModelFor(primary, settings)),
            SwarmAgent("coder", "Coder", AgentRole.CODER, primary, LlmClient.defaultModelFor(primary, settings)),
            SwarmAgent("reviewer", "Repair", AgentRole.REVIEWER, primary, LlmClient.defaultModelFor(primary, settings)),
            SwarmAgent("builder", "Builder", AgentRole.BUILDER, primary, LlmClient.defaultModelFor(primary, settings)),
            SwarmAgent("publisher", "Publisher", AgentRole.PUBLISHER, primary, LlmClient.defaultModelFor(primary, settings))
        )
    }
}
