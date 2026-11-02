package io.github.sivaprasad1988.typo3commitai

/** What the model returns (see [Prompts.RESPONSE_SCHEMA]). */
data class AiResult(
    val type: String,
    val breaking: Boolean,
    val subject: String,
    val bullets: List<String>,
)

data class FooterOptions(
    val resolves: List<String> = emptyList(),
    val releases: String = "",
)

/**
 * Turns the model's structured answer into a TYPO3 commit message. Everything
 * the TYPO3 rules make mechanical (keyword prefix, [!!!], bullet format,
 * 72-column wrapping, footer syntax) is done here, not left to the model.
 */
object CommitMessageFormatter {
    const val WRAP = 72
    private val KEYWORDS = setOf("BUGFIX", "FEATURE", "TASK", "DOCS")
    private val LEADING_TAGS = Regex("""^(\s*\[[^\]]*]\s*)+""")
    private val BULLET_PREFIX = Regex("""^\s*(?:[*\-•]|\d+[.)])\s*""")

    fun keyword(type: String): String =
        type.trim().trim('[', ']').uppercase().takeIf { it in KEYWORDS } ?: "TASK"

    fun subjectLine(result: AiResult): String {
        val breaking = if (result.breaking) "[!!!]" else ""
        return "$breaking[${keyword(result.type)}] ${cleanSubject(result.subject)}"
    }

    fun cleanSubject(subject: String): String {
        val text = subject.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(LEADING_TAGS, "")
            .trim()
            .trimEnd('.')
            .trim()
        return text.replaceFirstChar { it.uppercaseChar() }
    }

    fun bodyLines(bullets: List<String>, max: Int = 5): List<String> =
        bullets.asSequence()
            .map { it.replace(BULLET_PREFIX, "").replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(max)
            .flatMap { wrap(it, "* ", "  ") }
            .toList()

    /** Greedy word wrap with a hanging indent; a single over-long word keeps its own line. */
    fun wrap(text: String, firstPrefix: String, nextPrefix: String, width: Int = WRAP): List<String> {
        val lines = mutableListOf<String>()
        var current = StringBuilder(firstPrefix)
        var empty = true
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            if (!empty && current.length + 1 + word.length > width) {
                lines += current.toString()
                current = StringBuilder(nextPrefix)
                empty = true
            }
            if (!empty) current.append(' ')
            current.append(word)
            empty = false
        }
        if (!empty) lines += current.toString()
        return lines
    }

    fun footerLines(options: FooterOptions): List<String> = buildList {
        options.resolves.distinct().forEach { add("Resolves: #$it") }
        val releases = options.releases.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (releases.isNotEmpty()) add("Releases: ${releases.joinToString(", ")}")
    }

    fun format(result: AiResult, footer: FooterOptions = FooterOptions()): String {
        val parts = mutableListOf(subjectLine(result))
        bodyLines(result.bullets).takeIf { it.isNotEmpty() }?.let { parts += it.joinToString("\n") }
        footerLines(footer).takeIf { it.isNotEmpty() }?.let { parts += it.joinToString("\n") }
        return parts.joinToString("\n\n")
    }

    /** Issue numbers from the branch name; group 1 of [pattern] if present, else the whole match. */
    fun issuesFromBranch(branch: String?, pattern: String): List<String> {
        if (branch.isNullOrBlank() || pattern.isBlank()) return emptyList()
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return emptyList()
        return regex.findAll(branch)
            .map { m -> (m.groups[1]?.value ?: m.value).trim().trimStart('#') }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }
}
