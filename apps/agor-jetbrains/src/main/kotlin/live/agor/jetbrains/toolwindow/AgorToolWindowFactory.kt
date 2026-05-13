package live.agor.jetbrains.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import live.agor.jetbrains.client.AgorApiClient
import live.agor.jetbrains.client.AgorCreateSessionRequest
import live.agor.jetbrains.client.AgorCreateWorktreeRequest
import live.agor.jetbrains.client.AgorPermissionRequest
import live.agor.jetbrains.client.AgorPermissionScope
import live.agor.jetbrains.client.AgorSession
import live.agor.jetbrains.client.AgorSessionStatus
import live.agor.jetbrains.client.AgorSocketClient
import live.agor.jetbrains.client.AgorSpawnSessionRequest
import live.agor.jetbrains.client.AgorSnapshot
import live.agor.jetbrains.client.AgorWorktree
import live.agor.jetbrains.settings.AgorSettings
import live.agor.jetbrains.settings.AgorSettingsDialog
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.BoxLayout
import javax.swing.Box
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
        val view = runCatching { AgorToolWindow(project, toolWindow) }
            .onFailure { LOG.error("Failed to initialize Agor tool window", it) }
            .getOrNull()
        val component = view?.component ?: fallbackPanel("Agor failed to initialize. See the JetBrains IDE log for details.")
        val content = ContentFactory.getInstance().createContent(component, "", false)
        toolWindow.contentManager.addContent(content)
        view?.refresh()
    }

}

private class AgorToolWindow(private val project: Project, private val toolWindow: ToolWindow) {
    private val settings = AgorSettings.getInstance()
    private val tree = Tree(DefaultMutableTreeNode("Agor"))
    private val inspector = JPanel(BorderLayout(8, 8))
    private lateinit var splitPane: JSplitPane
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
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.border = JBUI.Borders.empty(6)
        tree.addTreeSelectionListener { showSelection() }
        tree.cellRenderer = AgorTreeRenderer { snapshot }
        TreeSpeedSearch(tree)

