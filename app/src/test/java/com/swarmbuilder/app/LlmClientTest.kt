package com.swarmbuilder.app

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
        val settings = UserSettings()
        LlmProvider.values().forEach { provider ->
            val model = LlmClient.defaultModelFor(provider, settings)
            // CUSTOM provider returns empty string by default (user must fill it in)
            if (provider != LlmProvider.CUSTOM) {
                assertNotNull("Model for $provider should not be null", model)
                assert(model.isNotBlank()) { "Model for $provider should not be blank" }
            }
        }
    }

    @Test
    fun `defaultModelFor HERMES_AGENT returns hermes-agent`() {
        assertEquals("hermes-agent", LlmClient.defaultModelFor(LlmProvider.HERMES_AGENT, UserSettings()))
    }

    @Test
    fun `defaultModelFor GROQ returns current working model`() {
        assertEquals("openai/gpt-oss-120b", LlmClient.defaultModelFor(LlmProvider.GROQ, UserSettings()))
    }

    @Test
    fun `defaultModelFor OLLAMA_LOCAL returns configured model`() {
        assertEquals(
            "llama3",
            LlmClient.defaultModelFor(LlmProvider.OLLAMA_LOCAL, UserSettings(ollamaModel = "llama3"))
        )
    }

    @Test
    fun `defaultModelFor local OpenAI compatible returns configured model`() {
        assertEquals(
            "local-model",
            LlmClient.defaultModelFor(LlmProvider.OPENAI_COMPAT_LOCAL, UserSettings())
        )
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
    fun `LlmClient constructs with blank settings`() {
        val settings = UserSettings()
        val client = LlmClient(settings)
        assertNotNull(client)
    }

    @Test
    fun `LlmClient constructs with Groq key set`() {
        val settings = UserSettings(groqApiKey = "gsk_test123")
        val client = LlmClient(settings)
        assertNotNull(client)
    }
}
