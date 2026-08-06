package com.swarmbuilder.app.build

import android.content.Context
import com.swarmbuilder.app.models.AppSpec
import com.swarmbuilder.app.models.BuildResult
import com.swarmbuilder.app.models.LogLevel
import com.swarmbuilder.app.models.SwarmLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Builds a generated Android project by running its Gradle wrapper.
 *
 * The built APK is copied to the app's private files directory so it can be
 * shared via [FileProvider]. Build logs are streamed through [logs].
 *
 * Before each build, previously cached framework artifacts (APK and
 * intermediate JARs/AARs) are restored from [LastApkCache] so that Gradle
 * can perform incremental compilation.  After a successful build the new
 * artifacts are saved back to the cache for the *next* run.
 */
class ApkBuilder(context: Context) {

    val logs: SharedFlow<SwarmLog> get() = _logs
    private val _logs = MutableSharedFlow<SwarmLog>(replay = 64)

    private val appContext = context.applicationContext

    /** Persistent cache for framework artifacts from the last successful APK build. */
    private val lastApkCache = LastApkCache(appContext)

    /**
     * Runs `./gradlew assembleDebug` in [projectDir].
     * Returns a [BuildResult] describing success/failure and the APK path if built.
     */
    suspend fun build(spec: AppSpec, projectDir: File): BuildResult = withContext(Dispatchers.IO) {
        val gradlew = File(projectDir, "gradlew")
        if (!gradlew.exists()) {
            emit("Gradle wrapper not found in generated project", LogLevel.ERROR)
            return@withContext BuildResult(
                success = false,
                appName = spec.appName,
                errorMessage = "Generated project is missing Gradle wrapper"
            )
        }
        gradlew.setExecutable(true)

        // Restore framework artifacts from the last APK build (if available).
        // This enables incremental Gradle compilation without hard-failing when
        // no previous build exists.
        lastApkCache.restoreFrameworks(projectDir) { message -> emit(message) }

        val outputDir = File(appContext.filesDir, "apks").also { it.mkdirs() }
        val apkName = "${spec.appName.replace(Regex("[^A-Za-z0-9_-]"), "-")}-debug.apk"
        val targetApk = File(outputDir, apkName)

        emit("Starting Gradle build for ${spec.appName}…")

        val process = try {
            ProcessBuilder(gradlew.absolutePath, "assembleDebug")
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            emit("Could not start Gradle build: ${e.message}", LogLevel.ERROR)
            return@withContext BuildResult(
                success = false,
                appName = spec.appName,
                errorMessage = "Gradle build failed to start: ${e.message}"
            )
        }

        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val level = when {
                    line.contains("BUILD SUCCESSFUL", ignoreCase = true) -> LogLevel.SUCCESS
                    line.contains("BUILD FAILED", ignoreCase = true) || line.startsWith("e:") -> LogLevel.ERROR
                    line.startsWith("w:") -> LogLevel.WARNING
                    else -> LogLevel.INFO
                }
                emit(line, level)
            }
        }

        val exitCode = process.waitFor()

        if (exitCode != 0) {
            return@withContext BuildResult(
                success = false,
                appName = spec.appName,
                errorMessage = "Gradle build failed with exit code $exitCode"
            )
        }

        val builtApk = findBuiltApk(projectDir)
            ?: return@withContext BuildResult(
                success = false,
                appName = spec.appName,
                errorMessage = "Build succeeded but APK was not found"
            )

        builtApk.copyTo(targetApk, overwrite = true)
        emit("APK ready: ${targetApk.absolutePath}", LogLevel.SUCCESS)

        // Save framework artifacts from this successful build for the next run.
        lastApkCache.saveFrameworks(projectDir) { message -> emit(message) }

        BuildResult(
            success = true,
            appName = spec.appName,
            apkPath = targetApk.absolutePath
        )
    }

    private fun findBuiltApk(projectDir: File): File? {
        val outputs = File(projectDir, "app/build/outputs/apk/debug")
        return outputs.listFiles { _, name -> name.endsWith(".apk") }?.firstOrNull()
    }

    private suspend fun emit(message: String, level: LogLevel = LogLevel.INFO) {
        _logs.emit(SwarmLog("Builder", message, level))
    }
}
