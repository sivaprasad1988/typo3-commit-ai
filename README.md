# Core Commit AI

**TYPO3 commit messages for PhpStorm**

PhpStorm plugin that writes TYPO3-style commit messages with a **local Ollama
model**. Nothing leaves your machine except the request to your own Ollama URL.

> Built with AI assistance (Claude by Anthropic). Designed and maintained by
> [sivaprasad1988](https://github.com/sivaprasad1988).

> TYPO3 is a registered trademark of the TYPO3 Association. Core Commit AI is
> an independent project and is not affiliated with or endorsed by the TYPO3
> Association.

```
[TASK] Remove unused robotsTxt configuration

* Remove unused robotsTxt configuration from TypoScript.
* Remove corresponding route from site configuration.

Resolves: #12345
```

**Contents:**
[Why another plugin?](#why-another-plugin) ·
[How it works](#how-it-works) ·
[Message format](#the-typo3-message-format) ·
[Requirements](#requirements) ·
[Install](#install) ·
[Use](#use) ·
[Template dialog](#template-dialog) ·
[Shortcuts](#keyboard-shortcuts) ·
[Settings](#settings) ·
[Troubleshooting](#troubleshooting) ·
[FAQ](#faq) ·
[Build from source](#build-from-source)

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
  - no `Change-Id` (left to the Gerrit hook) and no `Signed-off-by`; existing ones are kept
- warns when the subject is longer than 72 characters
- offers a **template dialog** to write or fix a message field by field, with or without AI

## How it works

![Two paths: one click from diff to commit field, or the template dialog with parser, optional AI fill and Apply](docs/images/workflow.svg)

- **One click** (orange T): diff → local Ollama → formatter → commit field. Nothing to fill in.
- **Template dialog** (pencil): the current message is parsed into fields, you edit
  (optionally let AI fill type, subject and description), **Apply** formats it back.
  Open it again at any time to fix the message.

In both paths the model only delivers content (type, breaking flag, subject,
bullets). Brackets, wrapping and footer syntax always come from code.

## The TYPO3 message format

![Annotated commit message: subject line with flag and keyword, wrapped bullet body, footer lines, preserved Change-Id](docs/images/message-anatomy.svg)

| Part | Rule applied by the plugin |
|---|---|
| Flag | `[!!!]` first, only for breaking changes. Other flags already present (e.g. `[SECURITY]`) are kept. |
| Keyword | exactly one of `[BUGFIX]`, `[FEATURE]`, `[TASK]`, `[DOCS]`; unknown types become `[TASK]`; always `[DOCS]` when only documentation changed (README, CHANGELOG, `*.md`, `*.rst`, `Documentation/`, `docs/`) |
| Subject | capitalised, no trailing period; counter warns above 52, Apply is blocked above 72 characters |
| Body | `*` bullets, wrapped at 72 with a two-space hanging indent; paragraphs wrapped at 72; indented lines (code) kept as they are |
| `Resolves:` / `Related:` | one line per issue, `#` added automatically |
| `Releases:` | one comma-separated line, e.g. `Releases: main, 13.4` |
| `Depends:` | one line per Change-Id (documentation patches) |
| `Change-Id:`, `Signed-off-by:`, `Reverts:` … | never created, always kept in their original order |

Rules from the
[TYPO3 commit message guide](https://docs.typo3.org/m/typo3/guide-contributionworkflow/main/en-us/Appendix/CommitMessage.html).

## Requirements

- PhpStorm 2026.1 or newer (other IntelliJ-based IDEs with Git should work too)
- [Ollama](https://ollama.com/download) running locally
- One of these models (pick it under **Model** in the plugin settings):

  | Model | Disk | RAM | Use when |
  |---|---|---|---|
  | `qwen2.5-coder:14b` (default) | about 9 GB | about 16 GB | you want the best messages |
  | `qwen2.5-coder:7b` | about 4.7 GB | about 8 GB | you want answers about twice as fast, or have less RAM |

  ```sh
  ollama pull qwen2.5-coder:14b   # or: ollama pull qwen2.5-coder:7b
  ```

## Install

1. Download `typo3-commit-ai-<version>.zip` from
   [Releases](https://github.com/sivaprasad1988/typo3-commit-ai/releases).
2. PhpStorm → **Settings → Plugins → ⚙ → Install Plugin from Disk…** → choose the zip → restart.
3. If another commit-message plugin is installed, disable it to avoid two buttons.

## Use

1. Open the commit dialog, check the files to commit.
2. Click the **Generate TYPO3 Commit Message** button (orange T icon) in the message toolbar.
3. Review and edit the message. Change the keyword if your team uses a different convention.

Regenerating keeps what is already in the footer: `Change-Id` (Gerrit amend),
`Related:`, `Depends:`, `Signed-off-by:`, hand-typed `Resolves:` numbers and
extra flags such as `[SECURITY]`.

### Template dialog

Click **Edit TYPO3 Commit Message…** (pencil icon) or press
<kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>K</kbd>
(macOS: <kbd>⌘</kbd>+<kbd>⌥</kbd>+<kbd>⇧</kbd>+<kbd>K</kbd>) in the commit dialog.

![Schematic of the template dialog: type, breaking flag, subject with counter, description, Resolves and Related with From Branch buttons, Releases, Depends, preview, Generate with AI, Cancel and Apply](docs/images/dialog-layout.svg)

<sub>Schematic drawing; the real dialog uses your IDE theme.</sub>

| Field | What it does |
|---|---|
| Type | `BUGFIX`, `FEATURE`, `TASK` or `DOCS` |
| Breaking change | adds `[!!!]` in front of the keyword |
| Subject | without keyword; counter shows the length of the whole first line |
| Description | one change per line starting with `*`; Enter continues the list, Enter on an empty `* ` ends it |
| Resolves / Related | issue numbers, comma separated; **From Branch** adds the number from the branch name |
| Releases | e.g. `main, 13.4`; prefilled from the settings for new messages |
| Depends | Change-Id of the core patch, documentation patches only |
| Preview | the exact text that **Apply** writes into the commit field |

- **Generate with AI** fills type, subject and description from the diff. Related,
  Releases and Depends stay as you entered them; an empty Resolves field gets the
  number from the branch name (if enabled in the settings).
- With a message already in the field, the dialog opens with it parsed into the
  fields: wrapped bullets become one line again, so you can edit and apply again.
- Validation: an empty subject or a first line over 72 characters blocks **Apply**;
  `Related` without `Resolves` shows a warning (TYPO3 rule) but still allows **Apply**.

## Keyboard shortcuts

| Action | Windows / Linux | macOS |
|---|---|---|
| Edit TYPO3 Commit Message… (dialog) | <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>K</kbd> | <kbd>⌘</kbd>+<kbd>⌥</kbd>+<kbd>⇧</kbd>+<kbd>K</kbd> |
| Generate TYPO3 Commit Message (one click) | none by default | none by default |

The actions are available in the commit dialog. Change or add shortcuts in
**Settings → Keymap** (search for "TYPO3").

## Settings

**Settings → Tools → Core Commit AI**

| Setting | Default | Note |
|---|---|---|
| URL | `http://localhost:11434` | Ollama server |
| Model | `qwen2.5-coder:14b` | pick `qwen2.5-coder:14b` or `qwen2.5-coder:7b`, or type any pulled Ollama model |
| Temperature (%) | `20` | lower = more consistent |
| Timeout (s) | `180` | the first call loads the model and is slower |
| Max diff size (chars) | `40000` | larger diffs are cut, with a warning |
| Add `Resolves: #N` | on | only when the branch name contains an issue number |
| Issue regex | `(?:^\|[/_-])(\d{3,})(?=[/_-]\|$)` | matches `feature/12345-text`; group 1 = number |
| Releases | empty | e.g. `main, 13.4`; empty = no `Releases:` line |
| System prompt | built-in | keep the JSON field names; clear the field to restore the default |

## Troubleshooting

| Message | Cause and fix |
|---|---|
| `Cannot reach Ollama at http://localhost:11434. Is Ollama running?` | Start Ollama (`ollama serve` or the Ollama app). Check the URL in the settings. |
| `Ollama error 404: … not found. Run: ollama pull <model>` | The model is not pulled. Run the command shown, or set a pulled model (`ollama list`). |
| `Ollama did not answer within 180 s.` | The first call loads the model into memory. Try again, raise the timeout, or use a smaller model such as `qwen2.5-coder:7b`. |
| `Diff larger than 40000 chars, so the message may be incomplete (…)` | The listed files were cut or left out. Raise **Max diff size** or commit in smaller parts. |
| `No changes selected for commit.` | Check at least one file in the commit dialog. |
| `Model did not return JSON` / `Model returned an empty subject.` | Use one of the suggested coding models (`qwen2.5-coder:14b` or `qwen2.5-coder:7b`). If it happens often with `7b`, switch to `14b`. If you changed the system prompt, keep the JSON field names or clear it to restore the default. |
| No button in the commit dialog | The buttons sit in the toolbar of the commit message field. Check that the plugin is enabled in **Settings → Plugins** and restart the IDE after installing. |
| `No issue number found in branch "…"` | The branch name does not match the **Issue regex** (default: a number with at least 3 digits between `/`, `-` or `_`, e.g. `feature/12345-text`). |

## FAQ

**Should I use `qwen2.5-coder:7b` or `qwen2.5-coder:14b`?**
Start with `14b` for the best messages. Switch to `7b` if generating feels slow
or your machine has less than 16 GB of RAM. Both get the same prompt and the
same JSON format, and you can switch any time under **Settings → Tools → Core
Commit AI → Model**. Measured on an Apple M1 Pro with 32 GB of RAM:

| Model | Writing speed | Reading speed | Load time | Memory while loaded |
|---|---|---|---|---|
| `qwen2.5-coder:14b` | about 12 tokens/s | about 68 tokens/s | about 6.5 s | about 10 GB |
| `qwen2.5-coder:7b` | about 25 tokens/s | about 134 tokens/s | about 1.8 s | about 6.6 GB |

Large diffs are slowest to read: at 40000 chars (about 13k tokens), `14b`
needs about 3 minutes just to read the diff, `7b` about 1.5 minutes. Lower
**Max diff size** or commit in smaller parts to speed this up.

**Does my code leave the machine?**
Only as the request to the Ollama URL you configure. With the default
`http://localhost:11434`, everything stays local.

**Can I use it without AI?**
Yes. The template dialog works without Ollama; only **Generate with AI** needs it.

**Will regenerating break my Gerrit change?**
No. An existing `Change-Id:` line is kept, so amending updates the same change.

**Why no `[WIP]` flag?**
It is not part of the TYPO3 commit message guide. Flags already in the message
are kept, though.

**Our team uses other keywords.**
Change the keyword after generating, or edit the system prompt. The formatter
only accepts `BUGFIX`, `FEATURE`, `TASK` and `DOCS` and falls back to `TASK`.

## Prompt for other tools

[`prompts/typo3-commit-message.txt`](prompts/typo3-commit-message.txt) holds a
standalone version of the prompt (subject and body rules) that returns a
plain-text commit message. Use it with any AI tool or commit-message plugin.

## Build from source

Requires JDK 21.

```sh
./gradlew test buildPlugin          # zip in build/distributions/
OLLAMA_IT=1 ./gradlew test          # also runs the test against a real local Ollama (default model)
OLLAMA_IT=1 OLLAMA_MODEL=qwen2.5-coder:7b ./gradlew test --rerun-tasks   # same, against 7b
```

The build compiles against a local PhpStorm installation (`platformPath` in
`gradle.properties`, default `/Applications/PhpStorm.app`).
