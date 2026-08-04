package com.swarmbuilder.app.swarm

import android.util.Log
import com.swarmbuilder.app.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Low-level client that sends a chat completion request to a free LLM provider
 * and returns the assistant's text response.
 */
class LlmClient(private val settings: UserSettings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Send [prompt] to the configured provider and return the text reply. */
    suspend fun complete(
        prompt: String,
        systemPrompt: String = "You are an expert Android developer.",
        provider: LlmProvider = settings.preferredProvider,
        modelId: String = defaultModelFor(provider)
    ): String = withContext(Dispatchers.IO) {
        when (provider) {
            LlmProvider.GROQ -> groqComplete(prompt, systemPrompt, modelId)
            LlmProvider.HUGGINGFACE -> hfComplete(prompt, modelId)
            LlmProvider.OPENROUTER -> openRouterComplete(prompt, systemPrompt, modelId)
            LlmProvider.OLLAMA_LOCAL -> ollamaComplete(prompt, systemPrompt, modelId)
        }
    }

    // ── Groq ─────────────────────────────────────────────────────────────────

    private fun groqComplete(prompt: String, system: String, model: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", system) })
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            })
            put("max_tokens", 4096)
            put("temperature", 0.2)
        }.toString().toRequestBody(JSON_MEDIA)

        val req = Request.Builder()
            .url("${LlmProvider.GROQ.baseUrl}/chat/completions")
            .addHeader("Authorization", "******")
            .post(body)
            .build()

        return executeAndExtractContent(req)
    }

    // ── Hugging Face ──────────────────────────────────────────────────────────

    private fun hfComplete(prompt: String, model: String): String {
        val body = JSONObject().apply {
            put("inputs", prompt)
            put("parameters", JSONObject().apply {
                put("max_new_tokens", 2048)
                put("temperature", 0.2)
                put("return_full_text", false)
            })
        }.toString().toRequestBody(JSON_MEDIA)

        val req = Request.Builder()
            .url("${LlmProvider.HUGGINGFACE.baseUrl}/$model")
            .addHeader("Authorization", "******")
            .post(body)
            .build()

        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: throw RuntimeException("Empty HF response")
            if (!resp.isSuccessful) throw RuntimeException("HF error ${resp.code}: $raw")
            val arr = JSONArray(raw)
            arr.getJSONObject(0).getString("generated_text")
        }
    }

    // ── OpenRouter ────────────────────────────────────────────────────────────

    private fun openRouterComplete(prompt: String, system: String, model: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", system) })
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            })
        }.toString().toRequestBody(JSON_MEDIA)

        val req = Request.Builder()
            .url("${LlmProvider.OPENROUTER.baseUrl}/chat/completions")
            .addHeader("Authorization", "******")
            .addHeader("HTTP-Referer", "https://github.com/swarmbuilder")
            .post(body)
            .build()

        return executeAndExtractContent(req)
    }

    // ── Ollama local ──────────────────────────────────────────────────────────

    private fun ollamaComplete(prompt: String, system: String, model: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("prompt", "$system\n\n$prompt")
            put("stream", false)
        }.toString().toRequestBody(JSON_MEDIA)

        val req = Request.Builder()
            .url("${LlmProvider.OLLAMA_LOCAL.baseUrl}/generate")
            .post(body)
            .build()

        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: throw RuntimeException("Empty Ollama response")
            if (!resp.isSuccessful) throw RuntimeException("Ollama error ${resp.code}: $raw")
            JSONObject(raw).getString("response")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun executeAndExtractContent(req: Request): String {
        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: throw RuntimeException("Empty response")
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $raw")
            val json = JSONObject(raw)
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    companion object {
        fun defaultModelFor(provider: LlmProvider): String = when (provider) {
            LlmProvider.GROQ -> "llama3-70b-8192"
            LlmProvider.HUGGINGFACE -> "mistralai/Mistral-7B-Instruct-v0.2"
            LlmProvider.OPENROUTER -> "mistralai/mistral-7b-instruct:free"
            LlmProvider.OLLAMA_LOCAL -> "llama3"
        }
    }
}
