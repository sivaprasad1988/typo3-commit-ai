package de.gingco.typo3commitai

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

@Service(Service.Level.APP)
@State(name = "Typo3CommitAiSettings", storages = [Storage("typo3-commit-ai.xml")])
class Settings : PersistentStateComponent<Settings.Data> {
    class Data {
        var ollamaUrl: String = "http://localhost:11434"
        var model: String = "qwen2.5-coder:14b"
        /** Blank = use the built-in default, so prompt improvements reach users who never edited it. */
        var systemPrompt: String = ""
        var temperaturePercent: Int = 20
        var maxDiffChars: Int = 40000
        var timeoutSeconds: Int = 180
        var addResolves: Boolean = true
        var issueRegex: String = """(?:^|[/_-])(\d{3,})(?=[/_-]|$)"""
        var releases: String = ""
    }

    private var data = Data()

    override fun getState(): Data = data
    override fun loadState(state: Data) {
        data = state
    }

    val effectiveSystemPrompt: String
        get() = data.systemPrompt.ifBlank { Prompts.DEFAULT_SYSTEM }

    companion object {
        fun get(): Settings = service()
    }
}

class SettingsConfigurable : BoundConfigurable("TYPO3 Commit AI") {
    override fun createPanel(): DialogPanel {
        val s = Settings.get().state
        return panel {
            group("Ollama") {
                row("URL:") { textField().bindText(s::ollamaUrl).columns(30) }
                row("Model:") {
                    textField().bindText(s::model).columns(30)
                        .comment("Must be pulled first, e.g. <code>ollama pull qwen2.5-coder:14b</code>")
                }
                row("Temperature (%):") { intTextField(0..100).bindIntText(s::temperaturePercent) }
                row("Timeout (s):") { intTextField(10..900).bindIntText(s::timeoutSeconds) }
                row("Max diff size (chars):") {
                    intTextField(2000..400000).bindIntText(s::maxDiffChars)
                        .comment("Larger diffs are cut and you get a warning listing the affected files.")
                }
            }
            group("Footer") {
                row { checkBox("Add 'Resolves: #N' from the branch name").bindSelected(s::addResolves) }
                row("Issue regex:") {
                    textField().bindText(s::issueRegex).columns(30)
                        .comment("Group 1 is the issue number. Default matches feature/12345-text.")
                }
                row("Releases:") {
                    textField().bindText(s::releases).columns(30)
                        .comment("e.g. <code>main, 13.4</code>. Empty = no Releases line.")
                }
            }
            group("System Prompt") {
                row {
                    textArea()
                        .bindText(
                            { s.systemPrompt.ifBlank { Prompts.DEFAULT_SYSTEM } },
                            { s.systemPrompt = if (it.trim() == Prompts.DEFAULT_SYSTEM) "" else it },
                        )
                        .rows(18)
                        .align(AlignX.FILL)
                        .comment("Keep the JSON field names (type, breaking, subject, bullets). Clear the text to restore the default.")
                }
            }
        }
    }
}
