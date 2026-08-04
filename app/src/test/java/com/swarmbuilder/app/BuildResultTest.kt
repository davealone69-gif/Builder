package com.swarmbuilder.app

import com.swarmbuilder.app.models.AppSpec
import com.swarmbuilder.app.models.BuildResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildResultTest {

    @Test
    fun `successful BuildResult holds apk path`() {
        val result = BuildResult(
            success = true,
            appName = "MyApp",
            apkPath = "/data/user/0/com.swarmbuilder.app/files/apks/MyApp-debug.apk"
        )
        assertTrue(result.success)
        assertEquals("MyApp", result.appName)
        assertNull(result.errorMessage)
        assertTrue(result.apkPath!!.endsWith(".apk"))
    }

    @Test
    fun `failed BuildResult holds error message`() {
        val result = BuildResult(
            success = false,
            appName = "BrokenApp",
            errorMessage = "Compilation error: unresolved reference"
        )
        assertFalse(result.success)
        assertNull(result.apkPath)
        assertEquals("Compilation error: unresolved reference", result.errorMessage)
    }

    @Test
    fun `BuildResult logs default to empty`() {
        val result = BuildResult(success = true, appName = "App")
        assertTrue(result.logs.isEmpty())
    }

    @Test
    fun `AppSpec sanitisation for safe directory name`() {
        val spec = AppSpec(
            prompt = "Build me an app",
            appName = "My Cool App! 2.0",
            packageName = "com.example.mycoolapp"
        )
        val safeName = spec.appName.replace(Regex("[^A-Za-z0-9_]"), "_")
        assertEquals("My_Cool_App__2_0", safeName)
    }
}
