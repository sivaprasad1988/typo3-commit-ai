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
 * Everything a TYPO3 commit message consists of, in editable form.
 *
 * [description] is free text: lines starting with `*` or `-` are bullets, other
 * lines are paragraphs, indented lines (not continuing a bullet) are kept as
 * they are. [extraFlags] (e.g. `SECURITY`) and [otherTrailers] (e.g.
 * `Change-Id:`, `Signed-off-by:`, `Reverts:`) are not edited by the plugin,
 * only carried over unchanged.
 */
data class CommitData(
    val type: String = "TASK",
    val breaking: Boolean = false,
    val subject: String = "",
    val description: String = "",
    val resolves: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val releases: String = "",
    val depends: List<String> = emptyList(),
    val extraFlags: List<String> = emptyList(),
    val otherTrailers: List<String> = emptyList(),
)

/**
 * Turns structured data into a TYPO3 commit message. Everything the TYPO3
 * rules make mechanical (keyword prefix, [!!!], bullet format, 72-column
 * wrapping, footer syntax) is done here, not left to the model or the user.
 */
object CommitMessageFormatter {
    const val WRAP = 72
    /** The guide asks for "below 52 characters if possible". */
    const val SUBJECT_SOFT_LIMIT = 52
    val KEYWORDS = listOf("BUGFIX", "FEATURE", "TASK", "DOCS")
    private val LEADING_TAGS = Regex("""^(\s*\[[^\]]*]\s*)+""")
    private val BULLET_PREFIX = Regex("""^\s*(?:[*\-•]|\d+[.)])\s*""")

    fun keyword(type: String): String =
        type.trim().trim('[', ']').uppercase().takeIf { it in KEYWORDS } ?: "TASK"

    fun subjectLine(result: AiResult): String = subjectLine(fromAi(result))

    fun subjectLine(data: CommitData): String {
        val flags = buildString {
            if (data.breaking) append("[!!!]")
            data.extraFlags.forEach { append("[$it]") }
        }
        return "$flags[${keyword(data.type)}] ${cleanSubject(data.subject)}"
    }

    fun cleanSubject(subject: String): String {
        val text = subject.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(LEADING_TAGS, "")
            .trim()
            .trimEnd('.')
            .trim()
        return text.replaceFirstChar { it.uppercaseChar() }
    }

