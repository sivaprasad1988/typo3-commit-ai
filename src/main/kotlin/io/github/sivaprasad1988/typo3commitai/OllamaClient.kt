package io.github.sivaprasad1988.typo3commitai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Duration

class OllamaClient(
    private val baseUrl: String,
    private val model: String,
    private val temperature: Double,
    timeout: Duration,
) : AiClient {
    private val http = JsonHttp("Ollama", baseUrl, timeout)

    override fun generate(systemPrompt: String, userMessage: String): AiResult {
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("stream", false)
            add("format", JsonParser.parseString(Prompts.RESPONSE_SCHEMA))
            add("messages", JsonArray().apply {
                add(message("system", systemPrompt))
                add(message("user", userMessage))
            })
            add("options", JsonObject().apply {
                addProperty("temperature", temperature)
                addProperty("num_ctx", contextSize(systemPrompt.length + userMessage.length))
            })
        }
        val response = try {
            http.post("/api/chat", body)
        } catch (e: AiException) {
            if (e.cause is java.net.ConnectException) throw AiException("Cannot reach Ollama at $baseUrl. Is Ollama running?", e)
            throw e
        }
        if (response.statusCode() != 200) {
            val error = AiResponse.errorText(response.body())
            val hint = if (response.statusCode() == 404 && "not found" in error) " Run: ollama pull $model" else ""
            throw AiException("Ollama error ${response.statusCode()}: $error.$hint")
        }
        return parse(response.body())
    }

    companion object {
        /** Rough token estimate (~3 chars/token for code) plus room for the answer. */
        fun contextSize(promptChars: Int): Int = (promptChars / 3 + 2048).coerceIn(8192, 32768)

        fun parse(responseBody: String): AiResult {
            val content = try {
                JsonParser.parseString(responseBody).asJsonObject
                    .getAsJsonObject("message").get("content").asString
            } catch (e: Exception) {
                throw AiException("Unexpected Ollama response: ${responseBody.take(300)}", e)
            }
            return AiResponse.parseContent(content)
        }

        internal fun message(role: String, content: String) = JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }
    }
}
