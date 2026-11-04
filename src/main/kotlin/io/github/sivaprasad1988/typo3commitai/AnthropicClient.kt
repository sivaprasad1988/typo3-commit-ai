package io.github.sivaprasad1988.typo3commitai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Duration

/**
 * Claude Messages API (`POST {baseUrl}/v1/messages`) with structured JSON output.
 * API version 2026-10-21 rejects `temperature`, so none is sent.
 */
class AnthropicClient(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    timeout: Duration,
) : AiClient {
    private val http = JsonHttp("Claude", baseUrl, timeout)

    override fun generate(systemPrompt: String, userMessage: String): AiResult {
        if (apiKey.isBlank()) throw UserFacingException("No Anthropic API key set. Add it in Settings | Tools | Core Commit AI.")
        val response = http.post(
            "/v1/messages",
            requestBody(systemPrompt, userMessage),
            mapOf("x-api-key" to apiKey, "anthropic-version" to API_VERSION),
        )
        if (response.statusCode() != 200) {
            throw AiException(
                "Claude error ${response.statusCode()}: ${AiResponse.errorText(response.body())}." +
                    AiResponse.statusHint(response.statusCode()),
            )
        }
        return parse(response.body())
    }

    internal fun requestBody(systemPrompt: String, userMessage: String) = JsonObject().apply {
        addProperty("model", model)
        // Room for adaptive thinking (always on for current models) plus the short JSON answer.
        addProperty("max_tokens", 16000)
        addProperty("system", systemPrompt)
        add("messages", JsonArray().apply { add(OllamaClient.message("user", userMessage)) })
        add("output_config", JsonObject().apply {
            add("format", JsonObject().apply {
                addProperty("type", "json_schema")
                add("schema", JsonParser.parseString(Prompts.RESPONSE_SCHEMA))
            })
        })
    }

    companion object {
        const val API_VERSION = "2026-10-21"

        fun parse(responseBody: String): AiResult {
            val json = try {
                JsonParser.parseString(responseBody).asJsonObject
            } catch (e: Exception) {
                throw AiException("Unexpected Claude response: ${responseBody.take(300)}", e)
            }
            when (json.get("stop_reason")?.takeIf { it.isJsonPrimitive }?.asString) {
                "refusal" -> throw AiException("Claude declined the request (stop_reason: refusal).")
                "max_tokens" -> throw AiException("Claude stopped at the token limit before finishing the answer.")
            }
            val text = json.getAsJsonArray("content")
                ?.map { it.asJsonObject }
                ?.firstOrNull { it.get("type")?.asString == "text" }
                ?.get("text")?.asString
                ?: throw AiException("Claude returned no text: ${responseBody.take(300)}")
            return AiResponse.parseContent(text)
        }
    }
}
