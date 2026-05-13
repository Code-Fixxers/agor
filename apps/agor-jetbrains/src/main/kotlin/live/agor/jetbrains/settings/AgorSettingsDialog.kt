package live.agor.jetbrains.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import live.agor.jetbrains.toolwindow.AgorSplitLayoutMode
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

class AgorSettingsDialog(project: Project?) : DialogWrapper(project) {
    private val settings = AgorSettings.getInstance()
    private val agorUrl = JTextField(settings.state.agorUrl, 34)
    private val agorToken = JPasswordField(settings.agorToken.orEmpty(), 34)
    private val splitLayoutMode = JComboBox(AgorSplitLayoutMode.entries.toTypedArray()).apply {
        renderer = javax.swing.DefaultListCellRenderer().apply {
            horizontalAlignment = JLabel.LEFT
        }
        selectedItem = AgorSplitLayoutMode.fromId(settings.state.splitLayoutMode)
    }

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
            addRow("Tool window layout", splitLayoutMode, row++)
        }

    override fun doOKAction() {
        val state = settings.state
        state.agorUrl = agorUrl.text.trim()
        state.splitLayoutMode = (splitLayoutMode.selectedItem as? AgorSplitLayoutMode ?: AgorSplitLayoutMode.AUTO).id
        settings.agorToken = agorToken.passwordText().ifBlank { null }
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
