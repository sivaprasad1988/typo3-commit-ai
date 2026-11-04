package io.github.sivaprasad1988.typo3commitai

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.layout.not
import com.intellij.ui.layout.selectedValueIs
import java.time.Duration

@Service(Service.Level.APP)
@State(name = "Typo3CommitAiSettings", storages = [Storage("typo3-commit-ai.xml")])
class Settings : PersistentStateComponent<Settings.Data> {
    class Data {
        var provider: Provider = Provider.OLLAMA
        var ollamaUrl: String = "http://localhost:11434"
        /** Ollama model (name kept for existing settings files). */
        var model: String = "qwen2.5-coder:14b"
        var openAiUrl: String = "https://api.openai.com/v1"
        var openAiModel: String = "gpt-6.1-sol"
        var anthropicUrl: String = "https://api.anthropic.com"
        var anthropicModel: String = "claude-opus-5-5"
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

    val activeModel: String
        get() = when (data.provider) {
            Provider.OLLAMA -> data.model
            Provider.OPENAI -> data.openAiModel
            Provider.ANTHROPIC -> data.anthropicModel
        }

    fun createClient(): AiClient {
        val timeout = Duration.ofSeconds(data.timeoutSeconds.toLong())
        val temperature = data.temperaturePercent / 100.0
        return when (data.provider) {
            Provider.OLLAMA -> OllamaClient(data.ollamaUrl, data.model, temperature, timeout)
            Provider.OPENAI -> OpenAiClient(data.openAiUrl, apiKey(Provider.OPENAI), data.openAiModel, temperature, timeout)
            Provider.ANTHROPIC -> AnthropicClient(data.anthropicUrl, apiKey(Provider.ANTHROPIC), data.anthropicModel, timeout)
        }
    }

    companion object {
        /** Offered in the settings drop-downs; any other model can still be typed in. */
        val SUGGESTED_MODELS = listOf("qwen2.5-coder:14b", "qwen2.5-coder:7b")
        val SUGGESTED_OPENAI_MODELS = listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-luna")
        val SUGGESTED_ANTHROPIC_MODELS = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")

        fun get(): Settings = service()

        /** API keys live in the IDE password safe (OS keychain), never in typo3-commit-ai.xml. */
        private fun keyAttributes(provider: Provider) =
            CredentialAttributes(generateServiceName("Core Commit AI", "${provider.name} API key"))

        fun apiKey(provider: Provider): String =
            PasswordSafe.instance.getPassword(keyAttributes(provider)).orEmpty()

        fun setApiKey(provider: Provider, key: String) =
            PasswordSafe.instance.setPassword(keyAttributes(provider), key.trim().ifEmpty { null })
    }
}

class SettingsConfigurable : BoundConfigurable("Core Commit AI") {
    override fun createPanel(): DialogPanel {
        val s = Settings.get().state
        return panel {
            lateinit var providerBox: javax.swing.JComboBox<Provider>
            group("Provider") {
                row("AI provider:") {
                    providerBox = comboBox(Provider.entries).bindItem({ s.provider }, { s.provider = it ?: Provider.OLLAMA }).component
                }
                row {
                    comment(
                        "With OpenAI or Claude the diff of the selected files is sent to that cloud service. " +
                            "Do not use them for code you may not share.",
                    )
                }.visibleIf(providerBox.selectedValueIs(Provider.OLLAMA).not())
            }
            group("Ollama") {
                row("URL:") { textField().bindText(s::ollamaUrl).columns(30) }
                row("Model:") {
                    comboBox(Settings.SUGGESTED_MODELS)
                        .applyToComponent { isEditable = true }
                        .bindItem({ s.model }, { s.model = it?.trim().orEmpty().ifEmpty { Settings.SUGGESTED_MODELS.first() } })
                        .columns(30)
                        .comment(
                            "<code>qwen2.5-coder:14b</code>: better messages, about 16 GB RAM. " +
                                "<code>qwen2.5-coder:7b</code>: about twice as fast, about 8 GB RAM. " +
                                "Or type any pulled model. Pull first, e.g. <code>ollama pull qwen2.5-coder:7b</code>",
                        )
                }
            }.visibleIf(providerBox.selectedValueIs(Provider.OLLAMA))
            group("OpenAI") {
                row("Base URL:") {
                    textField().bindText(s::openAiUrl).columns(30)
                        .comment("Any OpenAI-compatible server works, e.g. <code>https://openrouter.ai/api/v1</code>.")
                }
                row("API key:") {
                    passwordField().columns(30).bindText(
                        { Settings.apiKey(Provider.OPENAI) },
                        { Settings.setApiKey(Provider.OPENAI, it) },
                    ).comment("Stored in the IDE password safe.")
                }
                row("Model:") {
                    comboBox(Settings.SUGGESTED_OPENAI_MODELS)
                        .applyToComponent { isEditable = true }
                        .bindItem({ s.openAiModel }, { s.openAiModel = it?.trim().orEmpty().ifEmpty { Settings.SUGGESTED_OPENAI_MODELS.first() } })
                        .columns(30)
                        .comment("Or type any model your account or server offers.")
                }
            }.visibleIf(providerBox.selectedValueIs(Provider.OPENAI))
            group("Claude") {
                row("Base URL:") { textField().bindText(s::anthropicUrl).columns(30) }
                row("API key:") {
                    passwordField().columns(30).bindText(
                        { Settings.apiKey(Provider.ANTHROPIC) },
                        { Settings.setApiKey(Provider.ANTHROPIC, it) },
                    ).comment("Stored in the IDE password safe.")
                }
                row("Model:") {
                    comboBox(Settings.SUGGESTED_ANTHROPIC_MODELS)
                        .applyToComponent { isEditable = true }
                        .bindItem({ s.anthropicModel }, { s.anthropicModel = it?.trim().orEmpty().ifEmpty { Settings.SUGGESTED_ANTHROPIC_MODELS.first() } })
                        .columns(30)
                        .comment(
                            "<code>claude-opus-5-5</code>: best messages. <code>claude-haiku-5-5</code>: much cheaper and faster.",
                        )
                }
            }.visibleIf(providerBox.selectedValueIs(Provider.ANTHROPIC))
            group("Request") {
                row("Temperature (%):") {
                    intTextField(0..100).bindIntText(s::temperaturePercent)
                        .comment("Not sent to Claude (the API rejects it).")
                }
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
