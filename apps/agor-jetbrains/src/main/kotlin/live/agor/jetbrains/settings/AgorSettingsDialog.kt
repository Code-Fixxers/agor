package live.agor.jetbrains.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

class AgorSettingsDialog(project: Project?) : DialogWrapper(project) {
    private val settings = AgorSettings.getInstance()
    private val agorUrl = JTextField(settings.state.agorUrl, 34)
    private val agorToken = JPasswordField(settings.agorToken.orEmpty(), 34)
    private val hermesUrl = JTextField(settings.state.hermesUrl, 34)
    private val hermesToken = JPasswordField(settings.hermesToken.orEmpty(), 34)
    private val hermesModel = JTextField(settings.state.hermesModel, 34)
    private val hermesProxyPath = JTextField(settings.state.hermesProxyPath, 34)

    init {
        title = "Agor Settings"
        init()
    }

    override fun createCenterPanel(): JComponent =
        JPanel(GridBagLayout()).apply {
            var row = 0
            addSection("Agor", row++)
            addRow("Server URL or IP", agorUrl, row++)
            addRow("User API key", agorToken, row++)
            addSection("Hermes ACP", row++)
            addRow("Gateway URL or IP", hermesUrl, row++)
            addRow("Token", hermesToken, row++)
            addRow("Model", hermesModel, row++)
            addRow("Proxy path", hermesProxyPath, row++)
        }

    override fun doOKAction() {
        val state = settings.state
        state.agorUrl = agorUrl.text.trim()
        state.hermesUrl = hermesUrl.text.trim()
        state.hermesModel = hermesModel.text.trim()
        state.hermesProxyPath = hermesProxyPath.text.trim()
        settings.agorToken = agorToken.passwordText().ifBlank { null }
        settings.hermesToken = hermesToken.passwordText().ifBlank { null }
        super.doOKAction()
    }

    private fun JPanel.addSection(label: String, row: Int) {
        add(
            JLabel("<html><b>$label</b></html>"),
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                gridwidth = 2
                anchor = GridBagConstraints.WEST
                insets = Insets(if (row == 0) 0 else 14, 0, 6, 0)
            },
        )
    }

    private fun JPanel.addRow(label: String, field: JComponent, row: Int) {
        add(
            JLabel(label),
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                anchor = GridBagConstraints.WEST
                insets = Insets(3, 0, 3, 10)
            },
        )
        add(
            field,
            GridBagConstraints().apply {
                gridx = 1
                gridy = row
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(3, 0, 3, 0)
            },
        )
    }

    private fun JPasswordField.passwordText(): String =
        String(password).trim()
}
