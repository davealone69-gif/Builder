package com.swarmbuilder.app.codegen

import android.content.Context
import com.swarmbuilder.app.build.OnDeviceGradleEnv
import com.swarmbuilder.app.models.AppSpec
import com.swarmbuilder.app.models.SourceFile
import java.io.File

/**
 * Writes LLM-generated sources and ensures a usable Gradle wrapper + local.properties.
 */
class ProjectWriter(private val context: Context) {

    fun write(spec: AppSpec, files: List<SourceFile>): File {
        val safeAppName = spec.appName.replace(Regex("[^A-Za-z0-9_]"), "_")
        val projectDir = File(context.cacheDir, "projects/$safeAppName").also { it.mkdirs() }
        val projectRoot = projectDir.canonicalPath + File.separator

        files.forEach { sf ->
            val target = File(projectDir, sf.relativePath).canonicalFile
            require(target.path.startsWith(projectRoot)) {
                "Refusing to write outside project dir: ${sf.relativePath}"
            }
            target.parentFile?.mkdirs()
            target.writeText(sf.content)
        }

        ensureGradleWrapper(projectDir)
        OnDeviceGradleEnv.applyToProject(context, projectDir)

        return projectDir
    }

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

        val wrapperJar = File(wrapperDir, "gradle-wrapper.jar")
        if (!wrapperJar.exists() || wrapperJar.length() < 10_000L) {
            context.assets.open("gradle-wrapper.jar").use { input ->
                wrapperJar.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val gradlew = File(dir, "gradlew")
        if (!gradlew.exists() || gradlew.length() < 100L) {
            gradlew.writeText(GRADLEW_SCRIPT)
        }
        gradlew.setExecutable(true)
    }

    companion object {
        private val GRADLEW_SCRIPT = """
            #!/usr/bin/env sh
            APP_HOME=${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)
            CLASSPATH="${'$'}APP_HOME/gradle/wrapper/gradle-wrapper.jar"
            if [ -n "${'$'}JAVA_HOME" ] && [ -x "${'$'}JAVA_HOME/bin/java" ]; then
              JAVACMD="${'$'}JAVA_HOME/bin/java"
            else
              JAVACMD=java
            fi
            exec "${'$'}JAVACMD" -classpath "${'$'}CLASSPATH" org.gradle.wrapper.GradleWrapperMain "${'$'}@"
        """.trimIndent()
    }
}
