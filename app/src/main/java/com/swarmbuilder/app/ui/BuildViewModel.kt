package com.swarmbuilder.app.ui

import android.app.Application
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
import com.swarmbuilder.app.models.AppSpec
import com.swarmbuilder.app.models.BuildResult
import com.swarmbuilder.app.models.LogLevel
import com.swarmbuilder.app.models.SwarmLog
import com.swarmbuilder.app.swarm.SwarmOrchestrator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

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

    fun start(prompt: String) {
        if (_isRunning.value == true) return
        _isRunning.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            val settings = app.userSettings
            val orchestrator = SwarmOrchestrator(settings)
            val apkBuilder = ApkBuilder(app)
            val writer = ProjectWriter(app)
            orchestrator.logs.onEach { _logs.emit(it) }.launchIn(this)
            apkBuilder.logs.onEach { _logs.emit(it) }.launchIn(this)

            try {
                _phase.postValue("🤖 Swarm generating code…")
                emit("Starting cost-aware swarm for prompt: $prompt")
                var files = orchestrator.run(prompt)

                val appName = files.find { it.relativePath.contains("settings.gradle") }
                    ?.content?.lines()?.firstOrNull { it.contains("rootProject.name") }
                    ?.substringAfter("=")?.trim()?.trim('"') ?: "GeneratedApp"
                val spec = AppSpec(
                    prompt = prompt,
                    appName = appName,
                    packageName = "com.generated.app"
                )

                // Compiler-first loop. AI is called again only after real Gradle failure.
                val maxRepairPasses = 8
                val pipelineStartMs = System.currentTimeMillis()
                var repairPass = 0
                var projectDir: File? = null
                var buildResult: BuildResult

                while (true) {
                    if (System.currentTimeMillis() - pipelineStartMs > PIPELINE_MAX_MS) {
                        throw IllegalStateException(
                            "Pipeline exceeded the ${PIPELINE_MAX_MS / 60_000} minute overall limit. " +
                                "Stopping to protect battery and data."
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
                            "Build still failing after $maxRepairPasses compiler repair passes.\n" +
                                (buildResult.errorMessage ?: "Unknown Gradle error")
                        )
                    }

                    repairPass++
                    emit(
                        "Gradle failed. Sending actual compiler diagnostics to Repair pass $repairPass.",
                        LogLevel.WARNING
                    )
                    files = orchestrator.repair(
                        spec = spec,
                        files = files,
                        buildError = buildResult.errorMessage ?: "Unknown Gradle failure",
                        attempt = repairPass
                    )
                }

                buildResult.apkPath?.let { _apkPath.postValue(it) }

                if (settings.githubToken.isNotBlank() && settings.githubUsername.isNotBlank()) {
                    _phase.postValue("🚀 Pushing working project to GitHub…")
                    val publisher = GitHubPublisher(settings)
                    publisher.logs.onEach { _logs.emit(it) }.launchIn(this)
                    val url = publisher.publish(spec, projectDir!!, buildResult)
                    _githubUrl.postValue(url)
                } else {
                    emit("GitHub credentials not set – skipping push", LogLevel.WARNING)
                }

                _phase.postValue("✅ APK built successfully")
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

    private suspend fun emit(msg: String, level: LogLevel = LogLevel.INFO) {
        _logs.emit(SwarmLog("System", msg, level))
    }

    private companion object {
        const val PIPELINE_MAX_MS = 90L * 60L * 1000L // 90 minutes end-to-end
    }
}

class BuildViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return BuildViewModel(application) as T
    }
}