        val actions = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Refresh", "Refresh Agor", AgorIcons.Refresh) {
                override fun actionPerformed(event: AnActionEvent) = refresh()
            })
            add(object : DumbAwareAction("New Worktree", "Create an Agor worktree", AgorIcons.NewWorktree) {
                override fun actionPerformed(event: AnActionEvent) = showNewWorktree()
            })
            add(object : DumbAwareAction("New Session", "Create an Agor session", AgorIcons.NewSession) {
                override fun actionPerformed(event: AnActionEvent) = selectedWorktree()?.let { showNewSession(it) } ?: showText("Agor", "Select a worktree first.")
            })
            add(object : DumbAwareAction("Layout", "Cycle Agor split layout", AgorIcons.Layout) {
                override fun actionPerformed(event: AnActionEvent) = cycleSplitLayout()
            })
            addSeparator()
            add(object : DumbAwareAction("Settings", "Configure Agor", AgorIcons.Settings) {
                override fun actionPerformed(event: AnActionEvent) = showSettings()
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("AgorToolbar", actions, true).apply {
            targetComponent = component
        }

        splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(tree), inspector).apply {
            dividerSize = JBUI.scale(3)
            border = JBUI.Borders.empty()
        }
        component.add(toolbar.component, BorderLayout.NORTH)
        component.add(splitPane, BorderLayout.CENTER)
        applySplitLayout()
        showEmptyInspector()
    }

    fun refresh(interactive: Boolean = true, select: AgorNodeKey? = null) {
        val agorUrl = settings.state.agorUrl
        val selectionToRestore = select ?: selectedNodeRef()?.key()
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
                        if (interactive || snapshot == AgorSnapshot()) showConnectionError(error)
                    }
                }
        }
    }

    private fun renderSnapshot(
        loaded: AgorSnapshot,
        selectionToRestore: AgorNodeKey?,
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
                AgorTreeNodeKind.BOARD -> showBoard(value.id, value.label)
            }
            else -> showEmptyInspector()
        }
    }

    private fun showBoard(boardId: String, name: String) {
        val worktreeCount = snapshot.worktrees.count { it.boardId == boardId || (boardId == "unassigned" && it.boardId.isNullOrBlank()) }
        val sessionCount = snapshot.sessions.count { session ->
            snapshot.worktrees.any { it.worktreeId == session.worktreeId && (it.boardId == boardId || (boardId == "unassigned" && it.boardId.isNullOrBlank())) }
        }
        val panel = detailPanel("Board", name, listOf("Worktrees: $worktreeCount", "Sessions: $sessionCount"))
        panel.add(Box.createVerticalStrut(JBUI.scale(14)))
        panel.add(buttonRow(
            actionButton("New Worktree", AgorIcons.NewWorktree) { showNewWorktree(boardId.takeUnless { it == "unassigned" }) },
        ))
        replaceInspector(panel)
    }

    private fun showWorktree(worktree: AgorWorktree) {
        val sessions = snapshot.sessions.filter { it.worktreeId == worktree.worktreeId }
        val panel = detailPanel(
            "Worktree",
            worktree.name,
            listOf(
                "Branch/ref: ${worktree.ref ?: "-"}",
                "Sessions: ${sessions.size}",
                "Path: ${worktree.path}",
            ),
        )
        panel.add(Box.createVerticalStrut(JBUI.scale(14)))
        panel.add(buttonRow(
            actionButton("New Session", AgorIcons.NewSession) { showNewSession(worktree) },
            actionButton("Open Path", AgorIcons.OpenPath) {
                    val file = File(worktree.path)
                    if (file.exists()) {
                        FileEditorManager.getInstance(project).openFile(com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return@actionButton, true)
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
            actionButton("Send", AgorIcons.Send) {
                    val text = prompt.text.trim()
                    if (text.isNotEmpty()) runClientAction(AgorNodeKey(AgorTreeNodeKind.SESSION, session.sessionId)) {
                        promptSession(session.sessionId, text)
                    }
            },
            actionButton("Stop", AgorIcons.Stop) { runClientAction { stopSession(session.sessionId) } },
            actionButton("Fork", AgorIcons.Fork) { showForkSession(session) },
            actionButton("Spawn", AgorIcons.Spawn) { showSpawnSession(session) },
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
            actionButton("Approve Once", AgorIcons.Approve) {
                    runClientAction {
                        decidePermission(sessionId, permission.requestId, permission.taskId, true, AgorPermissionScope.ONCE)
                    }
            },
            actionButton("Approve Project", AgorIcons.Approve) {
                    runClientAction {
                        decidePermission(sessionId, permission.requestId, permission.taskId, true, AgorPermissionScope.PROJECT)
                    }
            },
            actionButton("Deny", AgorIcons.Deny) {
                    runClientAction {
                        decidePermission(sessionId, permission.requestId, permission.taskId, false, AgorPermissionScope.ONCE)
                    }
            },
        ))
        return panel
    }

    private fun showNewWorktree(selectedBoardId: String? = selectedBoardId()) {
        val agorUrl = settings.state.agorUrl
        ApplicationManager.getApplication().executeOnPooledThread {
            val client = AgorApiClient(agorUrl, settings.agorToken)
            runCatching { client.loadRepos() }
                .onSuccess { repos ->
                    SwingUtilities.invokeLater {
                        val dialog = AgorNewWorktreeDialog(project, repos, snapshot.boards, selectedBoardId)
                        if (dialog.showAndGet()) {
                            val input = dialog.input()
                            runClientAction(AgorNodeKey(AgorTreeNodeKind.BOARD, input.boardId ?: "unassigned")) {
                                val created = createWorktree(
                                    AgorCreateWorktreeRequest(
                                        repoId = input.repoId,
                                        boardId = input.boardId,
                                        name = input.name,
                                        sourceBranch = input.sourceBranch,
                                        createBranch = input.createBranch,
                                        pullLatest = input.pullLatest,
                                    ),
                                )
                                refreshSelectionAfterAction = AgorNodeKey(AgorTreeNodeKind.WORKTREE, created.worktreeId)
                            }
                        }
                    }
                }
                .onFailure { error ->
                    SwingUtilities.invokeLater {
                        LOG.warn("Could not load Agor repos", error)
                        showActionError(error)
                    }
                }
        }
    }

    private var refreshSelectionAfterAction: AgorNodeKey? = null

    private fun showNewSession(worktree: AgorWorktree) {
        val dialog = AgorNewSessionDialog(project, promptRequired = false, titleText = "New Agor Session")
        if (!dialog.showAndGet()) return
        val input = dialog.input()
        runClientAction(AgorNodeKey(AgorTreeNodeKind.WORKTREE, worktree.worktreeId)) {
            val created = createSession(
                AgorCreateSessionRequest(
                    worktreeId = worktree.worktreeId,
                    agenticTool = input.agenticTool,
                    title = input.title,
                    initialPrompt = input.prompt,
                ),
            )
            refreshSelectionAfterAction = AgorNodeKey(AgorTreeNodeKind.SESSION, created.sessionId)
        }
    }

    private fun showForkSession(session: AgorSession) {
        val dialog = AgorNewSessionDialog(
            project,
            promptRequired = true,
            titleText = "Fork Agor Session",
            defaultAgent = session.agenticTool,
            agentEnabled = false,
        )
        if (!dialog.showAndGet()) return
        val prompt = dialog.input().prompt.orEmpty()
        runClientAction(AgorNodeKey(AgorTreeNodeKind.SESSION, session.sessionId)) {
            val created = forkSession(session.sessionId, prompt)
            refreshSelectionAfterAction = AgorNodeKey(AgorTreeNodeKind.SESSION, created.sessionId)
        }
    }

    private fun showSpawnSession(session: AgorSession) {
        val dialog = AgorNewSessionDialog(project, promptRequired = true, titleText = "Spawn Agor Session", defaultAgent = session.agenticTool)
        if (!dialog.showAndGet()) return
        val input = dialog.input()
        runClientAction(AgorNodeKey(AgorTreeNodeKind.SESSION, session.sessionId)) {
            val created = spawnSession(
                AgorSpawnSessionRequest(
                    parentSessionId = session.sessionId,
                    prompt = input.prompt.orEmpty(),
                    title = input.title,
                    agenticTool = input.agenticTool,
                ),
            )
            refreshSelectionAfterAction = AgorNodeKey(AgorTreeNodeKind.SESSION, created.sessionId)
        }
    }

    private fun runClientAction(selectFallback: AgorNodeKey? = null, action: AgorApiClient.() -> Unit) {
        val agorUrl = settings.state.agorUrl
        ApplicationManager.getApplication().executeOnPooledThread {
            val client = AgorApiClient(agorUrl, settings.agorToken)
            runCatching { client.action() }
                .onSuccess {
                    SwingUtilities.invokeLater {
                        val selection = refreshSelectionAfterAction ?: selectFallback
                        refreshSelectionAfterAction = null
                        refresh(select = selection)
                    }
                }
                .onFailure { error ->
                    SwingUtilities.invokeLater {
                        refreshSelectionAfterAction = null
                        LOG.warn("Agor action failed", error)
                        showActionError(error)
                    }
                }
        }
    }

    private fun showEmptyInspector() {
        val activeSessions = snapshot.sessions.count { it.status == AgorSessionStatus.RUNNING || it.status == AgorSessionStatus.QUEUED }
        val pendingPermissions = snapshot.permissionRequests.size
        replaceInspector(
            detailPanel(
                "Agor",
                "Workspace",
                listOf(
                    "Boards: ${snapshot.boards.size}",
                    "Worktrees: ${snapshot.worktrees.size}",
                    "Sessions: ${snapshot.sessions.size}",
                    "Active: $activeSessions",
                    "Pending permissions: $pendingPermissions",
                ),
            ).apply {
                add(Box.createVerticalStrut(JBUI.scale(14)))
                add(buttonRow(
                    actionButton("New Worktree", AgorIcons.NewWorktree) { showNewWorktree() },
                ))
            },
        )
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
            actionButton("Configure", AgorIcons.Settings) { showSettings() },
            actionButton("Retry", AgorIcons.Refresh) { refresh() },
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
            applySplitLayout()
            socketClient?.disconnect()
            socketClient = null
            socketConnectionKey = null
            refresh()
        }
    }

    private fun cycleSplitLayout() {
        val next = when (AgorSplitLayoutMode.fromId(settings.state.splitLayoutMode)) {
            AgorSplitLayoutMode.AUTO -> AgorSplitLayoutMode.STACKED
            AgorSplitLayoutMode.STACKED -> AgorSplitLayoutMode.SIDE_BY_SIDE
            AgorSplitLayoutMode.SIDE_BY_SIDE -> AgorSplitLayoutMode.AUTO
        }
        settings.state.splitLayoutMode = next.id
        applySplitLayout()
    }

    private fun applySplitLayout() {
        val layout = AgorSplitLayout.resolve(settings.state.splitLayoutMode, toolWindow.anchor)
        splitPane.orientation = layout.splitPaneOrientation
        splitPane.resizeWeight = layout.resizeWeight
        tree.minimumSize = layout.treeMinimumSize
        inspector.border = if (layout.splitPaneOrientation == JSplitPane.HORIZONTAL_SPLIT) {
            JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0)
        } else {
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
        }
        splitPane.revalidate()
        splitPane.repaint()
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

    private fun actionButton(text: String, icon: javax.swing.Icon, action: () -> Unit): JButton =
        JButton(text, icon).apply {
            addActionListener { action() }
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
            refresh(interactive = false)
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun selectedWorktree(): AgorWorktree? {
        val ref = selectedNodeRef() ?: return null
        return when (ref.kind) {
            AgorTreeNodeKind.WORKTREE -> snapshot.worktrees.firstOrNull { it.worktreeId == ref.id }
            AgorTreeNodeKind.SESSION -> snapshot.sessions.firstOrNull { it.sessionId == ref.id }
                ?.let { session -> snapshot.worktrees.firstOrNull { it.worktreeId == session.worktreeId } }
            AgorTreeNodeKind.BOARD -> null
        }
    }

    private fun selectedBoardId(): String? {
        val ref = selectedNodeRef() ?: return null
        return when (ref.kind) {
            AgorTreeNodeKind.BOARD -> ref.id.takeUnless { it == "unassigned" }
            AgorTreeNodeKind.WORKTREE -> snapshot.worktrees.firstOrNull { it.worktreeId == ref.id }?.boardId
            AgorTreeNodeKind.SESSION -> snapshot.sessions.firstOrNull { it.sessionId == ref.id }
                ?.let { session -> snapshot.worktrees.firstOrNull { it.worktreeId == session.worktreeId }?.boardId }
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

private class AgorTreeRenderer(
    private val snapshotProvider: () -> AgorSnapshot,
) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode
        val ref = node?.userObject as? AgorNodeRef
        if (ref == null) {
            append(value?.toString().orEmpty())
            return
        }

        val snapshot = snapshotProvider()
        append(ref.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        when (ref.kind) {
            AgorTreeNodeKind.BOARD -> {
                val worktreeCount = snapshot.worktrees.count {
                    it.boardId == ref.id || (ref.id == "unassigned" && it.boardId.isNullOrBlank())
                }
                append("  $worktreeCount worktrees", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            AgorTreeNodeKind.WORKTREE -> {
                val worktree = snapshot.worktrees.firstOrNull { it.worktreeId == ref.id }
                val sessions = snapshot.sessions.filter { it.worktreeId == ref.id }
                val active = sessions.count { it.status == AgorSessionStatus.RUNNING || it.status == AgorSessionStatus.QUEUED }
                if (!worktree?.ref.isNullOrBlank()) append("  ${worktree?.ref}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (active > 0) append("  $active active", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }
            AgorTreeNodeKind.SESSION -> {
                val session = snapshot.sessions.firstOrNull { it.sessionId == ref.id }
                val permissionCount = snapshot.permissionRequests.count { it.sessionId == ref.id }
                if (session != null) append("  ${session.status.name.lowercase()}", statusAttributes(session.status))
                if (permissionCount > 0) append("  permission", SimpleTextAttributes.ERROR_ATTRIBUTES)
            }
        }
    }

    private fun statusAttributes(status: AgorSessionStatus): SimpleTextAttributes =
        when (status) {
            AgorSessionStatus.RUNNING, AgorSessionStatus.QUEUED -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            AgorSessionStatus.FAILED -> SimpleTextAttributes.ERROR_ATTRIBUTES
            else -> SimpleTextAttributes.GRAYED_ATTRIBUTES
        }
}

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
