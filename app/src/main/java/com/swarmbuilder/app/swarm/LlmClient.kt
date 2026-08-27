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
 * v4 LLM Client with local-first routing.
 *
 * When settings.localFirst = true, tries Ollama first for EVERY request.
 * Only falls back to the cloud provider if Ollama fails (unreachable,
 * no model, etc). This saves cloud tokens when local LLM is available.
 */
class LlmClient(private val settings: UserSettings) {

    companion object {
        private const val TAG = "LlmClient"

        /** Current working Groq models (August 2026). */
        const val GROQ_DEFAULT_MODEL = "openai/gpt-oss-120b"
        val GROQ_FALLBACK_MODELS = listOf("openai/gpt-oss-20b", "qwen/qwen3.6-27b")
        val VALID_GROQ_MODELS = setOf(
            GROQ_DEFAULT_MODEL, "openai/gpt-oss-20b", "openai/gpt-oss-120b",
            "openai/gpt-oss-safeguard-20b", "qwen/qwen3.6-27b"
        )

        fun defaultModelFor(provider: LlmProvider, settings: UserSettings): String = when (provider) {
            LlmProvider.GROQ -> GROQ_DEFAULT_MODEL
            LlmProvider.HUGGINGFACE -> "mistralai/Mistral-7B-Instruct-v0.3"
            LlmProvider.OPENROUTER -> "mistralai/mistral-7b-instruct:free"
            LlmProvider.OLLAMA_LOCAL -> settings.ollamaModel.ifBlank { "llama3" }
            LlmProvider.OPENAI_COMPAT_LOCAL -> settings.localOpenAiModel.ifBlank { "local-model" }
            LlmProvider.CUSTOM -> settings.customProviderModel.ifBlank { "" }
            LlmProvider.HERMES_AGENT -> "hermes-agent"
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Main entry point. Routes to the right provider with fallback.
     * When localFirst is enabled, tries Ollama first before cloud.
     */
    suspend fun complete(
        prompt: String,
        systemPrompt: String = "You are an expert Android developer.",
        provider: LlmProvider = settings.preferredProvider,
        modelId: String = defaultModelFor(provider, settings),
        maxOutputTokens: Int = 2048,
        useLocalFirst: Boolean = settings.localFirst
    ): String = withContext(Dispatchers.IO) {
        // ─ Local-first routing ──────────────────────
        if (useLocalFirst) {
            try {
                Log.i(TAG, "Local-first: trying Ollama (${settings.ollamaModel.ifBlank { "llama3" }}) before $provider")
                return@withContext ollamaComplete(prompt, systemPrompt, settings.ollamaModel.ifBlank { "llama3" })
            } catch (e: Exception) {
                Log.w(TAG, "Local-first Ollama failed (${e.message}), falling back to $provider")
            }
        }

        when (provider) {
            LlmProvider.GROQ -> groqCompleteWithFallback(prompt, systemPrompt, resolveGroqModel(modelId), maxOutputTokens)
            LlmProvider.HUGGINGFACE -> hfComplete(prompt, modelId, maxOutputTokens)
            LlmProvider.OPENROUTER -> openRouterComplete(prompt, systemPrompt, modelId, maxOutputTokens)
            LlmProvider.OLLAMA_LOCAL -> ollamaComplete(prompt, systemPrompt, modelId)
            LlmProvider.OPENAI_COMPAT_LOCAL -> localOpenAiComplete(prompt, systemPrompt, modelId, maxOutputTokens)
            LlmProvider.CUSTOM -> customComplete(prompt, systemPrompt, modelId, maxOutputTokens)
            LlmProvider.HERMES_AGENT -> hermesAgentComplete(prompt, systemPrompt, modelId, maxOutputTokens)
        }
    }

    // ─────────────────────────────────────────────────
    // Provider implementations
    // ─────────────────────────────────────────────────

    private fun resolveGroqModel(configured: String): String = when {
        configured.isBlank() -> GROQ_DEFAULT_MODEL
        configured == "openai/gpt-oss-20b" -> GROQ_DEFAULT_MODEL // old broken default
        configured in VALID_GROQ_MODELS -> configured
        else -> configured
    }

    private suspend fun groqCompleteWithFallback(
        prompt: String, system: String, model: String, maxOutputTokens: Int
    ): String {
        val modelsToTry = buildList {
            add(model)
            addAll(GROQ_FALLBACK_MODELS.filter { it != model })
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
                if (msg.contains("model_not_found", true) || msg.contains("does not exist", true) || msg.contains("404", true)) {
                    lastError = msg; continue
                }
                throw e
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

    /**
     * Custom provider: ANY OpenAI-compatible URL + ANY model + ANY key.
     */
    private suspend fun customComplete(prompt: String, system: String, model: String, maxOutputTokens: Int): String {
        val baseUrl = settings.resolveBaseUrl(LlmProvider.CUSTOM)
        val apiKey = settings.resolveApiKey(LlmProvider.CUSTOM)
        val resolvedModel = if (model.isNotBlank()) model else settings.customProviderModel

        if (baseUrl.isBlank()) {
            throw RuntimeException("Custom provider URL is blank. Go to Settings → Custom Provider URL.")
        }
        if (resolvedModel.isBlank()) {
            throw RuntimeException("Custom provider model is blank. Go to Settings → Custom Model Name.")
        }

        val body = chatBody(prompt, system, resolvedModel, maxOutputTokens).toRequestBody(jsonMedia)
        val reqBuilder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body)
        if (apiKey.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $apiKey")

        return executeAndExtractContentWithRetry(reqBuilder.build(), "Custom", resolvedModel)
    }

    /**
     * Hermes-agent: OpenAI-compatible API from NousResearch
     * Default URL: http://localhost:8642/v1
     * Default model: "hermes-agent"
     * Default API key: "change-me-local-dev" (built-in, no user config needed)
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

    // ── Ollama ───────────────────────────────────────

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
            put("options", JSONObject().apply {
                put("num_predict", 2048); put("temperature", 0.2)
            })
        }.toString().toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("${LlmProvider.OLLAMA_LOCAL.baseUrl}/chat")
            .post(body).build()
        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string()?.trim().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Ollama /api/chat HTTP ${resp.code}: $raw".take(300))
            if (raw.isBlank()) throw RuntimeException("Ollama returned empty response")
            try {
                val json = JSONObject(raw)
                val content = json.optJSONObject("message")?.optString("content")?.trim().orEmpty()
                if (content.isNotBlank()) return@use content
                val alt = json.optString("response").ifBlank {
                    throw RuntimeException("Ollama /api/chat returned no content. Raw: ${raw.take(300)}")
                }
                alt
            } catch (e: RuntimeException) { throw e }
            catch (e: Exception) { throw RuntimeException("Ollama invalid JSON: ${raw.take(300)}", e) }
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
            if (!resp.isSuccessful) throw RuntimeException("Ollama /api/generate HTTP ${resp.code}: $raw".take(300))
            if (raw.isBlank()) throw RuntimeException("Ollama returned empty response")
            JSONObject(raw).optString("response").ifBlank {
                throw RuntimeException("Ollama /api/generate returned no content. Raw: ${raw.take(300)}")
            }
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
            put("max_tokens", maxOutputTokens.coerceIn(512, 3000))
            put("temperature", 0.2)
        }.toString()

    private suspend fun executeAndExtractContentWithRetry(
        req: Request, providerName: String = "Provider", model: String = ""
    ): String {
        var lastError = "Unknown LLM error"
        repeat(8) { attempt ->
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()?.trim().orEmpty()
                if (resp.isSuccessful) {
                    if (raw.isBlank()) {
                        lastError = "HTTP ${resp.code}: $providerName (model=$model) returned empty response"
                        if (attempt < 7) { delay((1500L * (attempt + 1)).coerceAtMost(8000L)); return@use }
                        throw RuntimeException(lastError)
                    }
                    return extractChatContent(raw, providerName, model)
                }
                lastError = "HTTP ${resp.code}: $providerName (model=$model) ${raw.ifBlank { "empty" }.take(250)}"
                if (resp.code == 429 && attempt < 7) {
                    val retryAfter = resp.header("retry-after")?.toDoubleOrNull()
                    val waitSeconds = (retryAfter ?: 10.0 + attempt * 5.0).coerceIn(2.0, 65.0)
                    delay((waitSeconds * 1000.0).toLong()); return@use
                }
                if (resp.code in 400..499) throw RuntimeException(lastError)
                if (attempt < 7) { delay((1500L * (attempt + 1)).coerceAtMost(8000L)); return@use }
                throw RuntimeException(lastError)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw RuntimeException(lastError)
    }

    private fun extractChatContent(raw: String, providerName: String, model: String): String {
        return try {
            val json = JSONObject(raw)
            val errorObj = json.optJSONObject("error")
            if (errorObj != null) {
                val errorMsg = errorObj.optString("message", errorObj.toString().take(200))
                throw RuntimeException("$providerName API error (model=$model): $errorMsg")
            }
            val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim().orEmpty()
            if (content.isNotBlank()) return content
            val keys = buildList { val k = json.keys(); while (k.hasNext()) add(k.next()) }
            val preview = raw.take(400).replace("\n", " ")
            throw RuntimeException("$providerName returned JSON without expected content field.\nModel: $model\nKeys: ${keys.joinToString(", ")}\nPreview: $preview")
        } catch (e: RuntimeException) { throw e }
        catch (e: Exception) { throw RuntimeException("$providerName returned non-JSON: ${raw.take(500)}") }
    }
}
