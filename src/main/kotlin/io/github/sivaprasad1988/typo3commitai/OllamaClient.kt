package io.github.sivaprasad1988.typo3commitai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OllamaException(message: String, cause: Throwable? = null) : Exception(message, cause)

class OllamaClient(
    private val baseUrl: String,
    private val model: String,
    private val temperature: Double,
    private val timeout: Duration,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun generate(systemPrompt: String, userMessage: String): AiResult {
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
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/api/chat"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.ConnectException) {
            throw OllamaException("Cannot reach Ollama at $baseUrl. Is Ollama running?", e)
        } catch (e: java.net.http.HttpTimeoutException) {
            throw OllamaException("Ollama did not answer within ${timeout.seconds} s.", e)
        }
        if (response.statusCode() != 200) {
            val error = runCatching {
                JsonParser.parseString(response.body()).asJsonObject.get("error")?.asString
            }.getOrNull() ?: response.body().take(300)
            val hint = if (response.statusCode() == 404 && "not found" in error) " Run: ollama pull $model" else ""
            throw OllamaException("Ollama error ${response.statusCode()}: $error.$hint")
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
                throw OllamaException("Unexpected Ollama response: ${responseBody.take(300)}", e)
            }
            val json = try {
                JsonParser.parseString(content).asJsonObject
            } catch (e: Exception) {
                throw OllamaException("Model did not return JSON: ${content.take(300)}", e)
            }
            return AiResult(
                type = json.get("type")?.asString.orEmpty(),
                breaking = json.get("breaking")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                subject = json.get("subject")?.asString.orEmpty(),
                bullets = json.get("bullets")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
            ).also {
                if (it.subject.isBlank()) throw OllamaException("Model returned an empty subject.")
            }
        }

        private fun message(role: String, content: String) = JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }
    }
}
