package io.github.sivaprasad1988.typo3commitai

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.ui.CommitMessage

/**
 * Opens the template dialog. An existing message is parsed back into the form,
 * so clicking again edits the message instead of starting over.
 */
class ShowCommitTemplateAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val existing = GenerateCommitMessageAction.currentMessage(e)
        val initial = if (existing.isBlank()) {
            val footer = CommitMessageGenerator(project).defaultFooter()
            CommitData(resolves = footer.resolves, releases = footer.releases)
        } else {
            CommitMessageParser.parse(existing)
        }

        val dialog = CommitTemplateDialog(project, initial, CommitSelection.from(e, project))
        if (dialog.showAndGet()) {
            messageControl.setCommitMessage(CommitMessageFormatter.format(dialog.current()))
            (messageControl as? CommitMessage)?.editorField?.requestFocusInWindow()
        }
    }
}
