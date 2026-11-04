package io.github.sivaprasad1988.typo3commitai

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import git4idea.repo.GitRepositoryManager

/** The files checked in the commit dialog. */
data class CommitSelection(val changes: List<Change>, val unversioned: List<FilePath>) {
    val isEmpty: Boolean get() = changes.isEmpty() && unversioned.isEmpty()

    companion object {
        fun from(e: AnActionEvent, project: Project): CommitSelection {
            val handler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>
            return CommitSelection(
                handler?.ui?.getIncludedChanges()
                    ?: ChangeListManager.getInstance(project).defaultChangeList.changes.toList(),
                handler?.ui?.getIncludedUnversionedFiles().orEmpty(),
            )
        }
    }
}

/** A problem the user can fix (shown as a warning, not as an error). */
class UserFacingException(message: String) : Exception(message)

data class Generation(val result: AiResult, val warnings: List<String>)

/** Diff -> AI provider -> [AiResult]. Shared by the one-click action and the template dialog. */
class CommitMessageGenerator(private val project: Project) {
    fun generate(selection: CommitSelection, indicator: ProgressIndicator): Generation {
        if (selection.isEmpty) throw UserFacingException("No changes selected for commit.")
        val s = Settings.get()
        val cfg = s.state

        indicator.text = "Collecting diff"
        val files = DiffCollector(project).collect(selection.changes, selection.unversioned)
        val budget = DiffBudget.fit(files, cfg.maxDiffChars)
        if (budget.text.isBlank()) throw UserFacingException("The selected changes produced an empty diff.")
        indicator.checkCanceled()

        indicator.text = "Asking ${s.activeModel}"
        val result = s.createClient()
            .generate(s.effectiveSystemPrompt, Prompts.userMessage(budget.text))
            .let { if (DocsDetector.isDocsOnly(files.map(FileDiff::path))) it.copy(type = "DOCS") else it }
        indicator.checkCanceled()

        val warnings = buildList {
            if (!budget.isComplete) {
                val parts = buildList {
                    if (budget.truncated.isNotEmpty()) add("cut: " + budget.truncated.joinToString(", "))
                    if (budget.omitted.isNotEmpty()) add("left out: " + budget.omitted.joinToString(", "))
                }
                add(
                    "Diff larger than ${cfg.maxDiffChars} chars, so the message may be incomplete (${parts.joinToString("; ")}). " +
                        "Raise the limit in Settings | Tools | Core Commit AI or commit in smaller parts.",
                )
            }
        }
        return Generation(result, warnings)
    }

    fun currentBranch(): String? =
        GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentBranchName

    fun branchIssues(): List<String> =
        CommitMessageFormatter.issuesFromBranch(currentBranch(), Settings.get().state.issueRegex)

    /** Footer defaults for a new message: issue from the branch (if enabled) and the configured releases. */
    fun defaultFooter(): FooterOptions {
        val cfg = Settings.get().state
        return FooterOptions(
            resolves = if (cfg.addResolves) branchIssues() else emptyList(),
            releases = cfg.releases,
        )
    }
}

object Notifier {
    fun notify(project: Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Core Commit AI")
            .createNotification("Core Commit AI", text, type)
            .notify(project)
    }
}
