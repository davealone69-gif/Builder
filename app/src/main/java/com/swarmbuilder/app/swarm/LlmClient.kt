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

/** Low-level LLM client with retries, diagnostics, and local-provider self-healing. */
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
        }
    }

    private suspend fun groqComplete(prompt: String, system: String, model: String, maxOutputTokens: Int): String {
        val body = chatBody(prompt, system, model, maxOutputTokens).toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("${LlmProvider.GROQ.baseUrl}/chat/completions")
            .addHeader("Authorization", bearerToken(settings.groqApiKey))
            .post(body).build()
        return executeAndExtractContentWithRetry(req)
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
            JSONArray(raw).getJSONObject(0).getString("generated_text")
        }
    }

    private suspend fun openRouterComplete(prompt: String, system: String, model: String, maxOutputTokens: Int): String {
        val body = chatBody(prompt, system, model, maxOutputTokens).toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("${LlmProvider.OPENROUTER.baseUrl}/chat/completions")
            .addHeader("Authorization", bearerToken(settings.openRouterApiKey))
            .addHeader("HTTP-Referer", "https://github.com/davealone69-gif/Builder")
            .post(body).build()
        return executeAndExtractContentWithRetry(req)
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
        return executeAndExtractContentWithRetry(req)
    }

    /** Discover a real local model when the saved model is blank or stale. */
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

    private fun ollamaComplete(prompt: String, system: String, model: String): String {
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
            if (!resp.isSuccessful) throw RuntimeException("Ollama error ${resp.code}: ${raw.ifBlank { "empty response" }}")
            if (raw.isBlank()) throw RuntimeException("Ollama returned an empty response")
            JSONObject(raw).optString("response").ifBlank { throw RuntimeException("Ollama returned no response content") }
        }
    }

    private suspend fun executeAndExtractContentWithRetry(req: Request): String {
        var lastError = "Unknown LLM error"
        repeat(8) { attempt ->
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()?.trim().orEmpty()
                if (resp.isSuccessful) {
                    if (raw.isBlank()) {
                        lastError = "HTTP ${resp.code}: provider returned an empty response"
                        if (attempt < 7) {
                            delay((1500L * (attempt + 1)).coerceAtMost(8000L))
                            return@use
                        }
                        throw RuntimeException(lastError)
                    }
                    return extractChatContent(raw)
                }
                lastError = "HTTP ${resp.code}: ${raw.ifBlank { "provider returned an empty response" }}"
                if (resp.code == 429 && attempt < 7) {
                    val retryAfter = resp.header("retry-after")?.toDoubleOrNull()
                    val waitSeconds = (retryAfter ?: 10.0 + attempt * 5.0).coerceIn(2.0, 65.0)
                    delay((waitSeconds * 1000.0).toLong())
                    return@use
                }
                throw RuntimeException(lastError)
            }
        }
        throw RuntimeException(lastError)
    }

    /** Accept standard OpenAI JSON and produce a useful diagnostic for malformed responses. */
    private fun extractChatContent(raw: String): String {
        return try {
            val content = JSONObject(raw)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()
            if (content.isNotBlank()) content
            else throw RuntimeException("Provider returned valid JSON but no choices[0].message.content")
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            val preview = raw.take(500).replace("\n", " ")
            throw RuntimeException("Provider returned non-JSON response: $preview")
        }
    }

    private fun bearerToken(key: String): String = "Bearer $key"

    companion object {
        fun defaultModelFor(provider: LlmProvider, settings: UserSettings): String = when (provider) {
            LlmProvider.GROQ -> "openai/gpt-oss-20b"
            LlmProvider.HUGGINGFACE -> "mistralai/Mistral-7B-Instruct-v0.2"
            LlmProvider.OPENROUTER -> "mistralai/mistral-7b-instruct:free"
            LlmProvider.OLLAMA_LOCAL -> settings.ollamaModel.ifBlank { "llama3" }
            LlmProvider.OPENAI_COMPAT_LOCAL -> settings.localOpenAiModel.ifBlank { "local-model" }
        }
    }
}
