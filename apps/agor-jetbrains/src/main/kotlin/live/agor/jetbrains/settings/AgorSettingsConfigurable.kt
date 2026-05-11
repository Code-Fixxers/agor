package live.agor.jetbrains.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class AgorSettingsConfigurable : SearchableConfigurable {
    private val settings = AgorSettings.getInstance()
    private var panel: DialogPanel? = null
    private var agorTokenDraft: String = settings.agorToken.orEmpty()
    private var hermesTokenDraft: String = settings.hermesToken.orEmpty()

    override fun getId(): String = "live.agor.jetbrains.settings"

    override fun getDisplayName(): String = "Agor"

    override fun createComponent(): JComponent {
        val state = settings.state
        panel = panel {
            group("Agor daemon") {
                row("URL") {
                    textField().bindText(state::agorUrl)
                }
                row("API token") {
                    passwordField().bindText(::agorTokenDraft)
                }
            }
            group("Hermes ACP") {
                row("URL") {
                    textField().bindText(state::hermesUrl)
                }
                row("Token") {
                    passwordField().bindText(::hermesTokenDraft)
                }
                row("Model") {
                    textField().bindText(state::hermesModel)
                }
                row("Proxy path") {
                    textField().bindText(state::hermesProxyPath)
                }
            }
        }
        return panel as DialogPanel
    }

    override fun isModified(): Boolean =
        panel?.isModified() == true ||
            agorTokenDraft != settings.agorToken.orEmpty() ||
            hermesTokenDraft != settings.hermesToken.orEmpty()

    override fun apply() {
        panel?.apply()
        settings.agorToken = agorTokenDraft.ifBlank { null }
        settings.hermesToken = hermesTokenDraft.ifBlank { null }
    }

    override fun reset() {
        agorTokenDraft = settings.agorToken.orEmpty()
        hermesTokenDraft = settings.hermesToken.orEmpty()
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
