package com.swarmbuilder.app.build

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Detects and configures on-device Android SDK + JDK paths so Gradle can run.
 * Covers:
 * - Termux ($PREFIX/share/android-sdk, $PREFIX/lib/jvm)
 * - ANDROID_HOME / ANDROID_SDK_ROOT env
 * - Common rooted / sideloaded SDK locations
 * - App-private SDK symlink dir if user installed one under filesDir
 */
object OnDeviceGradleEnv {

    private const val TAG = "OnDeviceGradleEnv"

    data class Env(
        val javaHome: String?,
        val androidSdk: String?,
        val pathExtras: List<String>,
        val notes: List<String>,
    ) {
        val isUsable: Boolean get() = !javaHome.isNullOrBlank() && !androidSdk.isNullOrBlank()
    }

    fun detect(context: Context): Env {
        val notes = mutableListOf<String>()

        val javaHome = findJavaHome().also {
            if (it == null) notes += "No JDK found (need java binary + JAVA_HOME)"
            else notes += "JAVA_HOME=$it"
        }

        val sdk = findAndroidSdk(context).also {
            if (it == null) notes += "No Android SDK found"
            else notes += "ANDROID_SDK_ROOT=$it"
        }

        val pathExtras = buildList {
            javaHome?.let {
                add("$it/bin")
            }
            sdk?.let {
                add("$it/platform-tools")
                add("$it/cmdline-tools/latest/bin")
                add("$it/tools/bin")
            }
            termuxPrefix()?.let { p ->
                add("$p/bin")
                add("$p/libexec")
            }
        }.filter { File(it).isDirectory }

        return Env(javaHome, sdk, pathExtras, notes)
    }

    /** Write local.properties + return env map for ProcessBuilder. */
    fun applyToProject(context: Context, projectDir: File): Env {
        val env = detect(context)
        val lp = File(projectDir, "local.properties")
        if (!env.androidSdk.isNullOrBlank()) {
            val escaped = env.androidSdk.replace("\\", "\\\\").replace(":", "\\:")
            lp.writeText("sdk.dir=$escaped\n")
        } else if (!lp.exists()) {
            lp.writeText("# sdk.dir not detected — install SDK or set ANDROID_SDK_ROOT\n")
        }
        return env
    }

    fun processEnvironment(env: Env): Map<String, String> {
        val map = HashMap(System.getenv())
        env.javaHome?.let {
            map["JAVA_HOME"] = it
        }
        env.androidSdk?.let {
            map["ANDROID_SDK_ROOT"] = it
            map["ANDROID_HOME"] = it
        }
        val path = buildString {
            env.pathExtras.forEach { append(it).append(File.pathSeparator) }
            append(map["PATH"] ?: "/system/bin:/system/xbin")
        }
        map["PATH"] = path
        // Gradle on constrained devices
        map.putIfAbsent("GRADLE_OPTS", "-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx512m")
        map.putIfAbsent("TERM", "dumb")
        return map
    }

    // ── detection ─────────────────────────────────────────

    private fun termuxPrefix(): String? {
        val candidates = listOf(
            System.getenv("PREFIX"),
            "/data/data/com.termux/files/usr",
            "/data/data/com.termux.nix/files/usr",
        )
        return candidates.firstOrNull { !it.isNullOrBlank() && File(it, "bin").isDirectory }
    }

    private fun findJavaHome(): String? {
        // Explicit env first
        sequenceOf(System.getenv("JAVA_HOME"), System.getenv("JDK_HOME"))
            .filterNotNull()
            .firstOrNull { File(it, "bin/java").canExecute() }
            ?.let { return it }

        val javaBins = buildList {
            termuxPrefix()?.let {
                add("$it/bin/java")
                // OpenJDK packages in Termux
                add("$it/lib/jvm/java-17-openjdk/bin/java")
                add("$it/lib/jvm/java-21-openjdk/bin/java")
            }
            add("/usr/bin/java")
            add("/system/bin/java")
        }

        for (bin in javaBins) {
            val f = File(bin)
            if (f.canExecute()) {
                // Prefer real JAVA_HOME layout (bin/java under a jdk root)
                val home = f.parentFile?.parentFile
                if (home != null && File(home, "bin/java").exists()) return home.absolutePath
                // Fallback: parent of bin
                return f.parentFile?.parentFile?.absolutePath ?: f.parentFile?.absolutePath
            }
        }
        return null
    }

    private fun findAndroidSdk(context: Context): String? {
        sequenceOf(
            System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("ANDROID_HOME"),
        ).filterNotNull().firstOrNull { isSdkRoot(it) }?.let { return it }

        val candidates = buildList {
            termuxPrefix()?.let {
                add("$it/share/android-sdk")
                add("$it/lib/android-sdk")
                add("$it/opt/android-sdk")
            }
            add(File(context.filesDir, "android-sdk").absolutePath)
            add(File(context.getExternalFilesDir(null), "android-sdk").absolutePath)
            // Common manual installs
            add("/sdcard/Android/sdk")
            add("/storage/emulated/0/Android/sdk")
            if (Build.VERSION.SDK_INT >= 24) {
                // no-op placeholder for future scoped paths
            }
        }

        return candidates.firstOrNull { isSdkRoot(it) }
    }

    private fun isSdkRoot(path: String): Boolean {
        val root = File(path)
        if (!root.isDirectory) return false
        // platforms or build-tools present = usable SDK
        return File(root, "platforms").isDirectory ||
            File(root, "build-tools").isDirectory ||
            File(root, "platform-tools").isDirectory
    }

    fun diagnosticReport(context: Context): String {
        val env = detect(context)
        return buildString {
            appendLine("On-device Gradle environment")
            appendLine("usable=${env.isUsable}")
            env.notes.forEach { appendLine("• $it") }
            if (!env.isUsable) {
                appendLine()
                appendLine("To enable on-device builds in Termux:")
                appendLine("  pkg install openjdk-17")
                appendLine("  pkg install android-sdk  # or install cmdline-tools")
                appendLine("  export ANDROID_SDK_ROOT=\$PREFIX/share/android-sdk")
                appendLine("  export JAVA_HOME=\$PREFIX/lib/jvm/java-17-openjdk")
            }
        }
    }
}
