package com.swarmbuilder.app

import com.swarmbuilder.app.models.AppSpec
import com.swarmbuilder.app.models.LlmProvider
import com.swarmbuilder.app.models.LogLevel
import com.swarmbuilder.app.models.SourceFile
import com.swarmbuilder.app.models.SwarmAgent
import com.swarmbuilder.app.models.AgentRole
import com.swarmbuilder.app.models.AgentStatus
import com.swarmbuilder.app.models.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun `AppSpec defaults are sensible`() {
        val spec = AppSpec(prompt = "A calculator app")
        assertEquals("A calculator app", spec.prompt)
        assertEquals("", spec.appName)
        assertTrue(spec.features.isEmpty())
    }

    @Test
    fun `SourceFile stores path and content`() {
        val sf = SourceFile("app/src/main/java/Foo.kt", "class Foo")
        assertEquals("app/src/main/java/Foo.kt", sf.relativePath)
        assertEquals("class Foo", sf.content)
    }

    @Test
    fun `SwarmAgent defaults to IDLE status`() {
        val agent = SwarmAgent(
            id = "a1",
            name = "Coder",
            role = AgentRole.CODER,
            provider = LlmProvider.HERMES_AGENT,
            modelId = "hermes-agent"
        )
        assertEquals(AgentStatus.IDLE, agent.status)
    }

    @Test
    fun `UserSettings defaults use HERMES_AGENT provider and local-first off`() {
        val settings = UserSettings()
        assertEquals(LlmProvider.HERMES_AGENT, settings.preferredProvider)
        assertFalse(settings.useLocalOllama)
        assertFalse(settings.localFirst)
        assertEquals("llama3", settings.ollamaModel)
    }

    @Test
    fun `AgentConfig has blank defaults`() {
        val config = com.swarmbuilder.app.models.AgentConfig()
        assertEquals("", config.modelId)
        assertEquals("", config.baseUrl)
        assertEquals("", config.apiKey)
        assertEquals("", config.systemPrompt)
    }

    @Test
    fun `LogLevel enum contains all expected values`() {
        val levels = LogLevel.values()
        assertTrue(levels.contains(LogLevel.INFO))
        assertTrue(levels.contains(LogLevel.SUCCESS))
        assertTrue(levels.contains(LogLevel.WARNING))
        assertTrue(levels.contains(LogLevel.ERROR))
    }

    @Test
    fun `LlmProvider baseUrl is not blank`() {
        LlmProvider.values().forEach { provider ->
            assertTrue("${provider.name} baseUrl is blank", provider.baseUrl.isNotBlank())
        }
    }

    @Test
    fun `HERMES_AGENT does not require API key`() {
        assertFalse(LlmProvider.HERMES_AGENT.requiresApiKey)
    }

    @Test
    fun `HERMES_AGENT isProviderAvailable returns true`() {
        assertTrue(UserSettings().isProviderAvailable(LlmProvider.HERMES_AGENT))
    }
}
