package com.swarmbuilder.app.swarm

import com.swarmbuilder.app.models.LlmProvider
import com.swarmbuilder.app.models.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Low-level client for the configured LLM provider. */
class LlmClient(private val settings: UserSettings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

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

    private fun groqComplete(prompt: String, system: String, model: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", system) })
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            })
            put("max_tokens", 4096)
            put("temperature", 0.2)
        }.toString().toRequestBody(jsonMedia)

        val req = Request.Builder()
            .url("${LlmProvider.GROQ.baseUrl}/chat/completions")
            .addHeader("Authorization", bearerToken(settings.groqApiKey))
            .post(body)
            .build()

        return executeAndExtractContent(req)
    }

    private fun hfComplete(prompt: String, model: String): String {
        val body = JSONObject().apply {
            put("inputs", prompt)
            put("parameters", JSONObject().apply {
                put("max_new_tokens", 2048)
                put("temperature", 0.2)
                put("return_full_text", false)
            })
        }.toString().toRequestBody(jsonMedia)

        val req = Request.Builder()
            .url("${LlmProvider.HUGGINGFACE.baseUrl}/$model")
            .addHeader("Authorization", bearerToken(settings.huggingFaceToken))
            .post(body)
            .build()

        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: throw RuntimeException("Empty HF response")
            if (!resp.isSuccessful) throw RuntimeException("HF error ${resp.code}: $raw")
            val arr = JSONArray(raw)
            arr.getJSONObject(0).getString("generated_text")
        }
    }

    private fun openRouterComplete(prompt: String, system: String, model: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", system) })
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            })
        }.toString().toRequestBody(jsonMedia)

        val req = Request.Builder()
            .url("${LlmProvider.OPENROUTER.baseUrl}/chat/completions")
            .addHeader("Authorization", bearerToken(settings.openRouterApiKey))
            .addHeader("HTTP-Referer", "https://github.com/davealone69-gif/Builder")
            .post(body)
            .build()

        return executeAndExtractContent(req)
    }

    private fun ollamaComplete(prompt: String, system: String, model: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("prompt", "$system\n\n$prompt")
            put("stream", false)
        }.toString().toRequestBody(jsonMedia)

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

    private fun executeAndExtractContent(req: Request): String {
        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: throw RuntimeException("Empty response")
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $raw")
            JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    private fun bearerToken(key: String): String = "Bearer $key"

    companion object {
        fun defaultModelFor(provider: LlmProvider): String = when (provider) {
            // Groq shut down llama-3.3-70b-versatile on 2026-08-16.
            // GPT-OSS 120B is the current recommended replacement.
            LlmProvider.GROQ -> "openai/gpt-oss-120b"
            LlmProvider.HUGGINGFACE -> "mistralai/Mistral-7B-Instruct-v0.2"
            LlmProvider.OPENROUTER -> "mistralai/mistral-7b-instruct:free"
            LlmProvider.OLLAMA_LOCAL -> "llama3"
        }
    }
}
