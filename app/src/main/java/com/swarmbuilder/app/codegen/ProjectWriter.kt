package com.swarmbuilder.app.codegen

import android.content.Context
import com.swarmbuilder.app.models.AppSpec
import com.swarmbuilder.app.models.SourceFile
import java.io.File

/**
 * Writes the LLM-generated [SourceFile] list onto disk inside a temporary
 * project directory and ensures essential scaffold files are present.
 */
class ProjectWriter(private val context: Context) {

    /**
     * Write all [files] into a subdirectory of the app's cache dir named after [spec].
     * Returns the root [File] of the written project.
     */
    fun write(spec: AppSpec, files: List<SourceFile>): File {
        val safeAppName = spec.appName.replace(Regex("[^A-Za-z0-9_]"), "_")
        val projectDir = File(context.cacheDir, "projects/$safeAppName").also { it.mkdirs() }

        // Write LLM-generated files
        files.forEach { sf ->
            val target = File(projectDir, sf.relativePath)
            target.parentFile?.mkdirs()
            target.writeText(sf.content)
        }

        // Ensure mandatory scaffold files exist
        ensureGradleWrapper(projectDir)
        ensureLocalProperties(projectDir)

        return projectDir
    }

    // ── Scaffold helpers ──────────────────────────────────────────────────────

    private fun ensureGradleWrapper(dir: File) {
        val wrapperDir = File(dir, "gradle/wrapper").also { it.mkdirs() }
        val props = File(wrapperDir, "gradle-wrapper.properties")
        if (!props.exists()) {
            props.writeText(
                """
                distributionBase=GRADLE_USER_HOME
                distributionPath=wrapper/dists
                distributionUrl=https\://services.gradle.org/distributions/gradle-8.2-bin.zip
                zipStoreBase=GRADLE_USER_HOME
                zipStorePath=wrapper/dists
                """.trimIndent()
            )
        }
        val gradlew = File(dir, "gradlew")
        if (!gradlew.exists()) {
            gradlew.writeText(GRADLEW_SCRIPT)
            gradlew.setExecutable(true)
        }
    }

    private fun ensureLocalProperties(dir: File) {
        val lp = File(dir, "local.properties")
        if (!lp.exists()) {
            // Point to the device's SDK location
            val sdkPath = "/opt/android-sdk"
            lp.writeText("sdk.dir=$sdkPath\n")
        }
    }

    companion object {
        private val GRADLEW_SCRIPT = """
            #!/usr/bin/env sh
            ##############################################################################
            ## Gradle start up script for UN*X
            ##############################################################################
            APP_NAME="Gradle"
            APP_BASE_NAME=`basename "${'$'}0"`
            APP_HOME=`dirname "${'$'}0"`
            CLASSPATH=${'$'}APP_HOME/gradle/wrapper/gradle-wrapper.jar
            exec java -classpath "${'$'}CLASSPATH" org.gradle.wrapper.GradleWrapperMain "${'$'}@"
        """.trimIndent()
    }
}