    /** Model bullets without list markers, duplicates and blanks, capped at [max]. */
    fun cleanBullets(bullets: List<String>, max: Int = 5): List<String> =
        bullets.asSequence()
            .map { it.replace(BULLET_PREFIX, "").replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(max)
            .toList()

    fun bodyLines(bullets: List<String>, max: Int = 5): List<String> =
        cleanBullets(bullets, max).flatMap { wrap(it, "* ", "  ") }

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

    fun footerLines(options: FooterOptions): List<String> =
        footerLines(CommitData(resolves = options.resolves, releases = options.releases))

    fun footerLines(data: CommitData): List<String> = buildList {
        issues(data.resolves).forEach { add("Resolves: #$it") }
        issues(data.related).forEach { add("Related: #$it") }
        val releases = data.releases.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (releases.isNotEmpty()) add("Releases: ${releases.joinToString(", ")}")
        data.depends.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { add("Depends: $it") }
        addAll(data.otherTrailers)
    }

    fun format(result: AiResult, footer: FooterOptions = FooterOptions()): String = format(fromAi(result, footer))

    fun format(data: CommitData): String {
        val parts = mutableListOf(subjectLine(data))
        Description.render(data.description).takeIf { it.isNotEmpty() }?.let { parts += it.joinToString("\n") }
        footerLines(data).takeIf { it.isNotEmpty() }?.let { parts += it.joinToString("\n") }
        return parts.joinToString("\n\n")
    }

    fun fromAi(result: AiResult, footer: FooterOptions = FooterOptions()): CommitData = CommitData(
        type = keyword(result.type),
        breaking = result.breaking,
        subject = cleanSubject(result.subject),
        description = cleanBullets(result.bullets).joinToString("\n") { "* $it" },
        resolves = footer.resolves,
        releases = footer.releases,
    )

    /** "12345, #678 999" -> [12345, 678, 999] */
    fun issues(values: List<String>): List<String> =
        values.flatMap { it.split(Regex("""[,\s]+""")) }
            .map { it.trim().trimStart('#') }
            .filter { it.isNotEmpty() }
            .distinct()

    fun issues(text: String): List<String> = issues(listOf(text))

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

/** Body text <-> items: bullets, paragraphs, verbatim (indented) lines and blank-line gaps. */
object Description {
    sealed interface Item {
        data class Bullet(val text: String) : Item
        data class Paragraph(val text: String) : Item
        data class Verbatim(val line: String) : Item
        data object Gap : Item
    }

    private val BULLET = Regex("""^\s*[*\-•]\s+""")

    fun tokenize(text: String): List<Item> {
        val out = mutableListOf<Item>()
        var open: Item? = null
        for (raw in text.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            val current = open
            when {
                line.isBlank() -> {
                    current?.let { out += it }
                    open = null
                    if (out.isNotEmpty() && out.last() != Item.Gap) out += Item.Gap
                }
                BULLET.containsMatchIn(line) -> {
                    current?.let { out += it }
                    open = Item.Bullet(line.replace(BULLET, "").trim())
                }
                line[0].isWhitespace() && current is Item.Bullet ->
                    open = Item.Bullet(current.text + " " + line.trim())
                line[0].isWhitespace() -> {
                    current?.let { out += it }
                    open = null
                    out += Item.Verbatim(line)
                }
                current is Item.Paragraph ->
                    open = Item.Paragraph(current.text + " " + line.trim())
                else -> {
                    current?.let { out += it }
                    open = Item.Paragraph(line.trim())
                }
            }
        }
        open?.let { out += it }
        while (out.lastOrNull() == Item.Gap) out.removeAt(out.lastIndex)
        return out
    }

    /** Final message lines, wrapped at [CommitMessageFormatter.WRAP]. */
    fun render(text: String): List<String> = tokenize(text).flatMap {
        when (it) {
            is Item.Bullet -> CommitMessageFormatter.wrap(it.text, "* ", "  ")
            is Item.Paragraph -> CommitMessageFormatter.wrap(it.text, "", "")
            is Item.Verbatim -> listOf(it.line)
            Item.Gap -> listOf("")
        }
    }

    /** Unwrapped form for editing: one line per bullet or paragraph. */
    fun editable(text: String): String = tokenize(text).joinToString("\n") {
        when (it) {
            is Item.Bullet -> "* ${it.text}"
            is Item.Paragraph -> it.text
            is Item.Verbatim -> it.line
            Item.Gap -> ""
        }
    }
}

/** Reads an existing commit message back into [CommitData], so it can be edited in the dialog. */
object CommitMessageParser {
    private val TAG = Regex("""^\s*\[([^\]]*)]""")
    private val TRAILER = Regex("""^([A-Za-z][A-Za-z0-9-]*):\s(.*)$""")

    fun parse(message: String): CommitData {
        val lines = message.replace("\r\n", "\n").lines()
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
        if (lines.isEmpty()) return CommitData()

        var subject = lines[0]
        var type: String? = null
        var breaking = false
        val extraFlags = mutableListOf<String>()
        while (true) {
            val m = TAG.find(subject) ?: break
            val tag = m.groupValues[1].trim()
            when {
                tag == "!!!" -> breaking = true
                type == null && tag.uppercase() in CommitMessageFormatter.KEYWORDS -> type = tag.uppercase()
                tag.isNotEmpty() -> extraFlags += tag
            }
            subject = subject.substring(m.range.last + 1)
        }

        val rest = lines.drop(1)
        val lastBlank = rest.indexOfLast { it.isBlank() }
        val tail = rest.drop(lastBlank + 1)
        val hasTrailers = tail.isNotEmpty() && tail.all { TRAILER.matches(it.trim()) }
        val body = if (hasTrailers) rest.take(maxOf(lastBlank, 0)) else rest

        val resolves = mutableListOf<String>()
        val related = mutableListOf<String>()
        val releases = mutableListOf<String>()
        val depends = mutableListOf<String>()
        val others = mutableListOf<String>()
        if (hasTrailers) {
            for (line in tail) {
                val (key, value) = TRAILER.matchEntire(line.trim())!!.destructured
                when (key.lowercase()) {
                    "resolves" -> resolves += CommitMessageFormatter.issues(value)
                    "related" -> related += CommitMessageFormatter.issues(value)
                    "releases" -> releases += value.trim()
                    "depends" -> depends += value.trim()
                    else -> others += line.trimEnd()
                }
            }
        }

        return CommitData(
            type = type ?: "TASK",
            breaking = breaking,
            subject = subject.trim(),
            description = Description.editable(body.joinToString("\n")),
            resolves = resolves.distinct(),
            related = related.distinct(),
            releases = releases.joinToString(", "),
            depends = depends,
            extraFlags = extraFlags,
            otherTrailers = others,
        )
    }
}
