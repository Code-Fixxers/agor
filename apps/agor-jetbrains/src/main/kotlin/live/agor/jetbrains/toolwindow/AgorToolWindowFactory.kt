package live.agor.jetbrains.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
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
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.Timer
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

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
    private var socketRefreshTimer: Timer? = null
    private val promptDrafts = mutableMapOf<String, String>()
    private var activePromptSessionId: String? = null
    private var activePrompt: JBTextArea? = null
    private var promptFocusSessionToRestore: String? = null
    val component: JPanel = JPanel(BorderLayout())

    init {
        tree.minimumSize = Dimension(260, 300)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.border = JBUI.Borders.empty(6)
        tree.addTreeSelectionListener { showSelection() }
        TreeSpeedSearch(tree)

        inspector.border = JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0)

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            background = UIUtil.getPanelBackground()
        }
        toolbar.add(JButton("Refresh").apply { addActionListener { refresh() } })
        toolbar.add(JButton("Settings").apply { addActionListener { showSettings() } })

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(tree), inspector).apply {
            resizeWeight = 0.36
            dividerSize = JBUI.scale(3)
            border = JBUI.Borders.empty()
        }
        component.add(toolbar, BorderLayout.NORTH)
        component.add(split, BorderLayout.CENTER)
        showEmptyInspector()
    }

    fun refresh() {
        val agorUrl = settings.state.agorUrl
        val selectionToRestore = selectedNodeRef()
        val expandedToRestore = expandedNodeRefs()
        val promptFocusToRestore = focusedPromptSessionId()
        ApplicationManager.getApplication().executeOnPooledThread {
            val agorToken = settings.agorToken
            val client = AgorApiClient(agorUrl, agorToken)
            runCatching { client.loadSnapshot() }
                .onSuccess { loaded ->
                    SwingUtilities.invokeLater {
                        renderSnapshot(loaded, selectionToRestore, expandedToRestore, promptFocusToRestore)
                        connectSocket(client.connectionBaseUrl(), client.currentBearerToken())
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

    private fun renderSnapshot(
        loaded: AgorSnapshot,
        selectionToRestore: AgorNodeRef?,
        expandedToRestore: Set<AgorNodeKey>,
        promptFocusToRestore: String?,
    ) {
        snapshot = loaded
        val root = toSwingTree(AgorTreeModelBuilder().build(loaded.boards, loaded.worktrees, loaded.sessions))
        tree.model = DefaultTreeModel(root)
        if (expandedToRestore.isEmpty() && selectionToRestore == null) {
            expandAll()
        } else {
            expandNodeRefs(root, expandedToRestore)
        }

        val restoredPath = selectionToRestore?.let { findNodePath(root, it) }
        if (restoredPath != null) {
            tree.expandPath(restoredPath.parentPath)
            tree.selectionPath = restoredPath
            tree.scrollPathToVisible(restoredPath)
            promptFocusSessionToRestore = promptFocusToRestore
                ?.takeIf { selectionToRestore.kind == AgorTreeNodeKind.SESSION && selectionToRestore.id == it }
            showSelection()
        } else {
            promptFocusSessionToRestore = null
            showEmptyInspector()
        }
    }

    private fun showSelection() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        when (val value = node.userObject) {
            is AgorNodeRef -> when (value.kind) {
                AgorTreeNodeKind.WORKTREE -> snapshot.worktrees.firstOrNull { it.worktreeId == value.id }?.let { showWorktree(it) }
                AgorTreeNodeKind.SESSION -> snapshot.sessions.firstOrNull { it.sessionId == value.id }?.let { showSession(it) }
                AgorTreeNodeKind.BOARD -> showText("Board", value.label)
            }
            else -> showEmptyInspector()
        }
    }

    private fun showWorktree(worktree: AgorWorktree) {
        val panel = detailPanel("Worktree", worktree.name, listOf("Branch/ref: ${worktree.ref ?: "-"}", "Path: ${worktree.path}"))
        panel.add(Box.createVerticalStrut(JBUI.scale(14)))
        panel.add(buttonRow(
            JButton("Open Path").apply {
                addActionListener {
                    val file = File(worktree.path)
                    if (file.exists()) {
                        FileEditorManager.getInstance(project).openFile(com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return@addActionListener, true)
                    }
                }
            },
        ))
        replaceInspector(panel)
    }

    private fun showSession(session: AgorSession) {
        val panel = detailPanel(
            "Session",
            session.title,
            listOf("Status: ${session.status}", "Agent: ${session.agenticTool}", "ID: ${session.sessionId}"),
        )
        val prompt = JBTextArea(4, 32).apply {
            text = promptDrafts[session.sessionId].orEmpty()
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(6)
        }
        activePromptSessionId = session.sessionId
        activePrompt = prompt
        prompt.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = saveDraft()
            override fun removeUpdate(event: DocumentEvent) = saveDraft()
            override fun changedUpdate(event: DocumentEvent) = saveDraft()

            private fun saveDraft() {
                promptDrafts[session.sessionId] = prompt.text
            }
        })
        val promptScroll = JBScrollPane(prompt).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            preferredSize = Dimension(JBUI.scale(460), JBUI.scale(100))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(120))
            border = JBUI.Borders.customLine(JBColor.border())
        }
        panel.add(Box.createVerticalStrut(JBUI.scale(16)))
        panel.add(sectionLabel("Prompt"))
        panel.add(Box.createVerticalStrut(JBUI.scale(6)))
        panel.add(promptScroll)
        panel.add(Box.createVerticalStrut(JBUI.scale(10)))
        panel.add(buttonRow(
            JButton("Send").apply {
                addActionListener {
                    val text = prompt.text.trim()
                    if (text.isNotEmpty()) runClientAction { promptSession(session.sessionId, text) }
                }
            },
            JButton("Stop").apply {
                addActionListener { runClientAction { stopSession(session.sessionId) } }
            },
        ))
        snapshot.permissionRequests
            .filter { it.sessionId == session.sessionId }
            .forEach { panel.add(permissionPanel(session.sessionId, it)) }
        replaceInspector(panel)
        if (promptFocusSessionToRestore == session.sessionId) {
            promptFocusSessionToRestore = null
            SwingUtilities.invokeLater {
                prompt.requestFocusInWindow()
                prompt.caretPosition = prompt.text.length
            }
        }
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
        panel.add(Box.createVerticalStrut(JBUI.scale(10)))
        panel.add(buttonRow(
            JButton("Approve Once").apply {
                addActionListener {
                    runClientAction {
                        decidePermission(sessionId, permission.requestId, permission.taskId, true, AgorPermissionScope.ONCE)
                    }
                }
            },
            JButton("Approve Project").apply {
                addActionListener {
                    runClientAction {
                        decidePermission(sessionId, permission.requestId, permission.taskId, true, AgorPermissionScope.PROJECT)
                    }
                }
            },
            JButton("Deny").apply {
                addActionListener {
                    runClientAction {
                        decidePermission(sessionId, permission.requestId, permission.taskId, false, AgorPermissionScope.ONCE)
                    }
                }
            },
        ))
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
        panel.add(Box.createVerticalStrut(JBUI.scale(14)))
        panel.add(buttonRow(
            JButton("Configure").apply { addActionListener { showSettings() } },
            JButton("Retry").apply { addActionListener { refresh() } },
        ))
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
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(14, 18, 18, 18)
            background = UIUtil.getPanelBackground()
        }
        panel.add(JLabel(kicker.uppercase()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.BOLD, 11f)
        })
        panel.add(Box.createVerticalStrut(JBUI.scale(8)))
        panel.add(JLabel(title).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            foreground = UIUtil.getLabelForeground()
            font = font.deriveFont(Font.BOLD, 18f)
        })
        panel.add(Box.createVerticalStrut(JBUI.scale(12)))
        lines.forEach { panel.add(metaLabel(it)) }
        return panel
    }

    private fun metaLabel(text: String): JLabel =
        JLabel(text).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            foreground = JBColor.namedColor("Label.infoForeground", JBColor(0x6B7280, 0x9CA3AF))
            border = JBUI.Borders.emptyBottom(4)
        }

    private fun sectionLabel(text: String): JLabel =
        JLabel(text).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            foreground = UIUtil.getLabelForeground()
            font = font.deriveFont(Font.BOLD, 12f)
        }

    private fun buttonRow(vararg buttons: JButton): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            isOpaque = false
            buttons.forEach { add(it) }
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun replaceInspector(panel: JPanel) {
        inspector.removeAll()
        inspector.add(panel, BorderLayout.CENTER)
        inspector.revalidate()
        inspector.repaint()
    }

    private fun toSwingTree(nodes: List<AgorTreeNode>): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode("Agor")
        fun add(parent: DefaultMutableTreeNode, node: AgorTreeNode) {
            val swing = DefaultMutableTreeNode(AgorNodeRef(node.kind, node.id, node.label))
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
            SwingUtilities.invokeLater { scheduleBackgroundRefresh() }
        }.also {
            runCatching { it.connect() }
                .onFailure { error ->
                    socketConnectionKey = null
                    LOG.warn("Could not connect Agor socket", error)
                    showConnectionError(error)
                }
        }
    }

    private fun scheduleBackgroundRefresh() {
        socketRefreshTimer?.stop()
        socketRefreshTimer = Timer(750) {
            socketRefreshTimer = null
            refresh()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun selectedNodeRef(): AgorNodeRef? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? AgorNodeRef
    }

    private fun focusedPromptSessionId(): String? {
        val prompt = activePrompt ?: return null
        return activePromptSessionId?.takeIf { prompt.isFocusOwner }
    }

    private fun expandedNodeRefs(): Set<AgorNodeKey> {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return emptySet()
        val expanded = tree.getExpandedDescendants(TreePath(root.path)) ?: return emptySet()
        return expanded.asSequence()
            .mapNotNull { ((it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? AgorNodeRef)?.key() }
            .toSet()
    }

    private fun expandNodeRefs(root: DefaultMutableTreeNode, refs: Set<AgorNodeKey>) {
        refs.forEach { ref ->
            findNodePath(root, ref)?.let { tree.expandPath(it) }
        }
    }
}

internal data class AgorNodeRef(val kind: AgorTreeNodeKind, val id: String, val label: String) {
    override fun toString(): String = label
}

internal data class AgorNodeKey(val kind: AgorTreeNodeKind, val id: String)

internal fun AgorNodeRef.key(): AgorNodeKey = AgorNodeKey(kind, id)

internal fun findNodePath(root: DefaultMutableTreeNode, target: AgorNodeRef): TreePath? {
    return findNodePath(root, target.key())
}

internal fun findNodePath(root: DefaultMutableTreeNode, target: AgorNodeKey): TreePath? {
    val stack = ArrayDeque<TreePath>()
    stack.add(TreePath(root.path))
    while (stack.isNotEmpty()) {
        val path = stack.removeLast()
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
        val ref = node.userObject as? AgorNodeRef
        if (ref?.kind == target.kind && ref.id == target.id) return path
        for (index in 0 until node.childCount) {
            stack.add(path.pathByAddingChild(node.getChildAt(index)))
        }
    }
    return null
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun Throwable.userFacingMessage(fallback: String): String =
    message?.takeIf { it.isNotBlank() } ?: fallback

private fun fallbackPanel(message: String): JPanel =
    JPanel(BorderLayout()).apply {
        add(JLabel(message), BorderLayout.NORTH)
    }
