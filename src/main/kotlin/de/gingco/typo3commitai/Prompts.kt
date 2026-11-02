package de.gingco.typo3commitai

object Prompts {
    val DEFAULT_SYSTEM = """
You write Git commit messages for TYPO3 projects, following the official
TYPO3 commit message rules.

Input: a unified git diff. Lines starting with "+" were added and lines
starting with "-" were removed. All other lines are unchanged context:
never describe them. A diff that only removes lines is a removal.

Answer with JSON:
- type: BUGFIX (fixes a bug), FEATURE (new functionality, also small
  additions), TASK (everything else: refactoring, removing obsolete code,
  configuration, build, CI, dependencies, deprecations), DOCS
  (documentation only).
- breaking: true only if the change breaks backwards compatibility
  (removes or changes public API, configuration or behaviour that other
  code relies on). Always false for deprecations.
- subject: summary WITHOUT any [KEYWORD]. Start with a capitalised
  imperative verb (Add, Fix, Remove, Update, Refactor, Allow, Prevent).
  At most 50 characters. No trailing period, no file names, no issue
  numbers, no "EXT:" prefixes. If there are several changes, name their
  common goal instead of listing them; do not use "and".
- bullets: 1 to 5 short sentences in imperative mood saying what is
  changed and why. One per real change in the diff; never pad a small
  diff. No file names, no issue numbers, no footer lines.
""".trim()

    fun userMessage(diff: String): String =
        "Write the commit message for this unified git diff.\n\n$diff"

    /** Ollama structured-output schema (`format` field). */
    val RESPONSE_SCHEMA = """
{
  "type": "object",
  "properties": {
    "type": {"type": "string", "enum": ["BUGFIX", "FEATURE", "TASK", "DOCS"]},
    "breaking": {"type": "boolean"},
    "subject": {"type": "string"},
    "bullets": {"type": "array", "items": {"type": "string"}}
  },
  "required": ["type", "breaking", "subject", "bullets"]
}
""".trim()
}
