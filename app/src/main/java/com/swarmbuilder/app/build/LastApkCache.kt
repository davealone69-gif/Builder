package com.swarmbuilder.app.build

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Caches framework / intermediate artifacts from the last successful
 * on-device Gradle build so the next generated project can reuse them
 * and avoid full re-downloads / recompilation of the Android SDK bits.
 *
 * Cache location:  <app filesDir>/last_apk_cache/
 * Archive name:    frameworks.zip
 */
class LastApkCache(context: Context) {

    private val cacheRoot = File(context.filesDir, "last_apk_cache").also { it.mkdirs() }
    private val archive = File(cacheRoot, "frameworks.zip")

    /**
     * Paths relative to a generated project that are worth caching.
     * These are the heavy Android Gradle Plugin / SDK artifacts that
     * rarely change between builds.
     */
    private val CACHEABLE_RELATIVE_PATHS = listOf(
        "app/build/intermediates",
        ".gradle/caches",
        "gradle/wrapper"
    )

    /**
     * Restore previously cached framework artifacts into [projectDir].
     * Safe to call even when no cache exists.
     */
    fun restoreFrameworks(projectDir: File, log: (String) -> Unit) {
        if (!archive.exists() || archive.length() < 1024) {
            log("No previous framework cache found – full build will be performed")
            return
        }

        try {
            log("Restoring framework cache (${archive.length() / 1024} KB)…")
            unzip(archive, projectDir)
            log("Framework cache restored")
        } catch (e: Exception) {
            log("Failed to restore framework cache: ${e.message}")
            // Non-fatal – continue with a cold build
        }
    }

    /**
     * Save the framework / intermediate artifacts from a successful build
     * so the next run can reuse them.
     */
    fun saveFrameworks(projectDir: File, log: (String) -> Unit) {
        try {
            val tempZip = File(cacheRoot, "frameworks.tmp.zip")
            if (tempZip.exists()) tempZip.delete()

            ZipOutputStream(FileOutputStream(tempZip)).use { zos ->
                CACHEABLE_RELATIVE_PATHS.forEach { relative ->
                    val src = File(projectDir, relative)
                    if (src.exists()) {
                        addDirectoryToZip(src, relative, zos)
                    }
                }
            }

            // Atomic replace
            if (archive.exists()) archive.delete()
            tempZip.renameTo(archive)

            log("Framework cache saved (${archive.length() / 1024} KB)")
        } catch (e: Exception) {
            log("Failed to save framework cache: ${e.message}")
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun addDirectoryToZip(dir: File, basePath: String, zos: ZipOutputStream) {
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val entryName = basePath + "/" + file.relativeTo(dir).path.replace('\\', '/')
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
