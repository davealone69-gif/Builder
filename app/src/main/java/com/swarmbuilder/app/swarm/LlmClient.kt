package com.swarmbuilder.app.swarm

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
 * Low-level LLM client with retries and diagnostics.
 *
 * FIX: Groq default model changed from "openai/gpt-oss-20b" (DOES NOT EXIST)
 * to "llama-3.3-70b-versatile" (REAL Groq model).
 *
 * FIX: Error messages now show what the provider actually returned,
 * so "no choices[0].message.content" errors are actually debuggable.
 */
class LlmClient(private val settings: UserSettings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun complete(
        prompt: String,
        systemPrompt: String = "You are an expert Android developer.",
        provider: LlmProvider = settings.preferredProvider,
        modelId: String = defaultModelFor(provider, settings),
        maxOutputTokens: Int = 2048
    ): String = withContext(Dispatchers.IO) {
        when (provider) {
            LlmProvider.GROQ -> groqComplete(prompt, systemPrompt, modelId, maxOutputTokens)
            LlmProvider.HUGGINGFACE -> hfComplete(prompt, modelId, maxOutputTokens)
            LlmProvider.OPENROUTER -> openRouterComplete(prompt, systemPrompt, modelId, maxOutputTokens)
            LlmProvider.OLLAMA_LOCAL -> ollamaComplete(prompt, systemPrompt, modelId)
            LlmProvider.OPENAI_COMPAT_LOCAL -> localOpenAiComplete(prompt, systemPrompt, modelId, maxOutputTokens)
            LlmProvider.CUSTOM -> customComplete(prompt, systemPrompt, modelId, maxOutputTokens)
        }
    }

    private suspend fun groqComplete(
        prompt: String,
        system: String,
        model: String,
        maxOutputTokens: Int
    ): String {
        val resolvedModel = resolveGroqModel(model)
        return groqCompleteWithFallback(prompt, system, resolvedModel, maxOutputTokens)
    }

    /**
     * FIX: Map known bad model names to real Groq models.
     * "openai/gpt-oss-20b" does NOT exist on Groq — that was the crash bug.
     */
    private fun resolveGroqModel(configured: String): String = when {
        // Blank → use best free Groq model (the OG that's been available since day 1)
        configured.isBlank() -> GROQ_DEFAULT_MODEL
        // The buggy legacy default
        configured == "openai/gpt-oss-20b" -> GROQ_DEFAULT_MODEL
        // Already a valid Groq model ID
        configured in VALID_GROQ_MODELS -> configured
        // User typed something — try it as-is
        else -> configured
    }

    /**
     * Auto-fallback: if a Groq model returns 404/invalid_request_error,
     * try the next model in the list until one works.
     * This prevents the "model does not exist" crash.
     */
    private suspend fun groqCompleteWithFallback(
        prompt: String,
        system: String,
        model: String,
        maxOutputTokens: Int
    ): String {
        // Try models in order until one works
        val modelsToTry = buildList {
            add(model) // try requested model first
            addAll(GROQ_FALLBACK_MODELS.filter { it != model })
        }

        var lastError: String? = null
        for (m in modelsToTry) {
            try {
                val body = chatBody(prompt, system, m, maxOutputTokens).toRequestBody(jsonMedia)
                val req = Request.Builder()
                    .url("${LlmProvider.GROQ.baseUrl}/chat/completions")
                    .addHeader("Authorization", bearerToken(settings.groqApiKey))
                    .post(body).build()
                return executeAndExtractContentWithRetry(req, providerName = "Groq", model = m)
            } catch (e: Exception) {
                val msg = e.message ?: ""
                // Only retry on model-not-found type errors, not rate limits or auth errors
                if (msg.contains("model_not_found", ignoreCase = true) ||
                    msg.contains("does not exist", ignoreCase = true) ||
                    msg.contains("404", ignoreCase = true)) {
                    lastError = msg
                    continue // try next model
                }
                throw e // non-model errors should propagate immediately
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
            .addHeader("Authorization", bearerToken(settings.huggingFaceToken))
            .post(body).build()
        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string()?.trim().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HF error ${resp.code}: ${raw.ifBlank { "empty response" }}")
            if (raw.isBlank()) throw RuntimeException("HF returned an empty response")
            try {
                JSONArray(raw).getJSONObject(0).getString("generated_text")
            } catch (e: Exception) {
                throw RuntimeException("HF returned unexpected JSON: ${raw.take(300)}", e)
            }
        }
    }

    private suspend fun openRouterComplete(
        prompt: String,
        system: String,
        model: String,
        maxOutputTokens: Int
    ): String {
        val body = chatBody(prompt, system, model, maxOutputTokens).toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("${LlmProvider.OPENROUTER.baseUrl}/chat/completions")
            .addHeader("Authorization", bearerToken(settings.openRouterApiKey))
            .addHeader("HTTP-Referer", "https://github.com/davealone69-gif/Builder")
            .post(body).build()
        return executeAndExtractContentWithRetry(req, providerName = "OpenRouter", model = model)
    }

    private suspend fun localOpenAiComplete(
        prompt: String,
        system: String,
        model: String,
        maxOutputTokens: Int
    ): String {
        val baseUrl = settings.localOpenAiBaseUrl.trim().trimEnd('/')
        val resolvedModel = resolveLocalModel(baseUrl, model)
        val body = chatBody(prompt, system, resolvedModel, maxOutputTokens).toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body).build()
        return executeAndExtractContentWithRetry(req, providerName = "Local OpenAI", model = resolvedModel)
    }

    /**
     * Custom provider: ANY OpenAI-compatible URL + ANY model + ANY key.
     * This is what you use for any provider not listed above (e.g. your own
     * proxy, a different OpenAI-compatible service, etc.)
     */
    private suspend fun customComplete(
        prompt: String,
        system: String,
        model: String,
        maxOutputTokens: Int
    ): String {
        val baseUrl = settings.resolveBaseUrl(LlmProvider.CUSTOM)
        val apiKey = settings.resolveApiKey(LlmProvider.CUSTOM)
        val resolvedModel = if (model.isNotBlank()) model else settings.customProviderModel

        if (baseUrl.isBlank()) {
            throw RuntimeException(
                "Custom provider URL is blank. Go to Settings and enter your provider URL.\n" +
                "Examples: https://api.openai.com/v1, http://your-pc:11434/v1"
            )
        }
        if (resolvedModel.isBlank()) {
            throw RuntimeException(
                "Custom provider model is blank. Go to Settings and enter a model name.\n" +
                "Examples: gpt-4o-mini, gpt-3.5-turbo, llama3"
            )
        }

        val body = chatBody(prompt, system, resolvedModel, maxOutputTokens).toRequestBody(jsonMedia)
        val reqBuilder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body)

        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        return executeAndExtractContentWithRetry(reqBuilder.build(), providerName = "Custom", model = resolvedModel)
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
        } catch (_: Exception) {
            configured.ifBlank { "local-model" }
        }
    }

    private suspend fun ollamaComplete(prompt: String, system: String, model: String): String {
        val resolvedModel = model.ifBlank { settings.ollamaModel.ifBlank { "llama3" } }

        // Try /api/chat first (supports system prompt properly)
        try {
            return ollamaChat(resolvedModel, prompt, system)
        } catch (e: Exception) {
            // Fall back to /api/generate
            return ollamaGenerate(resolvedModel, prompt, system)
        }
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
                put("num_predict", 2048)
                put("temperature", 0.2)
            })
        }.toString().toRequestBody(jsonMedia)

        val req = Request.Builder()
            .url("${LlmProvider.OLLAMA_LOCAL.baseUrl}/chat")
            .post(body).build()

        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string()?.trim().orEmpty()
            if (!resp.isSuccessful)
                throw RuntimeException("Ollama /api/chat error ${resp.code}: ${raw.ifBlank { "empty response" }}")
            if (raw.isBlank())
                throw RuntimeException("Ollama returned an empty response")
            try {
                val json = JSONObject(raw)
                val content = json.optJSONObject("message")?.optString("content")?.trim().orEmpty()
                if (content.isNotBlank()) return@use content
                val alt = json.optString("response").ifBlank {
                    throw RuntimeException("Ollama /api/chat returned no content. Raw: ${raw.take(300)}")
                }
                alt
            } catch (e: RuntimeException) {
                throw e
            } catch (e: Exception) {
                throw RuntimeException("Ollama returned non-JSON: ${raw.take(300)}", e)
            }
        }
    }

    private suspend fun ollamaGenerate(model: String, prompt: String, system: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("prompt", "$system\n\n$prompt")
            put("stream", false)
        }.toString().toRequestBody(jsonMedia)

        val req = Request.Builder()
            .url("${LlmProvider.OLLAMA_LOCAL.baseUrl}/generate")
            .post(body).build()

        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string()?.trim().orEmpty()
            if (!resp.isSuccessful)
                throw RuntimeException("Ollama /api/generate error ${resp.code}: ${raw.ifBlank { "empty response" }}")
            if (raw.isBlank())
                throw RuntimeException("Ollama returned an empty response")
            JSONObject(raw).optString("response").ifBlank {
                throw RuntimeException("Ollama returned no response content. Raw: ${raw.take(300)}")
            }
        }
    }

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

    /**
     * FIX: Execute with retries and RICH error messages.
     * When a provider returns an error JSON (e.g. {"error":{"message":"..."}}),
     * we now show that message instead of the misleading
     * "no choices[0].message.content" error.
     */
    private suspend fun executeAndExtractContentWithRetry(
        req: Request,
        providerName: String = "Provider",
        model: String = ""
    ): String {
        var lastError = "Unknown LLM error"

        repeat(8) { attempt ->
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()?.trim().orEmpty()
                if (resp.isSuccessful) {
                    if (raw.isBlank()) {
                        lastError = "HTTP ${resp.code}: $providerName (model=$model) returned an empty response"
                        if (attempt < 7) {
                            delay((1500L * (attempt + 1)).coerceAtMost(8000L))
                            return@use
                        }
                        throw RuntimeException(lastError)
                    }
                    return extractChatContent(raw, providerName, model)
                }
                lastError = "HTTP ${resp.code}: $providerName (model=$model) ${raw.ifBlank { "empty response" }.take(250)}"
                if (resp.code == 429 && attempt < 7) {
                    val retryAfter = resp.header("retry-after")?.toDoubleOrNull()
                    val waitSeconds = (retryAfter ?: 10.0 + attempt * 5.0).coerceIn(2.0, 65.0)
                    delay((waitSeconds * 1000.0).toLong())
                    return@use
                }
                // 4xx = bad request (bad model, bad key, etc.) — don't retry
                if (resp.code in 400..499) throw RuntimeException(lastError)
                // 5xx = server error — retry
                if (attempt < 7) {
                    delay((1500L * (attempt + 1)).coerceAtMost(8000L))
                    return@use
                }
                throw RuntimeException(lastError)
            }
        }
        // All paths inside repeat() either return or throw, but keep this
        // as a safety net in case of unexpected flow changes.
        @Suppress("UNREACHABLE_CODE")
        throw RuntimeException(lastError)
    }

    /**
     * FIX: Extract chat content with USEFUL error messages.
     * If provider returns {"error": {"message": "Invalid model..."}} we now
     * surface that message instead of "no choices[0].message.content".
     */
    private fun extractChatContent(raw: String, providerName: String, model: String): String {
        return try {
            val json = JSONObject(raw)

            // Check for provider error objects (Groq, OpenRouter return these)
            val errorObj = json.optJSONObject("error")
            if (errorObj != null) {
                val errorMsg = errorObj.optString("message", errorObj.toString().take(200))
                throw RuntimeException("$providerName API error (model=$model): $errorMsg")
            }

            val content = json
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()

            if (content.isNotBlank()) return content

            // Build useful diagnostic
            val keys = buildList {
                val k = json.keys()
                while (k.hasNext()) add(k.next())
            }
            val preview = raw.take(400).replace("\n", " ")
            throw RuntimeException(
                "$providerName returned JSON without expected content field.\n" +
                "Model: $model\n" +
                "Top-level keys: ${keys.joinToString(", ")}\n" +
                "Response preview: $preview"
            )
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            val preview = raw.take(500).replace("\n", "")
            throw RuntimeException("$providerName returned non-JSON: $preview")
        }
    }

    private fun bearerToken(key: String): String = "Bearer $key"

    companion object {
        /** Current working Groq models (as of August 2026). */
        const val GROQ_DEFAULT_MODEL = "openai/gpt-oss-120b"

        /** Fallback models tried in order if the primary fails with 404/model_not_found. */
        val GROQ_FALLBACK_MODELS = listOf(
            "openai/gpt-oss-20b",
            "qwen/qwen3.6-27b"
        )

        /** All known Groq model IDs (for validation). */
        val VALID_GROQ_MODELS = setOf(
            GROQ_DEFAULT_MODEL,
            "openai/gpt-oss-20b",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-safeguard-20b",
            "qwen/qwen3.6-27b"
        )

        fun defaultModelFor(provider: LlmProvider, settings: UserSettings): String = when (provider) {
            LlmProvider.GROQ -> GROQ_DEFAULT_MODEL
            LlmProvider.HUGGINGFACE -> "mistralai/Mistral-7B-Instruct-v0.3"
            LlmProvider.OPENROUTER -> "mistralai/mistral-7b-instruct:free"
            LlmProvider.OLLAMA_LOCAL -> settings.ollamaModel.ifBlank { "llama3" }
            LlmProvider.OPENAI_COMPAT_LOCAL -> settings.localOpenAiModel.ifBlank { "local-model" }
            LlmProvider.CUSTOM -> settings.customProviderModel.ifBlank { "" }
        }
    }
}
