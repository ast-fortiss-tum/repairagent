package org.example.utils

import org.example.config.ApplicationConfig
import org.example.config.SecretConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmAccessTest {

    @Test
    fun `refused credentials and exhausted quota are fatal, other errors are not`() {
        assertNotNull(fatalGatewayError(IllegalStateException("wrapper", RuntimeException("Error from OpenAI API: 401 Unauthorized"))))
        assertNotNull(fatalGatewayError(RuntimeException("You exceeded your current quota: insufficient_quota")))
        assertNotNull(fatalGatewayError(RuntimeException("403 Forbidden")))
        assertNull(fatalGatewayError(IllegalStateException("maximum context length is 128000 tokens")))
        assertNull(fatalGatewayError(RuntimeException("429 Too Many Requests")))
    }

    @Test
    fun `every supported model resolves to its provider and output limit`() {
        assertEquals(16_384, Models.byName("gpt-4o").maxOutputTokens)
        assertEquals("OPENAI_API_KEY", Models.byName("GPT-4o").apiKeyVariable)
        assertEquals("GOOGLE_API_KEY", Models.byName("gemini-2.5-flash").apiKeyVariable)
        assertEquals("ANTHROPIC_API_KEY", Models.byName("claude-sonnet-4").apiKeyVariable)
        assertFailsWith<IllegalArgumentException> { Models.byName("unknown-model") }
    }

    @Test
    fun `spend follows the token counts and the configured prices`() {
        val meter = SpendMeter(2.50, 10.00)
        meter.add(1_000_000, 100_000)
        meter.record(null)
        assertEquals(3.50, meter.dollars, 1e-9)
    }

    @Test
    fun `a missing API key is reported with the variable to set`() {
        val utils = AgentUtils(SecretConfig(), ApplicationConfig(llmModel = "gpt-4o"))
        val e = assertFailsWith<IllegalStateException> { utils.getExecutor() }
        assertTrue(e.message!!.contains("OPENAI_API_KEY"), e.message)
    }
}
