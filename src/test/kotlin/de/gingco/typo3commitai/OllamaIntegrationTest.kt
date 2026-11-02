package de.gingco.typo3commitai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Duration

/**
 * Talks to a real local Ollama. Skipped unless OLLAMA_IT=1, e.g.
 * OLLAMA_IT=1 OLLAMA_MODEL=qwen2.5-coder:14b ./gradlew test
 */
class OllamaIntegrationTest {
    private fun client() = OllamaClient(
        System.getenv("OLLAMA_URL") ?: "http://localhost:11434",
        System.getenv("OLLAMA_MODEL") ?: Settings.Data().model,
        0.2,
        Duration.ofSeconds(300),
    )

    @Test fun `removal-only diff is described as a removal`() {
        assumeTrue(System.getenv("OLLAMA_IT") == "1")
        val diff = """
diff --git a/Configuration/TypoScript/page.typoscript b/Configuration/TypoScript/page.typoscript
--- a/Configuration/TypoScript/page.typoscript
+++ b/Configuration/TypoScript/page.typoscript
@@ -170,22 +170,3 @@ page {
         scripts.type = module
     }
 }
-
-robotsTxt = PAGE
-robotsTxt {
-    typeNum = 201
-    config.disableAllHeaderCode = 1
-    10 = TEXT
-    10.value = User-agent: *
-}
diff --git a/config/sites/main/config.yaml b/config/sites/main/config.yaml
--- a/config/sites/main/config.yaml
+++ b/config/sites/main/config.yaml
@@ -64,4 +64,3 @@ routeEnhancers:
     map:
       /: 0
       sitemap.xml: 1533906435
-      robots.txt: 201
""".trimStart()
        val result = client().generate(Prompts.DEFAULT_SYSTEM, Prompts.userMessage(diff))
        val message = CommitMessageFormatter.format(result)
        println(message)
        val subject = message.lineSequence().first()
        assertTrue(subject, subject.matches(Regex("""^(\[!!!])?\[(BUGFIX|FEATURE|TASK|DOCS)] [A-Z].*""")))
        assertTrue(subject, subject.length <= 72)
        assertTrue(subject, subject.contains("Remove", ignoreCase = true))
        assertTrue(message, message.lines().all { it.length <= 72 })
        assertEquals(1, Regex("""\[(BUGFIX|FEATURE|TASK|DOCS)]""").findAll(subject).count())
    }
}
