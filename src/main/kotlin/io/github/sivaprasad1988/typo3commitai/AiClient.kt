package io.github.sivaprasad1988.typo3commitai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One request: system prompt + diff -> structured commit message. */
interface AiClient {
    fun generate(systemPrompt: String, userMessage: String): AiResult
}

enum class Provider(val label: String) {
    OLLAMA("Ollama (local)"),
    OPENAI("OpenAI / OpenAI-compatible"),
    ANTHROPIC("Claude (Anthropic)");

    override fun toString() = label
}

/** Shared HTTP plumbing for all providers. */
internal class JsonHttp(private val providerName: String, private val baseUrl: String, private val timeout: Duration) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun post(path: String, body: JsonObject, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        return try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.ConnectException) {
            throw AiException("Cannot reach $providerName at $baseUrl.", e)
        } catch (e: java.net.http.HttpTimeoutException) {
            throw AiException("$providerName did not answer within ${timeout.seconds} s.", e)
        }
    }
}

object AiResponse {
    /** Turns the model's JSON text into an [AiResult]. */
    fun parseContent(content: String): AiResult {
        val json = try {
            JsonParser.parseString(stripFences(content)).asJsonObject
        } catch (e: Exception) {
            throw AiException("Model did not return JSON: ${content.take(300)}", e)
        }
        return AiResult(
            type = json.get("type")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            breaking = json.get("breaking")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            subject = json.get("subject")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            bullets = json.get("bullets")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
        ).also {
            if (it.subject.isBlank()) throw AiException("Model returned an empty subject.")
        }
    }

    /** Some OpenAI-compatible servers wrap JSON in ``` fences despite the schema. */
    private fun stripFences(s: String): String =
        s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    /** Best-effort error text from a JSON error body (`{"error": "..."}` or `{"error": {"message": "..."}}`). */
    fun errorText(body: String): String = runCatching {
        val e = JsonParser.parseString(body).asJsonObject.get("error")
        if (e.isJsonObject) e.asJsonObject.get("message").asString else e.asString
    }.getOrNull() ?: body.take(300)

    fun statusHint(status: Int): String = when (status) {
        401, 403 -> " Check the API key in Settings | Tools | Core Commit AI."
        429 -> " Rate limit or quota reached; wait and try again."
        else -> ""
    }
}
