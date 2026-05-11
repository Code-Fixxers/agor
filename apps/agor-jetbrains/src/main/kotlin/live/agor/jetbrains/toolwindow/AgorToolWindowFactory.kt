package live.agor.jetbrains.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import live.agor.jetbrains.client.AgorApiClient
import live.agor.jetbrains.client.AgorSession
import live.agor.jetbrains.client.AgorSocketClient
import live.agor.jetbrains.client.AgorSnapshot
import live.agor.jetbrains.client.AgorWorktree
import live.agor.jetbrains.settings.AgorSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class AgorToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = AgorToolWindow(project)
        val content = ContentFactory.getInstance().createContent(view.component, "", false)
        toolWindow.contentManager.addContent(content)
        view.refresh()
    }
}

private class AgorToolWindow(private val project: Project) {
    private val settings = AgorSettings.getInstance()
    private val tree = JTree(DefaultMutableTreeNode("Agor"))
    private val inspector = JPanel(BorderLayout(8, 8))
    private var snapshot = AgorSnapshot()
    private var socketClient: AgorSocketClient? = null
    val component: JPanel = JPanel(BorderLayout())

    init {
        tree.minimumSize = Dimension(260, 300)
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount >= 1) showSelection()
            }
        })

        val toolbar = JPanel()
        toolbar.add(JButton("Refresh").apply { addActionListener { refresh() } })
        toolbar.add(JButton("Hermes AI Chat").apply {
            toolTipText = "Select Hermes in JetBrains AI Chat. ACP is configured by Home Manager or ~/.jetbrains/acp.json."
            addActionListener {
                Messages.showInfoMessage(
                    project,
                    "Open JetBrains AI Chat and select the Hermes ACP agent.",
                    "Hermes ACP",
                )
            }
        })

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(tree), inspector)
        split.resizeWeight = 0.42
        component.add(toolbar, BorderLayout.NORTH)
        component.add(split, BorderLayout.CENTER)
        showEmptyInspector()
        connectSocket()
    }

    fun refresh() {
        val state = settings.state
        val client = AgorApiClient(state.agorUrl, settings.agorToken)
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { client.loadSnapshot() }
                .onSuccess { loaded ->
                    SwingUtilities.invokeLater {
                        snapshot = loaded
                        tree.model = DefaultTreeModel(toSwingTree(AgorTreeModelBuilder().build(loaded.boards, loaded.worktrees, loaded.sessions)))
                        expandAll()
                        showEmptyInspector()
                    }
                }
                .onFailure { error ->
                    SwingUtilities.invokeLater {
                        Messages.showErrorDialog(project, error.message ?: "Could not load Agor", "Agor")
                    }
                }
        }
    }

    private fun showSelection() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        when (val value = node.userObject) {
            is NodeRef -> when (value.kind) {
                AgorTreeNodeKind.WORKTREE -> snapshot.worktrees.firstOrNull { it.worktreeId == value.id }?.let { showWorktree(it) }
                AgorTreeNodeKind.SESSION -> snapshot.sessions.firstOrNull { it.sessionId == value.id }?.let { showSession(it) }
                AgorTreeNodeKind.BOARD -> showText("Board", value.label)
            }
            else -> showEmptyInspector()
        }
    }

    private fun showWorktree(worktree: AgorWorktree) {
        val panel = detailPanel("Worktree", worktree.name, listOf("Branch/ref: ${worktree.ref ?: "-"}", "Path: ${worktree.path}"))
        panel.add(JButton("Open Path").apply {
            addActionListener {
                val file = File(worktree.path)
                if (file.exists()) {
                    FileEditorManager.getInstance(project).openFile(com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return@addActionListener, true)
                }
            }
        })
        replaceInspector(panel)
    }

    private fun showSession(session: AgorSession) {
        val panel = detailPanel(
            "Session",
            session.title,
            listOf("Status: ${session.status}", "Agent: ${session.agenticTool}", "ID: ${session.sessionId}"),
        )
        val prompt = JTextArea(4, 32)
        panel.add(JScrollPane(prompt))
        panel.add(JButton("Prompt").apply {
            addActionListener {
                val text = prompt.text.trim()
                if (text.isNotEmpty()) runClientAction { promptSession(session.sessionId, text) }
            }
        })
        panel.add(JButton("Stop").apply {
            addActionListener { runClientAction { stopSession(session.sessionId) } }
        })
        replaceInspector(panel)
    }

    private fun runClientAction(action: AgorApiClient.() -> Unit) {
        val client = AgorApiClient(settings.state.agorUrl, settings.agorToken)
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { client.action() }
                .onSuccess { SwingUtilities.invokeLater { refresh() } }
                .onFailure { error -> SwingUtilities.invokeLater { Messages.showErrorDialog(project, error.message ?: "Agor action failed", "Agor") } }
        }
    }

    private fun showEmptyInspector() {
        showText("Agor", "Select a worktree or session.")
    }

    private fun showText(title: String, body: String) {
        replaceInspector(detailPanel(title, body, emptyList()))
    }

    private fun detailPanel(kicker: String, title: String, lines: List<String>): JPanel {
        val panel = JPanel()
        panel.layout = javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)
        panel.add(JLabel(kicker))
        panel.add(JLabel("<html><h2>${title.escapeHtml()}</h2></html>"))
        lines.forEach { panel.add(JLabel(it)) }
        return panel
    }

    private fun replaceInspector(panel: JPanel) {
        inspector.removeAll()
        inspector.add(panel, BorderLayout.NORTH)
        inspector.revalidate()
        inspector.repaint()
    }

    private fun toSwingTree(nodes: List<AgorTreeNode>): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode("Agor")
        fun add(parent: DefaultMutableTreeNode, node: AgorTreeNode) {
            val swing = DefaultMutableTreeNode(NodeRef(node.kind, node.id, node.label))
            parent.add(swing)
            node.children.forEach { add(swing, it) }
        }
        nodes.forEach { add(root, it) }
        return root
    }

    private fun expandAll() {
        for (i in 0 until tree.rowCount) tree.expandRow(i)
    }

    private fun connectSocket() {
        socketClient?.disconnect()
        socketClient = AgorSocketClient(settings.state.agorUrl, settings.agorToken) {
            SwingUtilities.invokeLater { refresh() }
        }.also { it.connect() }
    }
}

private data class NodeRef(val kind: AgorTreeNodeKind, val id: String, val label: String) {
    override fun toString(): String = label
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
