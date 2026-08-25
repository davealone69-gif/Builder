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
import java.util.concurrent.TimeUnit

/** On-device Gradle builder with compiler diagnostics retained for AI repair. */
class ApkBuilder(context: Context) {
    val logs: SharedFlow<SwarmLog> get() = _logs
    private val _logs = MutableSharedFlow<SwarmLog>(replay = 64)
    private val appContext = context.applicationContext
    private val lastApkCache = LastApkCache(appContext)

    suspend fun build(spec: AppSpec, projectDir: File): BuildResult = withContext(Dispatchers.IO) {
        val gradlew = File(projectDir, "gradlew")
        if (!gradlew.exists()) {
            emit("Gradle wrapper not found in generated project", LogLevel.ERROR)
            return@withContext BuildResult(false, spec.appName, errorMessage = "Generated project is missing Gradle wrapper")
        }
        gradlew.setExecutable(true)
        lastApkCache.restoreFrameworks(projectDir) { message -> emitSync(message) }

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
            return@withContext BuildResult(false, spec.appName, errorMessage = "Gradle build failed to start: ${e.message}")
        }

        // Read output on a background thread so the main watchdog can enforce
        // an overall build timeout even if Gradle stops emitting output.
        val diagnostics = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(diagnostics) {
                        if (diagnostics.length > 14000) diagnostics.delete(0, diagnostics.length - 14000)
                        diagnostics.append(line).append('\n')
                    }
                    val level = when {
                        line.contains("BUILD SUCCESSFUL", true) -> LogLevel.SUCCESS
                        line.contains("BUILD FAILED", true) || line.startsWith("e:") -> LogLevel.ERROR
                        line.startsWith("w:") -> LogLevel.WARNING
                        else -> LogLevel.INFO
                    }
                    emitSync(line, level)
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!finished) {
            emit("Gradle build exceeded ${BUILD_TIMEOUT_MINUTES} min – killing it", LogLevel.ERROR)
            process.destroyForcibly()
            reader.join(5_000)
            return@withContext BuildResult(
                false,
                spec.appName,
                errorMessage = "Gradle build timed out after ${BUILD_TIMEOUT_MINUTES} minutes\n$diagnostics"
            )
        }
        reader.join()
        val exitCode = process.exitValue()

        if (exitCode != 0) {
            return@withContext BuildResult(
                false,
                spec.appName,
                errorMessage = "Gradle build failed with exit code $exitCode\n${diagnostics.takeLast(12000)}"
            )
        }

        val builtApk = findBuiltApk(projectDir) ?: return@withContext BuildResult(
            false, spec.appName, errorMessage = "Build succeeded but APK was not found"
        )
        builtApk.copyTo(targetApk, overwrite = true)
        emit("APK ready: ${targetApk.absolutePath}", LogLevel.SUCCESS)
        lastApkCache.saveFrameworks(projectDir) { message -> emitSync(message) }
        BuildResult(true, spec.appName, apkPath = targetApk.absolutePath)
    }

    private fun findBuiltApk(projectDir: File): File? =
        File(projectDir, "app/build/outputs/apk/debug")
            .listFiles { _, name -> name.endsWith(".apk") }
            ?.firstOrNull()

    private fun emitSync(message: String, level: LogLevel = LogLevel.INFO) {
        _logs.tryEmit(SwarmLog("Builder", message, level))
    }

    private suspend fun emit(message: String, level: LogLevel = LogLevel.INFO) {
        _logs.emit(SwarmLog("Builder", message, level))
    }

    private companion object {
        // On-device builds can download the Gradle distribution + AGP on first
        // run, so this is generous — but never unbounded.
        const val BUILD_TIMEOUT_MINUTES = 30L
    }
}
