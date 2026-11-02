# Core Commit AI

**TYPO3 commit messages for PhpStorm**

PhpStorm plugin that writes TYPO3-style commit messages with a **local Ollama
model**. Nothing leaves your machine except the request to your own Ollama URL.

> TYPO3 is a registered trademark of the TYPO3 Association. Core Commit AI is
> an independent project and is not affiliated with or endorsed by the TYPO3
> Association.

```
[TASK] Remove unused robotsTxt configuration

* Remove unused robotsTxt configuration from TypoScript.
* Remove corresponding route from site configuration.

Resolves: #12345
```

## Why another plugin?

Existing plugins send the full *before* and *after* content of every file and
cut it off at a fixed size, so changes further down a file never reach the
model and it describes unrelated code. This plugin:

- sends a **real unified diff** (`git diff -M HEAD`) of exactly the files
  checked in the commit dialog; new and unversioned files are added as `+` lines
- **never truncates silently**: if the diff exceeds the limit, you get a
  warning listing the cut and omitted files
- asks the model for **structured JSON** (`type`, `breaking`, `subject`,
  `bullets`) and builds the message in code, so the rules of the
  [TYPO3 commit message guide](https://docs.typo3.org/m/typo3/guide-contributionworkflow/main/en-us/Appendix/CommitMessage.html)
  are applied mechanically:
  - exactly one `[BUGFIX]`, `[FEATURE]`, `[TASK]` or `[DOCS]` keyword, with `[!!!]` in front for breaking changes
  - `*` bullets wrapped at 72 characters with a hanging indent
  - optional `Resolves: #N` (from the branch name) and `Releases:` footer lines
  - no `Change-Id` (left to the Gerrit hook) and no `Signed-off-by`
- warns when the subject is longer than 72 characters

## Requirements

- PhpStorm 2026.1 or newer (other IntelliJ-based IDEs with Git should work too)
- [Ollama](https://ollama.com/download) running locally
- A model, by default `qwen2.5-coder:14b` (about 9 GB of disk; about 16 GB of RAM recommended):

  ```sh
  ollama pull qwen2.5-coder:14b
  ```

  With less RAM, use `qwen2.5-coder:7b` and set it in the plugin settings.

## Install

1. Download `typo3-commit-ai-<version>.zip` from
   [Releases](https://github.com/sivaprasad1988/typo3-commit-ai/releases).
2. PhpStorm → **Settings → Plugins → ⚙ → Install Plugin from Disk…** → choose the zip → restart.
3. If another commit-message plugin is installed, disable it to avoid two buttons.

## Use

1. Open the commit dialog, check the files to commit.
2. Click the **Generate TYPO3 Commit Message** button (orange T icon) in the message toolbar.
3. Review and edit the message. Change the keyword if your team uses a different convention.

## Settings

**Settings → Tools → Core Commit AI**

| Setting | Default | Note |
|---|---|---|
| URL | `http://localhost:11434` | Ollama server |
| Model | `qwen2.5-coder:14b` | any pulled Ollama model |
| Temperature (%) | `20` | lower = more consistent |
| Timeout (s) | `180` | the first call loads the model and is slower |
| Max diff size (chars) | `40000` | larger diffs are cut, with a warning |
| Add `Resolves: #N` | on | only when the branch name contains an issue number |
| Issue regex | `(?:^\|[/_-])(\d{3,})(?=[/_-]\|$)` | matches `feature/12345-text`; group 1 = number |
| Releases | empty | e.g. `main, 13.4`; empty = no `Releases:` line |
| System prompt | built-in | keep the JSON field names; clear the field to restore the default |

## Prompt for other tools

[`prompts/typo3-commit-message.txt`](prompts/typo3-commit-message.txt) holds a
standalone version of the prompt (subject and body rules) that returns a
plain-text commit message. Use it with any AI tool or commit-message plugin.

## Build from source

Requires JDK 21.

```sh
./gradlew test buildPlugin          # zip in build/distributions/
OLLAMA_IT=1 ./gradlew test          # also runs the test against a real local Ollama
```

The build compiles against a local PhpStorm installation (`platformPath` in
`gradle.properties`, default `/Applications/PhpStorm.app`).
