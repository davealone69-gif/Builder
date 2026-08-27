package com.swarmbuilder.app.swarm

import android.util.Log
import com.swarmbuilder.app.models.LlmProvider
import com.swarmbuilder.app.models.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v5 LLM Client with robust automatic fallback.
 *
 * When a provider fails (connection error, invalid key, model not found),
 * the client automatically tries the next provider in the fallback chain.
 * This means the app works even if the user's saved provider is broken.
 */
class LlmClient(private val settings: UserSettings) {

    companion object {
        private const val TAG = "LlmClient"

        /** Current working Groq models (August 2026). */
        const val GROQ_DEFAULT_MODEL = "openai/gpt-oss-120b"
        val GROQ_FALLBACK_MODELS = listOf("openai/gpt-oss-20b", "qwen/qwen3.6-27b")

        fun defaultModelFor(provider: LlmProvider, settings: UserSettings): String = when (provider) {
            LlmProvider.HERMES_AGENT -> "hermes-agent"
            LlmProvider.GROQ -> GROQ_DEFAULT_MODEL
            LlmProvider.HUGGINGFACE -> "mistralai/Mistral-7B-Instruct-v0.3"
            LlmProvider.OPENROUTER -> "mistralai/mistral-7b-instruct:free"
            LlmProvider.OLLAMA_LOCAL -> settings.ollamaModel.ifBlank { "llama3" }
            LlmProvider.OPENAI_COMPAT_LOCAL -> settings.localOpenAiModel.ifBlank { "local-model" }
            LlmProvider.CUSTOM -> settings.customProviderModel.ifBlank { "" }
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)   // Shorter timeout for local
        .readTimeout(120, TimeUnit.SECONDS)     // Longer read for code generation
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Main entry point. Tries the requested provider, then falls back
     * through the chain automatically.
     */
    suspend fun complete(
        prompt: String,
        systemPrompt: String = "You are an expert Android developer.",
        provider: LlmProvider = settings.preferredProvider,
        modelId: String = defaultModelFor(provider, settings),
        maxOutputTokens: Int = 2048,
        useLocalFirst: Boolean = settings.localFirst
    ): String = withContext(Dispatchers.IO) {
        // Build the attempt chain: requested provider first, then fallbacks
        val attemptChain = mutableListOf<Pair<LlmProvider, String>>()
        attemptChain.add(Pair(provider, modelId))

        // Add fallback providers
        val fallbacks = settings.getFallbackChain(provider)
        for (fb in fallbacks) {
            val fbModel = defaultModelFor(fb, settings)
            attemptChain.add(Pair(fb, fbModel))
        }

        var lastError: String? = null
        for ((attemptProvider, attemptModel) in attemptChain) {
            try {
                Log.i(TAG, "Trying ${attemptProvider.displayName} (model=$attemptModel)")
                val result = tryProvider(attemptProvider, attemptModel, prompt, systemPrompt, maxOutputTokens, useLocalFirst)
                if (attemptProvider != provider) {
                    Log.i(TAG, "Fallback to ${attemptProvider.displayName} succeeded")
                }
                return@withContext result
            } catch (e: Exception) {
                lastError = "${attemptProvider.displayName}: ${e.message}"
                Log.w(TAG, "${attemptProvider.displayName} failed: ${e.message}")

                // Don't retry on auth errors (bad API key) — only on connection/model errors
                val msg = e.message ?: ""
                if (msg.contains("Invalid API Key", ignoreCase = true) ||
                    msg.contains("invalid_api_key", ignoreCase = true)) {
                    // Auth error — skip other cloud providers, try local only
                    continue
                }
                // For other errors (connection, model not found, rate limit), try next
                continue
            }
        }

        throw RuntimeException(
            "All providers failed. Last error: $lastError\n\n" +
            "Tips:\n" +
            "• Make sure Hermes-agent is running on your device\n" +
            "• Or enter a valid API key for Groq/OpenRouter in Settings"
        )
    }

    /**
     * Try a single provider. Throws if it fails.
     */
    private suspend fun tryProvider(
        provider: LlmProvider,
        modelId: String,
        prompt: String,
        systemPrompt: String,
        maxOutputTokens: Int,
        useLocalFirst: Boolean
    ): String {
        // Local-first: try Ollama before the cloud provider
        if (useLocalFirst && provider != LlmProvider.OLLAMA_LOCAL && provider != LlmProvider.HERMES_AGENT) {
            try {
                Log.i(TAG, "Local-first: trying Ollama before $provider")
                return ollamaComplete(prompt, systemPrompt, settings.ollamaModel.ifBlank { "llama3" })
            } catch (e: Exception) {
                Log.w(TAG, "Local-first Ollama failed: ${e.message}")
            }
        }

        return when (provider) {
            LlmProvider.HERMES_AGENT -> hermesAgentComplete(prompt, systemPrompt, modelId, maxOutputTokens)
            LlmProvider.GROQ -> groqComplete(prompt, systemPrompt, modelId, maxOutputTokens)
            LlmProvider.OPENROUTER -> openRouterComplete(prompt, systemPrompt, modelId, maxOutputTokens)
            LlmProvider.HUGGINGFACE -> hfComplete(prompt, modelId, maxOutputTokens)
            LlmProvider.OLLAMA_LOCAL -> ollamaComplete(prompt, systemPrompt, modelId)
            LlmProvider.OPENAI_COMPAT_LOCAL -> localOpenAiComplete(prompt, systemPrompt, modelId, maxOutputTokens)
            LlmProvider.CUSTOM -> customComplete(prompt, systemPrompt, modelId, maxOutputTokens)
        }
    }

    // ─────────────────────────────────────────────────
    // Provider implementations
    // ─────────────────────────────────────────────────

    /**
     * Hermes-agent: runs locally on the device at localhost:8642
     * Built-in API key: "change-me-local-dev"
     * No user configuration needed — works out of the box.
     */
    private suspend fun hermesAgentComplete(prompt: String, system: String, model: String, maxOutputTokens: Int): String {
        val baseUrl = settings.resolveBaseUrl(LlmProvider.HERMES_AGENT)
        val resolvedModel = if (model.isNotBlank()) model else "hermes-agent"
        val apiKey = "change-me-local-dev"

        val body = chatBody(prompt, system, resolvedModel, maxOutputTokens).toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body).build()

        return executeAndExtractContentWithRetry(req, "Hermes Agent", resolvedModel)
    }

