package live.agor.jetbrains.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import live.agor.jetbrains.client.AgorApiClient
import live.agor.jetbrains.client.AgorPermissionRequest
import live.agor.jetbrains.client.AgorPermissionScope
import live.agor.jetbrains.client.AgorSession
import live.agor.jetbrains.client.AgorSocketClient
import live.agor.jetbrains.client.AgorSnapshot
import live.agor.jetbrains.client.AgorWorktree
import live.agor.jetbrains.settings.AgorSettings
import live.agor.jetbrains.settings.AgorSettingsDialog
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

private val LOG = Logger.getInstance(AgorToolWindowFactory::class.java)

class AgorToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = runCatching { AgorToolWindow(project) }
            .onFailure { LOG.error("Failed to initialize Agor tool window", it) }
            .getOrNull()
        val component = view?.component ?: fallbackPanel("Agor failed to initialize. See the JetBrains IDE log for details.")
        val content = ContentFactory.getInstance().createContent(component, "", false)
        toolWindow.contentManager.addContent(content)
        view?.refresh()
    }

}

private class AgorToolWindow(private val project: Project) {
    private val settings = AgorSettings.getInstance()
    private val tree = JTree(DefaultMutableTreeNode("Agor"))
    private val inspector = JPanel(BorderLayout(8, 8))
    private var snapshot = AgorSnapshot()
    private var socketClient: AgorSocketClient? = null
    private var socketConnectionKey: Pair<String, String?>? = null
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
        toolbar.add(JButton("Settings").apply { addActionListener { showSettings() } })
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
    }

    fun refresh() {
        val agorUrl = settings.state.agorUrl
        ApplicationManager.getApplication().executeOnPooledThread {
            val agorToken = settings.agorToken
            val client = AgorApiClient(agorUrl, agorToken)
            runCatching { client.loadSnapshot() }
                .onSuccess { loaded ->
                    SwingUtilities.invokeLater {
                        snapshot = loaded
                        tree.model = DefaultTreeModel(toSwingTree(AgorTreeModelBuilder().build(loaded.boards, loaded.worktrees, loaded.sessions)))
                        expandAll()
                        showEmptyInspector()
                        connectSocket(agorUrl, agorToken)
                    }
                }
                .onFailure { error ->
                    SwingUtilities.invokeLater {
                        LOG.warn("Could not refresh Agor snapshot", error)
                        showConnectionError(error)
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
        snapshot.permissionRequests
            .filter { it.sessionId == session.sessionId }
            .forEach { panel.add(permissionPanel(session.sessionId, it)) }
        replaceInspector(panel)
    }

    private fun permissionPanel(sessionId: String, permission: AgorPermissionRequest): JPanel {
        val panel = detailPanel(
            "Permission Required",
            permission.toolName,
            listOf(
                "Request: ${permission.requestId}",
                "Task: ${permission.taskId ?: "-"}",
                "Input: ${permission.toolInputJson}",
            ),
        )
        panel.add(JButton("Approve Once").apply {
            addActionListener {
                runClientAction {
                    decidePermission(sessionId, permission.requestId, permission.taskId, true, AgorPermissionScope.ONCE)
                }
            }
        })
        panel.add(JButton("Approve Project").apply {
            addActionListener {
                runClientAction {
                    decidePermission(sessionId, permission.requestId, permission.taskId, true, AgorPermissionScope.PROJECT)
                }
            }
        })
        panel.add(JButton("Deny").apply {
            addActionListener {
                runClientAction {
                    decidePermission(sessionId, permission.requestId, permission.taskId, false, AgorPermissionScope.ONCE)
                }
            }
        })
        return panel
    }

    private fun runClientAction(action: AgorApiClient.() -> Unit) {
        val agorUrl = settings.state.agorUrl
        ApplicationManager.getApplication().executeOnPooledThread {
            val client = AgorApiClient(agorUrl, settings.agorToken)
            runCatching { client.action() }
                .onSuccess { SwingUtilities.invokeLater { refresh() } }
                .onFailure { error ->
                    SwingUtilities.invokeLater {
                        LOG.warn("Agor action failed", error)
                        showActionError(error)
                    }
                }
        }
    }

    private fun showEmptyInspector() {
        showText("Agor", "Select a worktree or session.")
    }

    private fun showText(title: String, body: String) {
        replaceInspector(detailPanel(title, body, emptyList()))
    }

    private fun showConnectionError(error: Throwable) {
        val panel = detailPanel(
            "Agor",
            "Connection unavailable",
            listOf(error.userFacingMessage("Could not load Agor")),
        )
        panel.add(JButton("Configure").apply { addActionListener { showSettings() } })
        panel.add(JButton("Retry").apply { addActionListener { refresh() } })
        replaceInspector(panel)
    }

    private fun showActionError(error: Throwable) {
        replaceInspector(
            detailPanel(
                "Agor",
                "Action failed",
                listOf(error.userFacingMessage("Agor action failed")),
            ),
        )
    }

    private fun showSettings() {
        if (AgorSettingsDialog(project).showAndGet()) {
            socketClient?.disconnect()
            socketClient = null
            socketConnectionKey = null
            refresh()
        }
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

    private fun connectSocket(agorUrl: String, agorToken: String?) {
        if (agorToken.isNullOrBlank()) {
            socketClient?.disconnect()
            socketClient = null
            socketConnectionKey = null
            return
        }
        val connectionKey = agorUrl to agorToken
        if (socketClient != null && socketConnectionKey == connectionKey) return

        socketClient?.disconnect()
        socketConnectionKey = connectionKey
        socketClient = AgorSocketClient(agorUrl, agorToken) {
            SwingUtilities.invokeLater { refresh() }
        }.also {
            runCatching { it.connect() }
                .onFailure { error ->
                    socketConnectionKey = null
                    LOG.warn("Could not connect Agor socket", error)
                    showConnectionError(error)
                }
        }
    }
}

private data class NodeRef(val kind: AgorTreeNodeKind, val id: String, val label: String) {
    override fun toString(): String = label
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun Throwable.userFacingMessage(fallback: String): String =
    message?.takeIf { it.isNotBlank() } ?: fallback

private fun fallbackPanel(message: String): JPanel =
    JPanel(BorderLayout()).apply {
        add(JLabel(message), BorderLayout.NORTH)
    }
