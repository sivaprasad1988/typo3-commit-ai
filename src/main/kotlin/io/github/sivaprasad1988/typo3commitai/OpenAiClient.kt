package io.github.sivaprasad1988.typo3commitai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Duration

/**
 * OpenAI Chat Completions (`POST {baseUrl}/chat/completions`). Works with any
 * OpenAI-compatible server (OpenRouter, LM Studio, ...) by changing the base URL.
 */
class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val temperature: Double,
    timeout: Duration,
) : AiClient {
    private val http = JsonHttp("OpenAI", baseUrl, timeout)

    override fun generate(systemPrompt: String, userMessage: String): AiResult {
        if (apiKey.isBlank()) throw UserFacingException("No OpenAI API key set. Add it in Settings | Tools | Core Commit AI.")
        var response = http.post("/chat/completions", requestBody(systemPrompt, userMessage, withTemperature = true), auth())
        // Reasoning models (e.g. GPT-5 family) reject non-default temperature with a 400: retry once without it.
        if (response.statusCode() == 400 && "temperature" in response.body()) {
            response = http.post("/chat/completions", requestBody(systemPrompt, userMessage, withTemperature = false), auth())
        }
        if (response.statusCode() != 200) {
            throw AiException(
                "OpenAI error ${response.statusCode()}: ${AiResponse.errorText(response.body())}." +
                    AiResponse.statusHint(response.statusCode()),
            )
        }
        return parse(response.body())
    }

    private fun auth() = mapOf("Authorization" to "Bearer $apiKey")

    internal fun requestBody(systemPrompt: String, userMessage: String, withTemperature: Boolean) = JsonObject().apply {
        addProperty("model", model)
        add("messages", JsonArray().apply {
            add(OllamaClient.message("system", systemPrompt))
            add(OllamaClient.message("user", userMessage))
        })
        add("response_format", JsonObject().apply {
            addProperty("type", "json_schema")
            add("json_schema", JsonObject().apply {
                addProperty("name", "commit_message")
                addProperty("strict", true)
                add("schema", JsonParser.parseString(Prompts.RESPONSE_SCHEMA))
            })
        })
        if (withTemperature) addProperty("temperature", temperature)
    }

    companion object {
        fun parse(responseBody: String): AiResult {
            val message = try {
                JsonParser.parseString(responseBody).asJsonObject
                    .getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
            } catch (e: Exception) {
                throw AiException("Unexpected OpenAI response: ${responseBody.take(300)}", e)
            }
            message.get("refusal")?.takeIf { it.isJsonPrimitive }?.asString?.let {
                throw AiException("Model refused: $it")
            }
            val content = message.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw AiException("OpenAI returned no content: ${responseBody.take(300)}")
            return AiResponse.parseContent(content)
        }
    }
}
