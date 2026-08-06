package com.swarmbuilder.app

import com.swarmbuilder.app.build.LastApkCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [LastApkCache].
 *
 * These tests exercise the cache using a plain temp directory so they run on
 * the JVM without an Android context.  The internal [LastApkCache] constructor
 * that accepts a [File] is used here to avoid the Android runtime dependency.
 */
class LastApkCacheTest {

    private lateinit var tmpRoot: File
    private lateinit var cache: LastApkCache

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("LastApkCacheTest").toFile()
        // Use the internal File-based constructor so tests run on plain JVM.
        cache = LastApkCache(File(tmpRoot, "cache"))
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    // ── hasCache ──────────────────────────────────────────────────────────────

    @Test
    fun `hasCache returns false for empty cache`() {
        assertFalse(cache.hasCache())
    }

    @Test
    fun `hasCache returns true after APK is saved`() {
        val project = buildFakeProject(withApk = true, withFrameworks = false)
        cache.saveFrameworks(project)
        assertTrue(cache.hasCache())
    }

    @Test
    fun `hasCache returns true after frameworks are saved`() {
        val project = buildFakeProject(withApk = false, withFrameworks = true)
        cache.saveFrameworks(project)
        assertTrue(cache.hasCache())
    }

    // ── saveFrameworks ────────────────────────────────────────────────────────

    @Test
    fun `saveFrameworks copies APK into cache`() {
        val project = buildFakeProject(withApk = true, withFrameworks = false)
        val logs = mutableListOf<String>()
        cache.saveFrameworks(project) { logs.add(it) }

        assertTrue("Cached APK missing", File(cache.cacheDir, "app-debug.apk").exists())
        assertTrue(logs.any { it.contains("saved APK") })
    }

    @Test
    fun `saveFrameworks copies JAR and AAR files into frameworks cache`() {
        val project = buildFakeProject(withApk = false, withFrameworks = true)
        cache.saveFrameworks(project)

        val fw = File(cache.cacheDir, "frameworks")
        val names = fw.listFiles()?.map { it.name }.orEmpty()
        assertTrue("core.jar missing", names.contains("core.jar"))
        assertTrue("support.aar missing", names.contains("support.aar"))
    }

    @Test
    fun `saveFrameworks logs gracefully when no APK found`() {
        val project = buildFakeProject(withApk = false, withFrameworks = false)
        val logs = mutableListOf<String>()
        cache.saveFrameworks(project) { logs.add(it) }

        assertTrue(logs.any { it.contains("no APK found") || it.contains("skipping APK") })
    }

    @Test
    fun `saveFrameworks logs gracefully when no intermediates directory`() {
        val project = File(tmpRoot, "emptyProject").also { it.mkdirs() }
        val logs = mutableListOf<String>()
        cache.saveFrameworks(project) { logs.add(it) }

        assertFalse(cache.hasCache())
        assertTrue(logs.any { it.contains("intermediates directory not found") || it.contains("skipping") })
    }

    // ── restoreFrameworks ─────────────────────────────────────────────────────

    @Test
    fun `restoreFrameworks returns false and logs when cache is empty`() {
        val project = File(tmpRoot, "newProject").also { it.mkdirs() }
        val logs = mutableListOf<String>()
        val restored = cache.restoreFrameworks(project) { logs.add(it) }

        assertFalse(restored)
        assertTrue(logs.any { it.contains("no previous APK") || it.contains("no cached framework") })
    }

    @Test
    fun `restoreFrameworks copies APK to project output directory`() {
        val sourceProject = buildFakeProject(withApk = true, withFrameworks = false)
        cache.saveFrameworks(sourceProject)

        val targetProject = File(tmpRoot, "targetProject").also { it.mkdirs() }
        val restored = cache.restoreFrameworks(targetProject)

        assertTrue(restored)
        val restoredApk = File(targetProject, "app/build/outputs/apk/debug/app-debug.apk")
        assertTrue("Restored APK missing", restoredApk.exists())
    }

    @Test
    fun `restoreFrameworks copies framework files to cached_frameworks directory`() {
        val sourceProject = buildFakeProject(withApk = false, withFrameworks = true)
        cache.saveFrameworks(sourceProject)

        val targetProject = File(tmpRoot, "targetProject").also { it.mkdirs() }
        cache.restoreFrameworks(targetProject)

        val fw = File(targetProject, "app/build/intermediates/cached_frameworks")
        val names = fw.listFiles()?.map { it.name }.orEmpty()
        assertTrue("core.jar not restored", names.contains("core.jar"))
        assertTrue("support.aar not restored", names.contains("support.aar"))
    }

    @Test
    fun `restoreFrameworks returns true when both APK and frameworks are restored`() {
        val source = buildFakeProject(withApk = true, withFrameworks = true)
        cache.saveFrameworks(source)

        val target = File(tmpRoot, "target").also { it.mkdirs() }
        val restored = cache.restoreFrameworks(target)
        assertTrue(restored)
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    fun `clear removes all cached files`() {
        val project = buildFakeProject(withApk = true, withFrameworks = true)
        cache.saveFrameworks(project)
        assertTrue(cache.hasCache())

        cache.clear()
        assertFalse(cache.hasCache())
    }

    // ── roundtrip ────────────────────────────────────────────────────────────

    @Test
    fun `save then restore produces identical APK content`() {
        val project = buildFakeProject(withApk = false, withFrameworks = false)
        val originalContent = "APK_BINARY_CONTENT"
        val outputDir = File(project, "app/build/outputs/apk/debug").also { it.mkdirs() }
        File(outputDir, "app-debug.apk").writeText(originalContent)

        cache.saveFrameworks(project)

        val target = File(tmpRoot, "target").also { it.mkdirs() }
        cache.restoreFrameworks(target)

        val restoredApk = File(target, "app/build/outputs/apk/debug/app-debug.apk")
        assertEquals(originalContent, restoredApk.readText())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a minimal fake Gradle project tree inside [tmpRoot].
     *
     * @param withApk         If true, places a dummy APK in the debug output dir.
     * @param withFrameworks  If true, places dummy JAR and AAR files in intermediates.
     */
    private fun buildFakeProject(withApk: Boolean, withFrameworks: Boolean): File {
        val dir = File(tmpRoot, "project_${System.nanoTime()}").also { it.mkdirs() }

        if (withApk) {
            val outputDir = File(dir, "app/build/outputs/apk/debug").also { it.mkdirs() }
            File(outputDir, "app-debug.apk").writeText("FAKE_APK")
        }
        if (withFrameworks) {
            val intermediates = File(dir, "app/build/intermediates/some_task").also { it.mkdirs() }
            File(intermediates, "core.jar").writeText("FAKE_JAR")
            File(intermediates, "support.aar").writeText("FAKE_AAR")
        }
        return dir
    }
}