    private fun resolveGroqModel(configured: String): String = when {
        configured.isBlank() -> GROQ_DEFAULT_MODEL
        configured in listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b", "llama-3.3-70b-versatile", "llama3-70b-8192") -> GROQ_DEFAULT_MODEL
        else -> configured
    }

    private suspend fun groqComplete(prompt: String, system: String, model: String, maxOutputTokens: Int): String {
        val resolvedModel = resolveGroqModel(model)
        val modelsToTry = buildList {
            add(resolvedModel)
            addAll(GROQ_FALLBACK_MODELS.filter { it != resolvedModel })
        }

        var lastError: String? = null
        for (m in modelsToTry) {
            try {
                val body = chatBody(prompt, system, m, maxOutputTokens).toRequestBody(jsonMedia)
                val req = Request.Builder()
                    .url("${LlmProvider.GROQ.baseUrl}/chat/completions")
                    .addHeader("Authorization", "Bearer ${settings.groqApiKey}")
                    .post(body).build()
                return executeAndExtractContentWithRetry(req, "Groq", m)
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("model_not_found", true) || msg.contains("does not exist", true)) {
                    lastError = msg; continue
                }
                throw e // auth errors, rate limits propagate immediately
            }
        }
        throw RuntimeException("All Groq models failed. Last error: $lastError")
    }

    private suspend fun hfComplete(prompt: String, model: String, maxOutputTokens: Int): String {
        val body = JSONObject().apply {
            put("inputs", prompt)
            put("parameters", JSONObject().apply {
                put("max_new_tokens", maxOutputTokens.coerceIn(512, 2048))
                put("temperature", 0.2)
                put("return_full_text", false)
            })
        }.toString().toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("${LlmProvider.HUGGINGFACE.baseUrl}/$model")
            .addHeader("Authorization", "Bearer ${settings.huggingFaceToken}")
            .post(body).build()
        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string()?.trim().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HF error ${resp.code}: $raw".take(300))
            if (raw.isBlank()) throw RuntimeException("HF returned an empty response")
            try { JSONArray(raw).getJSONObject(0).getString("generated_text") }
            catch (e: Exception) { throw RuntimeException("HF unexpected JSON: ${raw.take(300)}", e) }
        }
    }

    private suspend fun openRouterComplete(prompt: String, system: String, model: String, maxOutputTokens: Int): String {
        val body = chatBody(prompt, system, model, maxOutputTokens).toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("${LlmProvider.OPENROUTER.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.openRouterApiKey}")
            .addHeader("HTTP-Referer", "https://github.com/davealone69-gif/Builder")
            .post(body).build()
        return executeAndExtractContentWithRetry(req, "OpenRouter", model)
    }

    private suspend fun localOpenAiComplete(prompt: String, system: String, model: String, maxOutputTokens: Int): String {
        val baseUrl = settings.localOpenAiBaseUrl.trim().trimEnd('/')
        val resolvedModel = resolveLocalModel(baseUrl, model)
        val body = chatBody(prompt, system, resolvedModel, maxOutputTokens).toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body).build()
        return executeAndExtractContentWithRetry(req, "Local OpenAI", resolvedModel)
    }

    private fun resolveLocalModel(baseUrl: String, configured: String): String {
        if (configured.isNotBlank() && configured != "local-model") return configured
        return try {
            val req = Request.Builder().url("$baseUrl/models").get().build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()?.trim().orEmpty()
                if (!resp.isSuccessful || raw.isBlank()) return configured.ifBlank { "local-model" }
                val data = JSONObject(raw).optJSONArray("data") ?: return configured.ifBlank { "local-model" }
                if (data.length() == 0) configured.ifBlank { "local-model" }
                else data.getJSONObject(0).optString("id").ifBlank { configured.ifBlank { "local-model" } }
            }
        } catch (_: Exception) { configured.ifBlank { "local-model" } }
    }

    private suspend fun customComplete(prompt: String, system: String, model: String, maxOutputTokens: Int): String {
        val baseUrl = settings.resolveBaseUrl(LlmProvider.CUSTOM)
        val apiKey = settings.resolveApiKey(LlmProvider.CUSTOM)
        val resolvedModel = if (model.isNotBlank()) model else settings.customProviderModel

        if (baseUrl.isBlank()) throw RuntimeException("Custom provider URL is blank. Go to Settings.")
        if (resolvedModel.isBlank()) throw RuntimeException("Custom provider model is blank. Go to Settings.")

        val body = chatBody(prompt, system, resolvedModel, maxOutputTokens).toRequestBody(jsonMedia)
        val reqBuilder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body)
        if (apiKey.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $apiKey")

        return executeAndExtractContentWithRetry(reqBuilder.build(), "Custom", resolvedModel)
    }

    private suspend fun ollamaComplete(prompt: String, system: String, model: String): String {
        val resolvedModel = model.ifBlank { settings.ollamaModel.ifBlank { "llama3" } }
        try { return ollamaChat(resolvedModel, prompt, system) }
        catch (e: Exception) { return ollamaGenerate(resolvedModel, prompt, system) }
    }

    private suspend fun ollamaChat(model: String, prompt: String, system: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", system) })
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            })
            put("stream", false)
            put("options", JSONObject().apply { put("num_predict", 2048); put("temperature", 0.2) })
        }.toString().toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("${LlmProvider.OLLAMA_LOCAL.baseUrl}/chat")
            .post(body).build()
        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string()?.trim().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Ollama HTTP ${resp.code}: $raw".take(300))
            if (raw.isBlank()) throw RuntimeException("Ollama returned empty response")
            try {
                val json = JSONObject(raw)
                val content = json.optJSONObject("message")?.optString("content")?.trim().orEmpty()
                if (content.isNotBlank()) return@use content
                json.optString("response").ifBlank { throw RuntimeException("Ollama no content. Raw: ${raw.take(200)}") }
            } catch (e: RuntimeException) { throw e }
            catch (e: Exception) { throw RuntimeException("Ollama invalid JSON: ${raw.take(200)}", e) }
        }
    }

    private suspend fun ollamaGenerate(model: String, prompt: String, system: String): String {
        val body = JSONObject().apply {
            put("model", model); put("prompt", "$system\n\n$prompt"); put("stream", false)
        }.toString().toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("${LlmProvider.OLLAMA_LOCAL.baseUrl}/generate")
            .post(body).build()
        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string()?.trim().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Ollama HTTP ${resp.code}: $raw".take(300))
            if (raw.isBlank()) throw RuntimeException("Ollama returned empty response")
            JSONObject(raw).optString("response").ifBlank { throw RuntimeException("Ollama no content. Raw: ${raw.take(200)}") }
        }
    }

    // ─────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────

    private fun chatBody(prompt: String, system: String, model: String, maxOutputTokens: Int): String =
        JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", system) })
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            })
            put("max_tokens", maxOutputTokens.coerceIn(512, 4096))
            put("temperature", 0.2)
        }.toString()

    private suspend fun executeAndExtractContentWithRetry(
        req: Request, providerName: String, model: String
    ): String {
        var lastError = "Unknown LLM error"
        repeat(5) { attempt ->
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()?.trim().orEmpty()
                if (resp.isSuccessful) {
                    if (raw.isBlank()) {
                        lastError = "$providerName (model=$model) returned empty response"
                        if (attempt < 4) { delay(1000L * (attempt + 1)); return@use }
                        throw RuntimeException(lastError)
                    }
                    return extractChatContent(raw, providerName, model)
                }
                lastError = "$providerName (model=$model) HTTP ${resp.code}: ${raw.ifBlank { "empty" }.take(200)}"
                // Rate limit → wait and retry
                if (resp.code == 429 && attempt < 4) {
                    val retryAfter = resp.header("retry-after")?.toDoubleOrNull()
                    delay(((retryAfter ?: 5.0) * 1000).toLong()); return@use
                }
                // 4xx = client error (bad key, bad model) — don't retry
                if (resp.code in 400..499) throw RuntimeException(lastError)
                // 5xx = server error — retry
                if (attempt < 4) { delay(1000L * (attempt + 1)); return@use }
                throw RuntimeException(lastError)
            }
        }
        throw RuntimeException(lastError)
    }

    private fun extractChatContent(raw: String, providerName: String, model: String): String {
        return try {
            val json = JSONObject(raw)
            // Check for error objects first
            val errorObj = json.optJSONObject("error")
            if (errorObj != null) {
                val errorMsg = errorObj.optString("message", errorObj.toString().take(200))
                throw RuntimeException("$providerName API error (model=$model): $errorMsg")
            }
            // Extract content from OpenAI-compatible response
            val content = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()
            if (content.isNotBlank()) return content

            // Build helpful diagnostic
            val keys = buildList { val k = json.keys(); while (k.hasNext()) add(k.next()) }
            throw RuntimeException(
                "$providerName returned JSON without content field.\n" +
                "Model: $model\n" +
                "Keys: ${keys.joinToString(", ")}\n" +
                "Preview: ${raw.take(300).replace("\n", " ")}"
            )
        } catch (e: RuntimeException) { throw e }
        catch (e: Exception) { throw RuntimeException("$providerName returned non-JSON: ${raw.take(300)}") }
    }
}
