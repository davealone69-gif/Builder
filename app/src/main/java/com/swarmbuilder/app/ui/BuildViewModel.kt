package com.swarmbuilder.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swarmbuilder.app.SwarmBuilderApp
import com.swarmbuilder.app.build.ApkBuilder
import com.swarmbuilder.app.codegen.ProjectWriter
import com.swarmbuilder.app.github.GitHubPublisher
import com.swarmbuilder.app.models.*
import com.swarmbuilder.app.swarm.SwarmOrchestrator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

/**
 * BuildViewModel v3
 *
 * New features:
 * - pushToGitHub() method for the "Push to Git" button
 * - sourceFiles LiveData for the "View Source" button
 * - Better error messages from LlmClient v3
 */
class BuildViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SwarmBuilderApp>()

    private val _logs = MutableSharedFlow<SwarmLog>(replay = 256)
    val logs: SharedFlow<SwarmLog> = _logs

    private val _phase = MutableLiveData("Waiting…")
    val phase: LiveData<String> = _phase

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    private val _apkPath = MutableLiveData<String?>(null)
    val apkPath: LiveData<String?> = _apkPath

    private val _githubUrl = MutableLiveData<String?>(null)
    val githubUrl: LiveData<String?> = _githubUrl

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    // NEW: Source files for viewing
    private val _sourceFiles = MutableLiveData<List<SourceFile>?>(null)
    val sourceFiles: LiveData<List<SourceFile>?> = _sourceFiles

    // Hold state for post-build actions
    private var lastSpec: AppSpec? = null
    private var lastProjectDir: File? = null
    private var lastBuildResult: BuildResult? = null

    fun start(prompt: String) {
        if (_isRunning.value == true) return
        _isRunning.value = true
        _errorMessage.value = null
        _apkPath.value = null
        _githubUrl.value = null
        _sourceFiles.value = null

        viewModelScope.launch {
            val settings = app.userSettings
            val orchestrator = SwarmOrchestrator(settings)
            val apkBuilder = ApkBuilder(app)
            val writer = ProjectWriter(app)

            orchestrator.logs.onEach { _logs.emit(it) }.launchIn(this)
            apkBuilder.logs.onEach { _logs.emit(it) }.launchIn(this)

            try {
                emit("Starting swarm for prompt: $prompt")

                // ── Phase 1: Generate code ──
                _phase.postValue(" Swarm generating code…")
                var files = orchestrator.run(prompt)
                _sourceFiles.postValue(files)

                val appName = files.find { it.relativePath.contains("settings.gradle") }
                    ?.content?.lines()?.firstOrNull { it.contains("rootProject.name") }
                    ?.substringAfter("=")?.trim()?.trim('"') ?: "GeneratedApp"

                val spec = AppSpec(
                    prompt = prompt,
                    appName = appName,
                    packageName = "com.generated.app"
                )
                lastSpec = spec

                // ── Phase 2: Build + repair loop ──
                val maxRepairPasses = 8
                val pipelineStartMs = System.currentTimeMillis()
                var repairPass = 0
                var projectDir: File? = null
                var buildResult: BuildResult

                while (true) {
                    if (System.currentTimeMillis() - pipelineStartMs > PIPELINE_MAX_MS) {
                        throw IllegalStateException(
                            "Pipeline exceeded the ${PIPELINE_MAX_MS / 60_000} minute limit."
                        )
                    }

                    _phase.postValue(
                        if (repairPass == 0) "🔨 Building APK…"
                        else "🔧 Compiler repair $repairPass/$maxRepairPasses…"
                    )

                    projectDir = writer.write(spec, files)
                    buildResult = apkBuilder.build(spec, projectDir)

                    if (buildResult.success) break
                    if (repairPass >= maxRepairPasses) {
                        throw IllegalStateException(
                            "Build still failing after $maxRepairPasses repairs.\n" +
                            (buildResult.errorMessage ?: "Unknown Gradle error")
                        )
                    }

                    repairPass++
                    emit("Gradle failed. Sending diagnostics to Repair pass $repairPass…", LogLevel.WARNING)
                    files = orchestrator.repair(
                        spec = spec,
                        files = files,
                        buildError = buildResult.errorMessage ?: "Unknown Gradle failure",
                        attempt = repairPass
                    )
                    _sourceFiles.postValue(files)
                }

                lastProjectDir = projectDir
                lastBuildResult = buildResult
                buildResult.apkPath?.let { _apkPath.postValue(it) }

                // ─ Phase 3: Optional GitHub push ──
                if (settings.githubToken.isNotBlank() && settings.githubUsername.isNotBlank()) {
                    _phase.postValue(" Pushing project to GitHub…")
                    val publisher = GitHubPublisher(settings)
                    publisher.logs.onEach { _logs.emit(it) }.launchIn(this)
                    try {
                        val url = publisher.publish(spec, projectDir!!, buildResult)
                        _githubUrl.postValue(url)
                    } catch (e: Exception) {
                        emit("GitHub push failed: ${e.message}. APK is still available.", LogLevel.WARNING)
                    }
                } else {
                    emit("GitHub credentials not set — APK built locally. Add GitHub info in Settings to enable push.", LogLevel.WARNING)
                }

                _phase.postValue("✅ APK built successfully")
                emit("Build complete! APK ready for install.", LogLevel.SUCCESS)

            } catch (e: Exception) {
                val msg = "Pipeline error: ${e.message}"
                emit(msg, LogLevel.ERROR)
                _errorMessage.postValue(msg)
                _phase.postValue("❌ Failed")
            } finally {
                _isRunning.postValue(false)
            }
        }
    }

    /**
     * NEW: Manually trigger GitHub push (for the "Push to Git" button).
     * Call this after a successful build if the user didn't have GitHub
     * credentials set initially but added them later.
     */
    fun pushToGitHub() {
        val spec = lastSpec ?: return
        val projectDir = lastProjectDir ?: return
        val buildResult = lastBuildResult ?: return
        val settings = app.userSettings

        if (settings.githubToken.isBlank() || settings.githubUsername.isBlank()) {
            _errorMessage.postValue("GitHub credentials not set. Open Settings to add them.")
            return
        }

        viewModelScope.launch {
            try {
                _phase.postValue("🚀 Pushing to GitHub…")
                emit("Pushing project to GitHub…")
                val publisher = GitHubPublisher(settings)
                publisher.logs.onEach { _logs.emit(it) }.launchIn(this)
                val url = publisher.publish(spec, projectDir, buildResult)
                _githubUrl.postValue(url)
                emit("Pushed to GitHub: $url", LogLevel.SUCCESS)
                _phase.postValue("✅ Pushed to GitHub")
            } catch (e: Exception) {
                emit("GitHub push failed: ${e.message}", LogLevel.ERROR)
                _errorMessage.postValue("Push failed: ${e.message}")
            }
        }
    }

    private suspend fun emit(msg: String, level: LogLevel = LogLevel.INFO) {
        _logs.emit(SwarmLog("System", msg, level))
    }

    private companion object {
        const val PIPELINE_MAX_MS = 90L * 60L * 1000L // 90 minutes
        private const val TAG = "BuildViewModel"
    }
}

class BuildViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return BuildViewModel(application) as T
    }
}
