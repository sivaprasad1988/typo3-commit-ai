package de.gingco.typo3commitai

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import git4idea.repo.GitRepositoryManager
import java.time.Duration

class GenerateCommitMessageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messageControl: CommitMessageI = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val handler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>
        val changes: List<Change> = handler?.ui?.getIncludedChanges()
            ?: ChangeListManager.getInstance(project).defaultChangeList.changes.toList()
        val unversioned: List<FilePath> = handler?.ui?.getIncludedUnversionedFiles().orEmpty()

        if (changes.isEmpty() && unversioned.isEmpty()) {
            notify(project, "No changes selected for commit.", NotificationType.WARNING)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating TYPO3 commit message", true) {
            override fun run(indicator: ProgressIndicator) {
                val s = Settings.get()
                val cfg = s.state

                indicator.text = "Collecting diff"
                val budget = DiffBudget.fit(DiffCollector(project).collect(changes, unversioned), cfg.maxDiffChars)
                if (budget.text.isBlank()) {
                    notify(project, "The selected changes produced an empty diff.", NotificationType.WARNING)
                    return
                }
                indicator.checkCanceled()

                indicator.text = "Asking ${cfg.model}"
                val result = OllamaClient(
                    cfg.ollamaUrl,
                    cfg.model,
                    cfg.temperaturePercent / 100.0,
                    Duration.ofSeconds(cfg.timeoutSeconds.toLong()),
                ).generate(s.effectiveSystemPrompt, Prompts.userMessage(budget.text))
                indicator.checkCanceled()

                val branch = GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentBranchName
                val footer = FooterOptions(
                    resolves = if (cfg.addResolves) CommitMessageFormatter.issuesFromBranch(branch, cfg.issueRegex) else emptyList(),
                    releases = cfg.releases,
                )
                val message = CommitMessageFormatter.format(result, footer)

                ApplicationManager.getApplication().invokeLater {
                    messageControl.setCommitMessage(message)
                    (messageControl as? CommitMessage)?.editorField?.requestFocusInWindow()
                }

                if (!budget.isComplete) {
                    val parts = buildList {
                        if (budget.truncated.isNotEmpty()) add("cut: " + budget.truncated.joinToString(", "))
                        if (budget.omitted.isNotEmpty()) add("left out: " + budget.omitted.joinToString(", "))
                    }
                    notify(
                        project,
                        "Diff larger than ${cfg.maxDiffChars} chars, so the message may be incomplete (${parts.joinToString("; ")}). " +
                            "Raise the limit in Settings | Tools | TYPO3 Commit AI or commit in smaller parts.",
                        NotificationType.WARNING,
                    )
                }
                val subject = message.lineSequence().first()
                if (subject.length > CommitMessageFormatter.WRAP) {
                    notify(project, "Subject has ${subject.length} characters (TYPO3 maximum: 72). Please shorten it.", NotificationType.WARNING)
                }
            }

            override fun onThrowable(error: Throwable) {
                notify(project, error.message ?: error.toString(), NotificationType.ERROR)
            }
        })
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("TYPO3 Commit AI")
            .createNotification("TYPO3 Commit AI", text, type)
            .notify(project)
    }
}
