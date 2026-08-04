package com.swarmbuilder.app.swarm

import com.swarmbuilder.app.models.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Orchestrates a swarm of specialised AI agents that collaborate to build an
 * Android app from a natural-language prompt.
 *
 * Agent pipeline:
 *   1. ARCHITECT  – analyses the prompt and returns a JSON spec + file list
 *   2. CODER      – generates source code for each file in the spec
 *   3. REVIEWER   – validates/fixes generated code
 *   4. BUILDER    – triggers the on-device Gradle build
 *   5. PUBLISHER  – pushes the project to GitHub
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

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Run the full swarm pipeline for the given [prompt].
     * Returns a list of [SourceFile] objects that represent the generated app.
     */
    suspend fun run(prompt: String): List<SourceFile> {
        val agents = buildAgents()

        // Step 1 – Architect
        val architect = agents.first { it.role == AgentRole.ARCHITECT }
        architect.status = AgentStatus.RUNNING
        log(architect, "Analysing prompt and designing app architecture…")
        val spec = runArchitect(architect, prompt)
        architect.status = AgentStatus.DONE
        log(architect, "Architecture ready: ${spec.appName}", LogLevel.SUCCESS)

        // Step 2 – Coder
        val coder = agents.first { it.role == AgentRole.CODER }
        coder.status = AgentStatus.RUNNING
        log(coder, "Generating source files…")
        val rawFiles = runCoder(coder, spec)
        coder.status = AgentStatus.DONE
        log(coder, "Generated ${rawFiles.size} source files", LogLevel.SUCCESS)

        // Step 3 – Reviewer
        val reviewer = agents.first { it.role == AgentRole.REVIEWER }
        reviewer.status = AgentStatus.RUNNING
        log(reviewer, "Reviewing generated code…")
        val reviewedFiles = runReviewer(reviewer, spec, rawFiles)
        reviewer.status = AgentStatus.DONE
        log(reviewer, "Code review complete", LogLevel.SUCCESS)

        return reviewedFiles
    }

    // ── Agent runners ─────────────────────────────────────────────────────────

    private suspend fun runArchitect(agent: SwarmAgent, prompt: String): AppSpec {
        val systemPrompt = """
            You are an expert Android architect. Given a user prompt, return a JSON object with:
            {
              "appName": "MyApp",
              "packageName": "com.example.myapp",
              "description": "...",
              "features": ["...", "..."]
            }
            Return ONLY valid JSON, no markdown fences.
        """.trimIndent()

        val response = llm.complete(
            prompt = "Design an Android app for: $prompt",
            systemPrompt = systemPrompt,
            provider = agent.provider,
            modelId = agent.modelId
        )
        return parseAppSpec(prompt, response)
    }

    private suspend fun runCoder(agent: SwarmAgent, spec: AppSpec): List<SourceFile> {
        val systemPrompt = """
            You are an expert Android developer using Kotlin. Generate complete, compilable source
            files for an Android app. Return a JSON array where each element is:
            {
              "path": "app/src/main/java/com/example/MainActivity.kt",
              "content": "... full file content ..."
            }
            Include: MainActivity.kt, AndroidManifest.xml, activity_main.xml,
            build.gradle (app), settings.gradle, and any additional files needed.
            Return ONLY a valid JSON array, no markdown fences or explanation.
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
            modelId = agent.modelId
        )
        return parseSourceFiles(response)
    }

    private suspend fun runReviewer(
        agent: SwarmAgent,
        spec: AppSpec,
        files: List<SourceFile>
    ): List<SourceFile> {
        val systemPrompt = """
            You are a senior Android code reviewer. Given a list of source files for an Android
            app, review them for correctness, fix any compilation errors, and return the corrected
            files as a JSON array with the same structure:
            [{ "path": "...", "content": "..." }]
            Return ONLY valid JSON, no markdown or explanation.
        """.trimIndent()

        val filesJson = files.joinToString(",\n", "[", "]") { f ->
            """{"path":"${f.relativePath}","content":${org.json.JSONObject.quote(f.content)}}"""
        }

        val response = llm.complete(
            prompt = "Review and fix these files for app '${spec.appName}':\n$filesJson",
            systemPrompt = systemPrompt,
            provider = agent.provider,
            modelId = agent.modelId
        )
        return try {
            parseSourceFiles(response)
        } catch (e: Exception) {
            // If reviewer fails, return original files unchanged
            files
        }
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

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

    // ── Agent factory ─────────────────────────────────────────────────────────

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
