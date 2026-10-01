package com.swarmbuilder.app.swarm

import android.content.Context
import android.util.Base64
import android.util.Log
import com.swarmbuilder.app.models.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real image generation via Hugging Face Inference API.
 * Primary model: black-forest-labs/FLUX.1-schnell (fast).
 * Fallback: stabilityai/stable-diffusion-xl-base-1.0
 *
 * Requires a HF token with Inference API access in settings.
 */
class ImageClient(private val settings: UserSettings) {

    companion object {
        private const val TAG = "ImageClient"
        private const val HF_INFERENCE = "https://api-inference.huggingface.co/models"
        val PRIMARY_MODEL = "black-forest-labs/FLUX.1-schnell"
        val FALLBACK_MODEL = "stabilityai/stable-diffusion-xl-base-1.0"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Generate an image from [prompt] and write PNG bytes to [outFile].
     * Returns the output file on success.
     */
    suspend fun generate(
        context: Context,
        prompt: String,
        outFile: File? = null,
        model: String = PRIMARY_MODEL,
    ): File = withContext(Dispatchers.IO) {
        val token = settings.huggingFaceToken.trim()
        require(token.isNotBlank()) {
            "Hugging Face token required for image generation. Set it in Settings."
        }

        val target = outFile ?: File(
            File(context.filesDir, "images").also { it.mkdirs() },
            "gen_${System.currentTimeMillis()}.png"
        )

        var lastError: String? = null
        for (m in listOf(model, FALLBACK_MODEL).distinct()) {
            try {
                Log.i(TAG, "Generating with $m")
                val bytes = requestImage(token, m, prompt)
                require(bytes.isNotEmpty()) { "Empty image response from $m" }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                Log.i(TAG, "Image saved: ${target.absolutePath} (${bytes.size} bytes)")
                return@withContext target
            } catch (e: Exception) {
                lastError = "$m: ${e.message}"
                Log.w(TAG, lastError)
            }
        }
        throw RuntimeException("Image generation failed. $lastError")
    }

    private fun requestImage(token: String, model: String, prompt: String): ByteArray {
        val body = JSONObject().apply {
            put("inputs", prompt)
            put("parameters", JSONObject().apply {
                put("num_inference_steps", 4)
                put("guidance_scale", 3.5)
            })
        }.toString().toRequestBody(jsonMedia)

        val req = Request.Builder()
            .url("$HF_INFERENCE/$model")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "image/png")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                val errText = bytes.toString(Charsets.UTF_8).take(400)
                // Some HF endpoints return JSON error even for image models
                throw RuntimeException("HF HTTP ${resp.code}: $errText")
            }
            // Detect JSON error masquerading as 200
            if (bytes.size > 2 && bytes[0] == '{'.code.toByte()) {
                val json = String(bytes, Charsets.UTF_8)
                val err = try {
                    JSONObject(json).optString("error").ifBlank {
                        JSONObject(json).optJSONObject("error")?.toString() ?: json.take(200)
                    }
                } catch (_: Exception) {
                    json.take(200)
                }
                throw RuntimeException("HF error: $err")
            }
            // Optional base64 JSON envelope used by some routers
            if (bytes.size > 20 && String(bytes, 0, minOf(20, bytes.size), Charsets.UTF_8)
                    .contains("\"image\"")
            ) {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val b64 = json.optString("image")
                    .ifBlank { json.optJSONArray("images")?.optString(0).orEmpty() }
                if (b64.isNotBlank()) {
                    return Base64.decode(b64.substringAfter(','), Base64.DEFAULT)
                }
            }
            return bytes
        }
    }
}
