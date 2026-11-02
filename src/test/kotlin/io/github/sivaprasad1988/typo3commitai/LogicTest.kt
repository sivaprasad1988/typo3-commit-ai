package io.github.sivaprasad1988.typo3commitai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private val f = CommitMessageFormatter

    @Test fun `keyword comes from code exactly once even if model adds one`() {
        val r = AiResult("feature", false, "[FEATURE] [TASK] add robots.txt middleware.", emptyList())
        assertEquals("[FEATURE] Add robots.txt middleware", f.subjectLine(r))
    }

    @Test fun `unknown type falls back to TASK and breaking adds prefix`() {
        assertEquals("[!!!][TASK] Remove hook", f.subjectLine(AiResult("CHORE", true, "Remove hook", emptyList())))
    }

    @Test fun `bullets are normalised, capped at five and wrapped at 72 with hanging indent`() {
        val long = "Serve robots.txt from a middleware so every site on the shared host decides about indexing itself"
        val lines = f.bodyLines(listOf("- $long", "1. Two", "", "* Two", "Three", "Four", "Five", "Six"))
        assertTrue(lines.all { it.length <= 72 })
        assertTrue(lines[0].startsWith("* Serve"))
        assertTrue(lines[1].startsWith("  "))
        // The long bullet counts toward the cap of five, so "Six" is dropped.
        assertEquals(listOf("* Two", "* Three", "* Four", "* Five"), lines.drop(2))
    }

    @Test fun `full message with footer`() {
        val msg = f.format(
            AiResult("TASK", false, "Remove obsolete robots.txt page type", listOf("Drop the TypoScript page type")),
            FooterOptions(resolves = listOf("12345"), releases = "main,  13.4"),
        )
        assertEquals(
            "[TASK] Remove obsolete robots.txt page type\n\n* Drop the TypoScript page type\n\n" +
                "Resolves: #12345\nReleases: main, 13.4",
            msg,
        )
    }

    @Test fun `issue numbers from branch names`() {
        val p = Settings.Data().issueRegex
        assertEquals(listOf("12345"), f.issuesFromBranch("feature/12345-robots-txt", p))
        assertEquals(listOf("987"), f.issuesFromBranch("bugfix/987", p))
        assertEquals(emptyList<String>(), f.issuesFromBranch("develop", p))
        assertEquals(emptyList<String>(), f.issuesFromBranch("feature/typo3-v13", p))
        assertEquals(emptyList<String>(), f.issuesFromBranch("main", "([unclosed"))
    }

    @Test fun `diff budget reports cut and omitted files instead of failing silently`() {
        val files = listOf(FileDiff("a", "x".repeat(1000)), FileDiff("b", "y".repeat(5000)), FileDiff("c", "z".repeat(5000)))
        val fit = DiffBudget.fit(files, 3000)
        assertEquals(listOf("b"), fit.truncated)
        assertEquals(listOf("c"), fit.omitted)
        assertFalse(fit.isComplete)
        assertTrue(fit.text.contains("Also changed (diff omitted for size): c"))
        assertTrue(DiffBudget.fit(files.take(1), 3000).isComplete)
    }

    @Test fun `git diff output is split per file`() {
        val diff = "diff --git a/x.php b/x.php\n--- a/x.php\n+++ b/x.php\n@@ -1 +1 @@\n-a\n+b\n" +
            "diff --git a/old.ts b/new.ts\nrename from old.ts\nrename to new.ts\n"
        assertEquals(listOf("x.php", "new.ts"), DiffBudget.split(diff).map { it.path })
    }

    @Test fun `new file diff marks every line as added`() {
        val d = DiffBudget.newFile("a.txt", "one\ntwo\n")
        assertTrue(d.text.endsWith("@@ -0,0 +1,2 @@\n+one\n+two"))
    }

    @Test fun `ollama structured response is parsed`() {
        val body = """{"message":{"role":"assistant","content":"{\"type\":\"TASK\",\"breaking\":false,\"subject\":\"Remove x\",\"bullets\":[\"Drop y\"]}"}}"""
        assertEquals(AiResult("TASK", false, "Remove x", listOf("Drop y")), OllamaClient.parse(body))
    }

    @Test fun `context size grows with the prompt but stays bounded`() {
        assertEquals(8192, OllamaClient.contextSize(100))
        assertEquals(32768, OllamaClient.contextSize(1_000_000))
    }
}
