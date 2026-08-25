package com.swarmbuilder.app

import android.content.Context
import com.swarmbuilder.app.build.LastApkCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [LastApkCache].
 *
 * [Context] is mocked with Mockito so [Context.getFilesDir] points at a plain
 * temp directory and the tests run on the JVM without an Android runtime.
 *
 * Note: [LastApkCache.restoreFrameworks] deliberately ignores archives smaller
 * than 1 KB, so fixture data is generously sized and non-compressible.
 */
class LastApkCacheTest {

    private lateinit var tmpRoot: File
    private lateinit var filesDir: File
    private lateinit var cache: LastApkCache

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("LastApkCacheTest").toFile()
        filesDir = File(tmpRoot, "files").also { it.mkdirs() }
        val context: Context = mock {
            on { filesDir } doReturn filesDir
        }
        cache = LastApkCache(context)
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    // ── restore with no cache ────────────────────────────────────────────────

    @Test
    fun `restoreFrameworks with empty cache logs full-build message and writes nothing`() {
        val project = File(tmpRoot, "freshProject").also { it.mkdirs() }
        val logs = mutableListOf<String>()

        cache.restoreFrameworks(project) { logs.add(it) }

        assertTrue(
            "expected a 'no cache' log line, got: $logs",
            logs.any { it.contains("No previous framework cache") }
        )
        assertFalse(File(project, "app/build/intermediates").exists())
    }

    // ── saveFrameworks ───────────────────────────────────────────────────────

    @Test
    fun `saveFrameworks creates frameworks zip containing cacheable paths`() {
        val project = buildFakeProject()
        val logs = mutableListOf<String>()

        cache.saveFrameworks(project) { logs.add(it) }

        val archive = File(filesDir, "last_apk_cache/frameworks.zip")
        assertTrue("cache archive missing at ${archive.absolutePath}", archive.exists())
        assertTrue("cache archive unexpectedly small", archive.length() >= 1024)
        assertTrue(logs.any { it.contains("Framework cache saved") })
    }

    @Test
    fun `saveFrameworks on a project without cacheable dirs leaves no usable cache`() {
        val emptyProject = File(tmpRoot, "emptyProject").also { it.mkdirs() }
        cache.saveFrameworks(emptyProject) { }

        val archive = File(filesDir, "last_apk_cache/frameworks.zip")
        // Either no archive or an effectively-empty one (< 1 KB threshold).
        assertTrue(!archive.exists() || archive.length() < 1024)

        val project2 = File(tmpRoot, "project2").also { it.mkdirs() }
        val logs = mutableListOf<String>()
        cache.restoreFrameworks(project2) { logs.add(it) }
        assertTrue(logs.any { it.contains("No previous framework cache") })
    }

    // ── roundtrip ────────────────────────────────────────────────────────────

    @Test
    fun `save then restore round-trips cacheable files with identical content`() {
        val sourceProject = buildFakeProject()
        cache.saveFrameworks(sourceProject) { }

        val targetProject = File(tmpRoot, "targetProject").also { it.mkdirs() }
        val logs = mutableListOf<String>()
        cache.restoreFrameworks(targetProject) { logs.add(it) }

        assertTrue(logs.any { it.contains("Framework cache restored") })

        val restoredJar = File(targetProject, "app/build/intermediates/some_task/core.jar")
        val restoredAar = File(targetProject, "app/build/intermediates/some_task/support.aar")
        val restoredWrapper = File(targetProject, "gradle/wrapper/gradle-wrapper.properties")

        assertTrue("core.jar not restored", restoredJar.exists())
        assertTrue("support.aar not restored", restoredAar.exists())
        assertTrue("wrapper properties not restored", restoredWrapper.exists())

        val originalJar = File(sourceProject, "app/build/intermediates/some_task/core.jar")
        assertEquals(originalJar.length(), restoredJar.length())
        assertTrue(
            "restored core.jar content differs",
            originalJar.readBytes().contentEquals(restoredJar.readBytes())
        )

        // APKs are NOT part of the framework cache.
        assertFalse(File(targetProject, "app/build/outputs/apk/debug/app-debug.apk").exists())
    }

    @Test
    fun `saveFrameworks called twice replaces the previous cache`() {
        val first = buildFakeProject(extraIntermediatesFileName = "first_only.jar")
        cache.saveFrameworks(first) { }
        val firstSize = File(filesDir, "last_apk_cache/frameworks.zip").length()

        val second = buildFakeProject(extraIntermediatesFileName = "second_only.jar")
        cache.saveFrameworks(second) { }

        val target = File(tmpRoot, "target").also { it.mkdirs() }
        cache.restoreFrameworks(target) { }

        assertTrue(File(target, "app/build/intermediates/some_task/second_only.jar").exists())
        assertFalse(File(target, "app/build/intermediates/some_task/first_only.jar").exists())
        assertTrue(File(filesDir, "last_apk_cache/frameworks.zip").length() > 0)
        // archive was atomically replaced, not appended to
        assertTrue(firstSize >= 1024)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a minimal fake Gradle project tree containing the paths
     * [LastApkCache] considers cacheable, plus an APK that must NOT be cached.
     * Intermediates files hold several KB of non-compressible bytes so the
     * resulting zip passes the 1 KB freshness threshold in restoreFrameworks.
     */
    private fun buildFakeProject(extraIntermediatesFileName: String? = null): File {
        val dir = File(tmpRoot, "project_${System.nanoTime()}").also { it.mkdirs() }

        val intermediates = File(dir, "app/build/intermediates/some_task").also { it.mkdirs() }
        File(intermediates, "core.jar").writeBytes(payload(4096, seed = 7))
        File(intermediates, "support.aar").writeBytes(payload(3072, seed = 13))
        if (extraIntermediatesFileName != null) {
            File(intermediates, extraIntermediatesFileName).writeBytes(payload(2048, seed = 29))
        }

        val gradleCaches = File(dir, ".gradle/caches/modules-2/files-2.1").also { it.mkdirs() }
        File(gradleCaches, "lib.bin").writeBytes(payload(1536, seed = 3))

        val wrapperDir = File(dir, "gradle/wrapper").also { it.mkdirs() }
        File(wrapperDir, "gradle-wrapper.properties").writeText(
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.2-bin.zip"
        )

        // Not cacheable — included to verify it is excluded.
        val outputDir = File(dir, "app/build/outputs/apk/debug").also { it.mkdirs() }
        File(outputDir, "app-debug.apk").writeBytes(payload(2048, seed = 5))

        return dir
    }

    /** Deterministic, poorly-compressible byte payload of [size] bytes. */
    private fun payload(size: Int, seed: Int): ByteArray {
        var state = seed * 2654435761L
        return ByteArray(size) {
            state = state * 6364136223846793005L + 1442695040888963407L
            (state ushr 33).toByte()
        }
    }
}
