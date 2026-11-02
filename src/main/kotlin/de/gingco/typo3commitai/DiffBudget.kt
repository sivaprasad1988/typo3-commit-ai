package de.gingco.typo3commitai

data class FileDiff(val path: String, val text: String)

data class BudgetedDiff(
    val text: String,
    val truncated: List<String>,
    val omitted: List<String>,
) {
    val isComplete: Boolean get() = truncated.isEmpty() && omitted.isEmpty()
}

/**
 * Fits file diffs into [maxChars]. Never truncates silently: every cut or
 * dropped file is reported so the user can be warned.
 */
object DiffBudget {
    private const val MIN_PARTIAL = 800
    private val FILE_HEADER = Regex("""(?m)^diff --git """)
    private val PATH_IN_HEADER = Regex("""^diff --git a/(.+?) b/(.+)$""")

    /** Splits `git diff` output into one entry per file. */
    fun split(gitDiff: String): List<FileDiff> {
        if (gitDiff.isBlank()) return emptyList()
        val starts = FILE_HEADER.findAll(gitDiff).map { it.range.first }.toList()
        if (starts.isEmpty()) return listOf(FileDiff("(diff)", gitDiff.trimEnd()))
        return starts.mapIndexed { i, start ->
            val end = if (i + 1 < starts.size) starts[i + 1] else gitDiff.length
            val chunk = gitDiff.substring(start, end).trimEnd()
            val header = chunk.lineSequence().first()
            val path = PATH_IN_HEADER.find(header)?.groupValues?.get(2) ?: header
            FileDiff(path, chunk)
        }
    }

    fun fit(files: List<FileDiff>, maxChars: Int): BudgetedDiff {
        val out = StringBuilder()
        val truncated = mutableListOf<String>()
        val omitted = mutableListOf<String>()
        for (file in files) {
            val remaining = maxChars - out.length
            when {
                file.text.length + 1 <= remaining -> out.append(file.text).append('\n')
                remaining >= MIN_PARTIAL -> {
                    out.append(file.text, 0, remaining - 40).append("\n... [diff of this file truncated]\n")
                    truncated += file.path
                }
                else -> omitted += file.path
            }
        }
        if (omitted.isNotEmpty()) {
            out.append("\nAlso changed (diff omitted for size): ")
                .append(omitted.joinToString(", ")).append('\n')
        }
        return BudgetedDiff(out.toString(), truncated, omitted)
    }

    /** Diff for a file git does not know yet (new or unversioned). */
    fun newFile(path: String, content: String?): FileDiff {
        if (content == null) return FileDiff(path, "diff --git a/$path b/$path\nnew file\nBinary file $path added")
        val lines = content.lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        val body = lines.joinToString("\n") { "+$it" }
        return FileDiff(
            path,
            "diff --git a/$path b/$path\nnew file mode 100644\n--- /dev/null\n+++ b/$path\n" +
                "@@ -0,0 +1,${lines.size} @@\n$body",
        )
    }
}
