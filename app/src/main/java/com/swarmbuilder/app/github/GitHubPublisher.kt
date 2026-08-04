package com.swarmbuilder.app.github

import com.swarmbuilder.app.models.AppSpec
import com.swarmbuilder.app.models.BuildResult
import com.swarmbuilder.app.models.LogLevel
import com.swarmbuilder.app.models.SwarmLog
import com.swarmbuilder.app.models.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Publishes a generated project to GitHub using the REST API.
 *
 * Steps:
 *  1. Create a new public repository (or use existing one).
 *  2. Upload each source file via the Contents API.
 *  3. Upload the built APK as a GitHub Release asset.
 */
class GitHubPublisher(private val settings: UserSettings) {

    val logs = MutableSharedFlow<SwarmLog>(replay = 64)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private suspend fun emit(msg: String, level: LogLevel = LogLevel.INFO) {
        logs.emit(SwarmLog("Publisher", msg, level))
    }

    /**
     * Push [projectDir] to GitHub and upload the APK from [buildResult].
     * Returns the HTML URL of the created repository.
     */
    suspend fun publish(
        spec: AppSpec,
        projectDir: File,
        buildResult: BuildResult
    ): String = withContext(Dispatchers.IO) {
        val repoName = spec.appName.replace(Regex("[^A-Za-z0-9_-]"), "-").lowercase()

        emit("Creating GitHub repository '$repoName'…")
        val repoUrl = createRepo(repoName, spec.description)
        emit("Repository created: $repoUrl", LogLevel.SUCCESS)

        emit("Uploading source files…")
        uploadDirectory(projectDir, projectDir, repoName)
        emit("Source files uploaded", LogLevel.SUCCESS)

        if (buildResult.success && buildResult.apkPath != null) {
            emit("Creating release and uploading APK…")
            val apkFile = File(buildResult.apkPath)
            if (apkFile.exists()) {
                uploadApkRelease(repoName, apkFile, spec.appName)
                emit("APK uploaded as release asset", LogLevel.SUCCESS)
            }
        }

        repoUrl
    }

    // ── GitHub API helpers ────────────────────────────────────────────────────

    private fun authHeader() = buildString {
        append("token ")
        append(settings.githubToken)
    }

    private fun createRepo(name: String, description: String): String {
        val body = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("private", false)
            put("auto_init", false)
        }.toString().toRequestBody(JSON)

        val req = Request.Builder()
            .url("https://api.github.com/user/repos")
            .addHeader("Authorization", authHeader())
            .addHeader("Accept", "application/vnd.github+json")
            .post(body)
            .build()

        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            // 422 = repo already exists – fetch its URL instead
            if (resp.code == 422) {
                "https://github.com/${settings.githubUsername}/$name"
            } else {
                if (!resp.isSuccessful) throw RuntimeException("Create repo failed ${resp.code}: $raw")
                JSONObject(raw).getString("html_url")
            }
        }
    }

    private fun uploadDirectory(root: File, dir: File, repoName: String) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                uploadDirectory(root, file, repoName)
            } else {
                val relativePath = file.relativeTo(root).path.replace(File.separatorChar, '/')
                uploadFile(repoName, relativePath, file)
            }
        }
    }

    private fun uploadFile(repoName: String, path: String, file: File) {
        val encoded = Base64.getEncoder().encodeToString(file.readBytes())
        val body = JSONObject().apply {
            put("message", "Add $path")
            put("content", encoded)
        }.toString().toRequestBody(JSON)

        val req = Request.Builder()
            .url("https://api.github.com/repos/${settings.githubUsername}/$repoName/contents/$path")
            .addHeader("Authorization", authHeader())
            .addHeader("Accept", "application/vnd.github+json")
            .put(body)
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 422) {
                val raw = resp.body?.string() ?: ""
                throw RuntimeException("Upload $path failed ${resp.code}: $raw")
            }
        }
    }

    private fun uploadApkRelease(repoName: String, apk: File, appName: String) {
        // Create release
        val releaseBody = JSONObject().apply {
            put("tag_name", "v1.0.0")
            put("name", "$appName v1.0.0")
            put("body", "Auto-generated by SwarmBuilder")
        }.toString().toRequestBody(JSON)

        val releaseReq = Request.Builder()
            .url("https://api.github.com/repos/${settings.githubUsername}/$repoName/releases")
            .addHeader("Authorization", authHeader())
            .addHeader("Accept", "application/vnd.github+json")
            .post(releaseBody)
            .build()

        val uploadUrl = http.newCall(releaseReq).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("Create release failed ${resp.code}: $raw")
            // upload_url looks like: https://uploads.github.com/...{?name,label}
            JSONObject(raw).getString("upload_url").removeSuffix("{?name,label}")
        }

        val apkBody = apk.readBytes().toRequestBody("application/vnd.android.package-archive".toMediaType())
        val uploadReq = Request.Builder()
            .url("$uploadUrl?name=${apk.name}")
            .addHeader("Authorization", authHeader())
            .addHeader("Accept", "application/vnd.github+json")
            .post(apkBody)
            .build()

        http.newCall(uploadReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val raw = resp.body?.string() ?: ""
                throw RuntimeException("APK upload failed ${resp.code}: $raw")
            }
        }
    }
}
