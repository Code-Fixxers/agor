package live.agor.jetbrains.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import live.agor.jetbrains.client.AgorBoard
import live.agor.jetbrains.client.AgorRepo
import live.agor.jetbrains.client.AgorWorktree
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

internal data class AgorNewSessionInput(
    val title: String?,
    val agenticTool: String,
    val prompt: String?,
)

internal data class AgorNewWorktreeInput(
    val repoId: String,
    val boardId: String?,
    val name: String,
    val sourceBranch: String,
    val createBranch: Boolean,
    val pullLatest: Boolean,
)

internal class AgorNewSessionDialog(
    project: Project?,
    private val promptRequired: Boolean,
    titleText: String,
    defaultAgent: String = "codex",
    agentEnabled: Boolean = true,
) : DialogWrapper(project) {
    private val titleField = JBTextField(34)
    private val agentField = JComboBox(AGENT_CHOICES).apply {
        selectedItem = defaultAgent.takeIf { AGENT_CHOICES.contains(it) } ?: "codex"
        isEnabled = agentEnabled
    }
    private val promptField = JBTextArea(6, 34).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = titleText
        init()
    }

    fun input(): AgorNewSessionInput =
        AgorNewSessionInput(
            title = titleField.text.trim().ifBlank { null },
            agenticTool = agentField.selectedItem as String,
            prompt = promptField.text.trim().ifBlank { null },
        )

    override fun createCenterPanel(): JComponent =
        formPanel {
            addRow("Title", titleField, 0)
            addRow("Agent", agentField, 1)
            addRow(if (promptRequired) "Prompt" else "Initial prompt", promptField, 2)
        }

    override fun doValidate(): ValidationInfo? =
        if (promptRequired && promptField.text.isBlank()) ValidationInfo("Prompt is required.", promptField) else null
}

internal class AgorNewWorktreeDialog(
    project: Project?,
    private val repos: List<AgorRepo>,
    boards: List<AgorBoard>,
    selectedBoardId: String?,
) : DialogWrapper(project) {
    private val repoChoices = repos.map { RepoChoice(it) }
    private val boardChoices = listOf(BoardChoice(null, "No board")) + boards.map { BoardChoice(it.boardId, it.name) }
    private val repoField = JComboBox(repoChoices.toTypedArray())
    private val boardField = JComboBox(boardChoices.toTypedArray()).apply {
        selectedItem = boardChoices.firstOrNull { it.boardId == selectedBoardId } ?: boardChoices.first()
    }
    private val nameField = JBTextField(34)
    private val sourceBranchField = JBTextField(defaultSourceBranch(), 34)
    private val createBranchField = JBCheckBox("Create branch", true)
    private val pullLatestField = JBCheckBox("Pull latest", true)

    init {
        title = "New Agor Worktree"
        repoField.addActionListener {
            sourceBranchField.text = selectedRepo()?.defaultBranch ?: "main"
        }
        init()
    }

    fun input(): AgorNewWorktreeInput =
        AgorNewWorktreeInput(
            repoId = selectedRepo()?.repoId.orEmpty(),
            boardId = (boardField.selectedItem as? BoardChoice)?.boardId,
            name = nameField.text.trim(),
            sourceBranch = sourceBranchField.text.trim(),
            createBranch = createBranchField.isSelected,
            pullLatest = pullLatestField.isSelected,
        )

    override fun createCenterPanel(): JComponent =
        formPanel {
            addRow("Repository", repoField, 0)
            addRow("Board", boardField, 1)
            addRow("Worktree name", nameField, 2)
            addRow("Source branch", sourceBranchField, 3)
            addRow("", createBranchField, 4)
            addRow("", pullLatestField, 5)
        }

    override fun doValidate(): ValidationInfo? {
        if (repoChoices.isEmpty()) return ValidationInfo("Create or import a repository in Agor first.", repoField)
        if (nameField.text.isBlank()) return ValidationInfo("Worktree name is required.", nameField)
        if (!nameField.text.trim().matches(WORKTREE_NAME)) {
            return ValidationInfo("Use lowercase letters, numbers, and hyphens.", nameField)
        }
        if (sourceBranchField.text.isBlank()) return ValidationInfo("Source branch is required.", sourceBranchField)
        return null
    }

    private fun selectedRepo(): AgorRepo? = (repoField.selectedItem as? RepoChoice)?.repo
    private fun defaultSourceBranch(): String = repos.firstOrNull()?.defaultBranch ?: "main"

    private data class RepoChoice(val repo: AgorRepo) {
        override fun toString(): String = "${repo.name} (${repo.slug})"
    }

    private data class BoardChoice(val boardId: String?, val label: String) {
        override fun toString(): String = label
    }
}

private val AGENT_CHOICES = arrayOf("codex", "claude-code", "gemini", "opencode", "copilot")
private val WORKTREE_NAME = Regex("[a-z0-9][a-z0-9-]*")

private fun formPanel(build: JPanel.() -> Unit): JPanel =
    JPanel(GridBagLayout()).apply {
        border = JBUI.Borders.empty(4)
        build()
    }

private fun JPanel.addRow(label: String, field: JComponent, row: Int) {
    add(
        JLabel(label),
        GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 0, 4, 10)
        },
    )
    add(
        field,
        GridBagConstraints().apply {
            gridx = 1
            gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 0, 4, 0)
        },
    )
}
