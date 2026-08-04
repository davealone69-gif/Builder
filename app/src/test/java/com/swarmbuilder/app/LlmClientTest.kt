package com.swarmbuilder.app

import com.swarmbuilder.app.models.AppSpec
import com.swarmbuilder.app.models.LlmProvider
import com.swarmbuilder.app.models.SourceFile
import com.swarmbuilder.app.models.UserSettings
import com.swarmbuilder.app.swarm.LlmClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LlmClientTest {

    @Test
    fun `defaultModelFor returns non-blank strings for all providers`() {
        LlmProvider.values().forEach { provider ->
            val model = LlmClient.defaultModelFor(provider)
            assertNotNull(model)
            assert(model.isNotBlank()) { "Model for $provider should not be blank" }
        }
    }

    @Test
    fun `defaultModelFor GROQ returns llama3 model`() {
        assertEquals("llama3-70b-8192", LlmClient.defaultModelFor(LlmProvider.GROQ))
    }

    @Test
    fun `defaultModelFor OLLAMA_LOCAL returns llama3`() {
        assertEquals("llama3", LlmClient.defaultModelFor(LlmProvider.OLLAMA_LOCAL))
    }

    @Test
    fun `SourceFile list can be built and filtered`() {
        val files = listOf(
            SourceFile("app/src/main/java/MainActivity.kt", "class MainActivity"),
            SourceFile("app/src/main/res/layout/activity_main.xml", "<LinearLayout/>"),
            SourceFile("app/build.gradle", "plugins { }"),
            SourceFile("settings.gradle", "rootProject.name = \"TestApp\"")
        )

        val kotlinFiles = files.filter { it.relativePath.endsWith(".kt") }
        assertEquals(1, kotlinFiles.size)

        val settingsFile = files.find { it.relativePath == "settings.gradle" }
        assertNotNull(settingsFile)
        val appName = settingsFile!!.content.lines()
            .firstOrNull { it.contains("rootProject.name") }
            ?.substringAfter("=")?.trim()?.trim('"')
        assertEquals("TestApp", appName)
    }

    @Test
    fun `UserSettings with blank keys still constructs`() {
        val settings = UserSettings(
            groqApiKey = "",
            huggingFaceToken = "",
            openRouterApiKey = "",
            githubToken = "",
            githubUsername = ""
        )
        // LlmClient can be constructed even with empty keys (API calls would fail at runtime)
        val client = LlmClient(settings)
        assertNotNull(client)
    }
}
