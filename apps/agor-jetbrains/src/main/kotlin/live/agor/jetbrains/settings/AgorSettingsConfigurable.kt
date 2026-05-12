package live.agor.jetbrains.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class AgorSettingsConfigurable : SearchableConfigurable {
    private val settings = AgorSettings.getInstance()
    private var panel: DialogPanel? = null
    private var agorTokenDraft: String = ""
    private var hermesTokenDraft: String = ""
    private var originalAgorToken: String = ""
    private var originalHermesToken: String = ""

    override fun getId(): String = "live.agor.jetbrains.settings"

    override fun getDisplayName(): String = "Agor"

    override fun createComponent(): JComponent {
        val state = settings.state
        loadTokenDrafts()
        panel = panel {
            group("Agor") {
                row("Server URL or IP") {
                    textField().bindText(state::agorUrl)
                }
                row("User API key") {
                    passwordField().bindText(::agorTokenDraft)
                }
            }
            group("Hermes ACP") {
                row("Gateway URL or IP") {
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
            agorTokenDraft != originalAgorToken ||
            hermesTokenDraft != originalHermesToken

    override fun apply() {
        panel?.apply()
        settings.agorToken = agorTokenDraft.ifBlank { null }
        settings.hermesToken = hermesTokenDraft.ifBlank { null }
        originalAgorToken = agorTokenDraft
        originalHermesToken = hermesTokenDraft
    }

    override fun reset() {
        loadTokenDrafts()
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun loadTokenDrafts() {
        agorTokenDraft = settings.agorToken.orEmpty()
        hermesTokenDraft = settings.hermesToken.orEmpty()
        originalAgorToken = agorTokenDraft
        originalHermesToken = hermesTokenDraft
    }
}
