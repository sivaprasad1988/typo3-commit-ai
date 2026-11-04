package io.github.sivaprasad1988.typo3commitai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class ProviderTest {
    private val expected = AiResult("TASK", false, "Remove x", listOf("Drop y"))
    private val content = """{\"type\":\"TASK\",\"breaking\":false,\"subject\":\"Remove x\",\"bullets\":[\"Drop y\"]}"""

    @Test fun `openai response is parsed`() {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant","content":"$content","refusal":null},"finish_reason":"stop"}]}"""
        assertEquals(expected, OpenAiClient.parse(body))
    }

    @Test(expected = AiException::class) fun `openai refusal raises`() {
        OpenAiClient.parse("""{"choices":[{"message":{"role":"assistant","content":null,"refusal":"No."}}]}""")
    }

    @Test fun `claude response skips thinking blocks`() {
        val body = """{"content":[{"type":"thinking","thinking":"","signature":"x"},{"type":"text","text":"$content"}],"stop_reason":"end_turn"}"""
        assertEquals(expected, AnthropicClient.parse(body))
    }

    @Test(expected = AiException::class) fun `claude refusal raises`() {
        AnthropicClient.parse("""{"content":[],"stop_reason":"refusal"}""")
    }

    @Test fun `fenced json is accepted`() {
        assertEquals(expected, AiResponse.parseContent("```json\n" + content.replace("\\\"", "\"") + "\n```"))
    }

    @Test fun `claude request has structured output and no temperature`() {
        val body = AnthropicClient("https://api.anthropic.com", "k", "claude-opus-5-5", Duration.ofSeconds(5))
            .requestBody("sys", "diff")
        assertFalse(body.has("temperature"))
        assertEquals("sys", body.get("system").asString)
        val format = body.getAsJsonObject("output_config").getAsJsonObject("format")
        assertEquals("json_schema", format.get("type").asString)
        assertFalse(format.getAsJsonObject("schema").get("additionalProperties").asBoolean)
    }

    @Test fun `openai request uses strict json schema`() {
        val client = OpenAiClient("https://api.openai.com/v1", "k", "gpt-6.1-sol", 0.2, Duration.ofSeconds(5))
        val body = client.requestBody("sys", "diff", withTemperature = true)
        val schema = body.getAsJsonObject("response_format").getAsJsonObject("json_schema")
        assertTrue(schema.get("strict").asBoolean)
        assertEquals("commit_message", schema.get("name").asString)
        assertTrue(body.has("temperature"))
        assertFalse(client.requestBody("sys", "diff", withTemperature = false).has("temperature"))
    }
}
