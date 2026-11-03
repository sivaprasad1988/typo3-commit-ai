package io.github.sivaprasad1988.typo3commitai

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * Form for writing or editing a TYPO3 commit message field by field. "Generate
 * with AI" fills type, subject and description from the diff; nothing reaches
 * the commit field until the user presses Apply.
 */
class CommitTemplateDialog(
    private val project: Project,
    initial: CommitData,
    private val selection: CommitSelection,
) : DialogWrapper(project, true) {
    private val generator = CommitMessageGenerator(project)

    private val type = ComboBox(CommitMessageFormatter.KEYWORDS.toTypedArray())
    private val breaking = JBCheckBox("Breaking change [!!!]")
    private val subject = JBTextField()
    private val counter = JBLabel()
    private val description = JBTextArea(8, 72)
    private val resolves = JBTextField()
    private val related = JBTextField()
    private val releases = JBTextField()
    private val depends = JBTextField()
    private val preview = JBTextArea(10, 72)

    // Carried over unchanged; not editable here.
    private val extraFlags = initial.extraFlags
    private val otherTrailers = initial.otherTrailers

    init {
        title = "TYPO3 Commit Message"
        setOKButtonText("Apply")
        fill(initial)

        val mono = EditorFontType.getGlobalPlainFont()
        description.font = mono
        preview.font = mono
        preview.isEditable = false
        description.lineWrap = false
        installBulletEnter(description)

        val onChange = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updatePreview()
        }
        listOf<JTextComponent>(subject, description, resolves, related, releases, depends)
            .forEach { it.document.addDocumentListener(onChange) }
        type.addActionListener { updatePreview() }
        breaking.addActionListener { updatePreview() }

        init()
        initValidation()
        updatePreview()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Type:") {
            cell(type)
            cell(breaking)
        }
        row("Subject:") {
            cell(subject).align(AlignX.FILL).resizableColumn()
                .comment("Imperative, e.g. <i>Fix</i>, <i>Add</i>, <i>Remove</i>. Whole line: below 52 characters if possible, 72 at most.")
            cell(counter)
        }
        row("Description:") {
            scrollCell(description).align(Align.FILL)
                .comment("One change per line starting with <code>*</code> (Enter continues the list). Wrapped at 72 characters on Apply.")
        }.resizableRow()
        row("Resolves:") {
            cell(resolves).align(AlignX.FILL).resizableColumn().comment("Issue numbers, comma separated. One line per issue is written.")
            button("From Branch") { insertBranchIssues(resolves) }.applyToComponent { icon = AllIcons.Vcs.Branch }
        }
        row("Related:") {
            cell(related).align(AlignX.FILL).resizableColumn()
            button("From Branch") { insertBranchIssues(related) }.applyToComponent { icon = AllIcons.Vcs.Branch }
        }
        row("Releases:") {
            cell(releases).align(AlignX.FILL).comment("e.g. <code>main, 13.4</code>")
        }
        row("Depends:") {
            cell(depends).align(AlignX.FILL).comment("Change-Id of the core patch, documentation patches only")
        }
        if (extraFlags.isNotEmpty() || otherTrailers.isNotEmpty()) {
            row {
                comment(
                    "Kept unchanged: " +
                        (extraFlags.map { "[$it]" } + otherTrailers.map { it.substringBefore(':') + ":" })
                            .distinct().joinToString(", "),
                )
            }
        }
        group("Preview") {
            row { scrollCell(preview).align(Align.FILL) }.resizableRow()
        }.resizableRow()
    }

    override fun createLeftSideActions(): Array<Action> = arrayOf(
        object : DialogWrapperAction("Generate with AI") {
            override fun doAction(e: ActionEvent) = generateWithAi()
        },
    )

    override fun getPreferredFocusedComponent(): JComponent = subject

    override fun getDimensionServiceKey() = "Typo3CommitAi.TemplateDialog"

    override fun doValidate(): ValidationInfo? {
        if (subject.text.isBlank()) return ValidationInfo("Enter a subject.", subject)
        val length = CommitMessageFormatter.subjectLine(current()).length
        if (length > CommitMessageFormatter.WRAP) {
            return ValidationInfo("Subject line has $length characters; TYPO3 allows at most 72.", subject)
        }
        if (CommitMessageFormatter.issues(related.text).isNotEmpty() && CommitMessageFormatter.issues(resolves.text).isEmpty()) {
            return ValidationInfo("TYPO3: Related cannot be used alone, add at least one Resolves issue.", resolves)
                .asWarning().withOKEnabled()
        }
        return null
    }

    /** The data as currently entered. */
    fun current(): CommitData = CommitData(
        type = type.item ?: "TASK",
        breaking = breaking.isSelected,
        subject = subject.text,
        description = description.text,
        resolves = CommitMessageFormatter.issues(resolves.text),
        related = CommitMessageFormatter.issues(related.text),
        releases = releases.text,
        depends = depends.text.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        extraFlags = extraFlags,
        otherTrailers = otherTrailers,
    )

    private fun fill(d: CommitData) {
        type.item = CommitMessageFormatter.keyword(d.type)
        breaking.isSelected = d.breaking
        subject.text = d.subject
        description.text = d.description
        resolves.text = d.resolves.joinToString(", ")
        related.text = d.related.joinToString(", ")
        releases.text = d.releases
        depends.text = d.depends.joinToString(", ")
    }

    private fun updatePreview() {
        val data = current()
        preview.text = CommitMessageFormatter.format(data)
        preview.caretPosition = 0
        val length = CommitMessageFormatter.subjectLine(data).length
        counter.text = "$length/${CommitMessageFormatter.WRAP}"
        counter.foreground = when {
            length > CommitMessageFormatter.WRAP -> JBColor.RED
            length > CommitMessageFormatter.SUBJECT_SOFT_LIMIT -> JBColor.ORANGE
            else -> JBColor.foreground()
        }
    }

    private fun insertBranchIssues(field: JBTextField) {
        val found = generator.branchIssues()
        if (found.isEmpty()) {
            val branch = generator.currentBranch() ?: "(none)"
            Messages.showInfoMessage(contentPanel, "No issue number found in branch \"$branch\".\nThe pattern is set in Settings | Tools | Core Commit AI.", "Core Commit AI")
            return
        }
        field.text = (CommitMessageFormatter.issues(field.text) + found).distinct().joinToString(", ")
    }

    private fun generateWithAi() {
        if (selection.isEmpty) {
            Messages.showWarningDialog(contentPanel, "No changes selected for commit.", "Core Commit AI")
            return
        }
        var generation: Generation? = null
        ProgressManager.getInstance().run(object : Task.Modal(project, "Generating TYPO3 commit message", true) {
            override fun run(indicator: ProgressIndicator) {
                generation = generator.generate(selection, indicator)
            }

            override fun onSuccess() {
                val g = generation ?: return
                val ai = CommitMessageFormatter.fromAi(g.result)
                type.item = ai.type
                breaking.isSelected = ai.breaking
                subject.text = ai.subject
                description.text = ai.description
                if (resolves.text.isBlank() && Settings.get().state.addResolves) {
                    resolves.text = generator.branchIssues().joinToString(", ")
                }
                updatePreview()
                if (g.warnings.isNotEmpty()) {
                    Messages.showWarningDialog(contentPanel, g.warnings.joinToString("\n\n"), "Core Commit AI")
                }
            }

            override fun onThrowable(error: Throwable) {
                val text = error.message ?: error.toString()
                if (error is UserFacingException) Messages.showWarningDialog(contentPanel, text, "Core Commit AI")
                else Messages.showErrorDialog(contentPanel, text, "Core Commit AI")
            }
        })
    }

    /** Enter on a "* " line starts the next bullet; Enter on an empty bullet ends the list. */
    private fun installBulletEnter(area: JBTextArea) {
        val key = "typo3commitai.bulletEnter"
        area.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), key)
        area.actionMap.put(key, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val pos = area.caretPosition
                val text = area.text
                val start = text.lastIndexOf('\n', pos - 1) + 1
                val line = text.substring(start, pos)
                when {
                    line.trim() == "*" -> area.replaceRange("", start, pos)
                    line.startsWith("* ") -> area.replaceSelection("\n* ")
                    else -> area.replaceSelection("\n")
                }
            }
        })
    }
}
