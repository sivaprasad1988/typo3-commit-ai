package io.github.sivaprasad1988.typo3commitai

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.ui.CommitMessage

/** One click: AI writes the message straight into the commit message field. */
class GenerateCommitMessageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messageControl: CommitMessageI = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val selection = CommitSelection.from(e, project)
        if (selection.isEmpty) {
            Notifier.notify(project, "No changes selected for commit.", NotificationType.WARNING)
            return
        }
        // Read on the EDT now; the field may change while the model runs.
        val existing = currentMessage(e)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating TYPO3 commit message", true) {
            override fun run(indicator: ProgressIndicator) {
                val generator = CommitMessageGenerator(project)
                val generation = generator.generate(selection, indicator)
                val data = keepFooter(CommitMessageFormatter.fromAi(generation.result, generator.defaultFooter()), existing)
                val message = CommitMessageFormatter.format(data)

                ApplicationManager.getApplication().invokeLater {
                    messageControl.setCommitMessage(message)
                    (messageControl as? CommitMessage)?.editorField?.requestFocusInWindow()
                }

                generation.warnings.forEach { Notifier.notify(project, it, NotificationType.WARNING) }
                val subject = message.lineSequence().first()
                if (subject.length > CommitMessageFormatter.WRAP) {
                    Notifier.notify(project, "Subject has ${subject.length} characters (TYPO3 maximum: 72). Please shorten it.", NotificationType.WARNING)
                }
            }

            override fun onThrowable(error: Throwable) {
                val type = if (error is UserFacingException) NotificationType.WARNING else NotificationType.ERROR
                Notifier.notify(project, error.message ?: error.toString(), type)
            }
        })
    }

    companion object {
        fun currentMessage(e: AnActionEvent): String =
            (e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) as? CommitMessage)?.comment.orEmpty()

        /**
         * Regenerating must not lose what is already in the footer: an existing
         * Change-Id (Gerrit amend), Related/Depends lines, extra flags such as
         * [SECURITY], or issue numbers typed by hand.
         */
        fun keepFooter(generated: CommitData, existingMessage: String): CommitData {
            if (existingMessage.isBlank()) return generated
            val old = CommitMessageParser.parse(existingMessage)
            return generated.copy(
                resolves = old.resolves.ifEmpty { generated.resolves },
                related = old.related,
                releases = old.releases.ifBlank { generated.releases },
                depends = old.depends,
                extraFlags = old.extraFlags,
                otherTrailers = old.otherTrailers,
            )
        }
    }
}
