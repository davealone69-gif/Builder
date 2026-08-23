package com.swarmbuilder.app.swarm

import com.swarmbuilder.app.models.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Orchestrates a swarm of specialised AI agents that collaborate to build an
 * Android app from a natural-language prompt.
 *
 * Agent pipeline:
 *   1. ARCHITECT – analyses the prompt and returns a compact JSON spec
 *   2. CODER – generates source code for the requested app
 *   3. REVIEWER – validates/fixes generated code
 *   4. BUILDER – triggers the on-device Gradle build
 *   5. PUBLISHER – pushes the project to GitHub
 */
class SwarmOrchestrator(
    private val settings: UserSettings,
    private val llm: LlmClient = LlmClient(settings)
) {

    private val _logs = MutableSharedFlow<SwarmLog>(replay = 64)
    val logs: SharedFlow<SwarmLog> = _logs

    private suspend fun log(agent: SwarmAgent, msg: String, level: LogLevel = LogLevel.INFO) {
        _logs.emit(SwarmLog(agent.name, msg, level))
    }

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
        val rawFiles = runCoder(coder, spec)
        coder.status = AgentStatus.DONE
        log(coder, "Generated ${rawFiles.size} source files", LogLevel.SUCCESS)

        val reviewer = agents.first { it.role == AgentRole.REVIEWER }
        reviewer.status = AgentStatus.RUNNING
        log(reviewer, "Reviewing generated code…")
        val reviewedFiles = runReviewer(reviewer, spec, rawFiles)
        reviewer.status = AgentStatus.DONE
        log(reviewer, "Code review complete", LogLevel.SUCCESS)

        return reviewedFiles
    }

    private suspend fun runArchitect(agent: SwarmAgent, prompt: String): AppSpec {
        val systemPrompt = """
            You are an expert Android architect. Given a user prompt, return ONLY valid JSON:
            {"appName":"MyApp","packageName":"com.example.myapp","description":"...","features":["..."]}
            Keep the description and feature list concise. No markdown.
        """.trimIndent()

        val response = llm.complete(
            prompt = "Design an Android app for: $prompt",
            systemPrompt = systemPrompt,
            provider = agent.provider,
            modelId = agent.modelId,
            maxOutputTokens = 1200
        )
        return parseAppSpec(prompt, response)
    }

    private suspend fun runCoder(agent: SwarmAgent, spec: AppSpec): List<SourceFile> {
        val systemPrompt = """
            You are an expert Android developer using Kotlin. Generate complete, compilable source
            files. Return ONLY a JSON array of {"path":"...","content":"..."}. No markdown.
            Prefer a small working project over explanations. Include MainActivity.kt, manifest,
            Gradle files and only the files required by the requested features.
        """.trimIndent()

        val prompt = """
            App name: ${spec.appName}
            Package: ${spec.packageName}
            Description: ${spec.description}
            Features: ${spec.features.joinToString(", ")}
        """.trimIndent()

        val response = llm.complete(
            prompt = prompt,
            systemPrompt = systemPrompt,
            provider = agent.provider,
            modelId = agent.modelId,
            maxOutputTokens = 2800
        )
        return parseSourceFiles(response)
    }

    private suspend fun runReviewer(
        agent: SwarmAgent,
        spec: AppSpec,
        files: List<SourceFile>
    ): List<SourceFile> {
        val systemPrompt = """
            You are a senior Android code reviewer. Fix compilation/correctness problems and return
            ONLY a JSON array of {"path":"...","content":"..."}. Preserve working files.
            No markdown or explanation.
        """.trimIndent()

        val filesJson = files.joinToString(",\n", "[", "]") { f ->
            """{"path":"${f.relativePath}","content":${org.json.JSONObject.quote(f.content)}}"""
        }

        val response = llm.complete(
            prompt = "Review and fix these files for app '${spec.appName}':\n$filesJson",
            systemPrompt = systemPrompt,
            provider = agent.provider,
            modelId = agent.modelId,
            maxOutputTokens = 2800
        )
        return try {
            parseSourceFiles(response)
        } catch (e: Exception) {
            files
        }
    }

    private fun parseAppSpec(originalPrompt: String, json: String): AppSpec {
        return try {
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
        } catch (e: Exception) {
            AppSpec(
                prompt = originalPrompt,
                appName = "MyApp",
                packageName = "com.example.myapp",
                description = originalPrompt
            )
        }
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
        val secondary = if (primary == LlmProvider.GROQ) LlmProvider.OPENROUTER else LlmProvider.GROQ
        return listOf(
            SwarmAgent("architect", "Architect", AgentRole.ARCHITECT, primary,
                LlmClient.defaultModelFor(primary)),
            SwarmAgent("coder", "Coder", AgentRole.CODER, primary,
                LlmClient.defaultModelFor(primary)),
            SwarmAgent("reviewer", "Reviewer", AgentRole.REVIEWER, secondary,
                LlmClient.defaultModelFor(secondary)),
            SwarmAgent("builder", "Builder", AgentRole.BUILDER, primary,
                LlmClient.defaultModelFor(primary)),
            SwarmAgent("publisher", "Publisher", AgentRole.PUBLISHER, primary,
                LlmClient.defaultModelFor(primary))
        )
    }
}
