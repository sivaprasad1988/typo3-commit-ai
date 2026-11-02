package io.github.sivaprasad1988.typo3commitai

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/** Builds a real unified diff for exactly the changes included in the commit. */
class DiffCollector(private val project: Project) {
    private val repos = GitRepositoryManager.getInstance(project)

    fun collect(changes: List<Change>, unversioned: List<FilePath>): List<FileDiff> {
        val tracked = LinkedHashMap<GitRepository, LinkedHashSet<FilePath>>()
        val result = mutableListOf<FileDiff>()

        for (change in changes) {
            val before = change.beforeRevision
            val after = change.afterRevision
            if (before == null) {
                // Added file: not in HEAD, so build the diff from its content.
                val path = after?.file ?: continue
                result += DiffBudget.newFile(relative(path), runCatching { after.content }.getOrNull())
                continue
            }
            val repo = repos.getRepositoryForFileQuick(before.file) ?: continue
            tracked.getOrPut(repo) { LinkedHashSet() }.apply {
                add(before.file)
                after?.file?.let { add(it) }
            }
        }

        for ((repo, paths) in tracked) {
            result.addAll(0, DiffBudget.split(gitDiff(repo, paths)))
        }

        for (path in unversioned) {
            val file = path.virtualFile
            val text = file?.takeUnless { it.fileType.isBinary }?.let { loadText(it) }
            result += DiffBudget.newFile(relative(path), text)
        }
        return result
    }

    private fun gitDiff(repo: GitRepository, paths: Collection<FilePath>): String {
        val handler = GitLineHandler(project, repo.root, GitCommand.DIFF).apply {
            setSilent(true)
            addParameters("--no-color", "--no-ext-diff", "-M", "-U3", "HEAD")
            endOptions()
            addRelativePaths(paths)
        }
        return Git.getInstance().runCommand(handler).getOutputOrThrow()
    }

    private fun loadText(file: VirtualFile): String? = runCatching { VfsUtilCore.loadText(file) }.getOrNull()

    private fun relative(path: FilePath): String {
        val root = repos.getRepositoryForFileQuick(path)?.root?.path ?: project.basePath
        return root?.let { FileUtil.getRelativePath(it, path.path, '/') } ?: path.name
    }
}
