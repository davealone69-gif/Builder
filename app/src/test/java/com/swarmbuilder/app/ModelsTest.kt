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
    fun `UserSettings defaults use HERMES_AGENT provider`() {
        val settings = UserSettings()
        assertEquals(LlmProvider.HERMES_AGENT, settings.preferredProvider)
        assertFalse(settings.useLocalOllama)
        assertFalse(settings.localFirst)
        assertEquals("llama3", settings.ollamaModel)
    }

    @Test
    fun `AgentConfig defaults are blank`() {
        val config = com.swarmbuilder.app.models.AgentConfig()
        assertEquals("", config.modelId)
        assertEquals("", config.baseUrl)
        assertEquals("", config.apiKey)
        assertEquals("", config.systemPrompt)
    }

    @Test
    fun `HERMES_AGENT does not require API key`() {
        assertFalse(LlmProvider.HERMES_AGENT.requiresApiKey)
    }

    @Test
    fun `HERMES_AGENT isProviderAvailable returns true even with no keys`() {
        val settings = UserSettings()
        assertTrue(settings.isProviderAvailable(LlmProvider.HERMES_AGENT))
    }

    @Test
    fun `HERMES_AGENT resolveApiKey returns built-in key`() {
        val settings = UserSettings()
        assertEquals("change-me-local-dev", settings.resolveApiKey(LlmProvider.HERMES_AGENT))
    }

    @Test
    fun `GROQ requires API key and isProviderAvailable returns false when key is blank`() {
        assertTrue(LlmProvider.GROQ.requiresApiKey)
        val settings = UserSettings()
        assertFalse(settings.isProviderAvailable(LlmProvider.GROQ))
    }

    @Test
    fun `GROQ isProviderAvailable returns true when key is set`() {
        val settings = UserSettings(groqApiKey = "gsk_test123")
        assertTrue(settings.isProviderAvailable(LlmProvider.GROQ))
    }

    @Test
    fun `getFallbackChain returns HERMES_AGENT first`() {
        val settings = UserSettings()
        val chain = settings.getFallbackChain(LlmProvider.GROQ)
        assertTrue(chain.firstOrNull() == LlmProvider.HERMES_AGENT)
    }

    @Test
    fun `getFallbackChain excludes the given provider`() {
        val settings = UserSettings(groqApiKey = "gsk_test123")
        val chain = settings.getFallbackChain(LlmProvider.GROQ)
        assertFalse(chain.contains(LlmProvider.GROQ))
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
    fun `OLLAMA_LOCAL does not require API key`() {
        assertFalse(LlmProvider.OLLAMA_LOCAL.requiresApiKey)
    }

    @Test
    fun `OPENAI_COMPAT_LOCAL does not require API key`() {
        assertFalse(LlmProvider.OPENAI_COMPAT_LOCAL.requiresApiKey)
    }
}
