package live.agor.jetbrains.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import live.agor.jetbrains.client.AgorApiClient
import live.agor.jetbrains.client.AgorCreateSessionRequest
import live.agor.jetbrains.client.AgorCreateWorktreeRequest
import live.agor.jetbrains.client.AgorMessage
import live.agor.jetbrains.client.AgorMessageRole
import live.agor.jetbrains.client.AgorPermissionRequest
import live.agor.jetbrains.client.AgorPermissionScope
import live.agor.jetbrains.client.AgorSession
import live.agor.jetbrains.client.AgorSessionStatus
import live.agor.jetbrains.client.AgorSocketClient
import live.agor.jetbrains.client.AgorSocketEvent
import live.agor.jetbrains.client.AgorSpawnSessionRequest
import live.agor.jetbrains.client.AgorSnapshot
import live.agor.jetbrains.client.AgorWorktree
import live.agor.jetbrains.settings.AgorSettings
import live.agor.jetbrains.settings.AgorSettingsDialog
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
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
import javax.swing.ScrollPaneConstants
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
    private val searchField = JBTextField()
    private val listSummary = AgorTheme.label("", 11f, color = AgorTheme.TextMuted)
    private val filterButtons = mutableMapOf<AgorObjectFilterMode, JButton>()
    private val inspector = JPanel(BorderLayout(8, 8))
    private lateinit var splitPane: JSplitPane
    private var snapshot = AgorSnapshot()
    private var objectFilterMode = AgorObjectFilterMode.ALL
    private var socketClient: AgorSocketClient? = null
    private var socketConnectionKey: Pair<String, String?>? = null
    private var socketRefreshTimer: Timer? = null
    private val promptDrafts = mutableMapOf<String, String>()
    private var activePromptSessionId: String? = null
    private var activePrompt: JBTextArea? = null
    private var promptFocusSessionToRestore: String? = null
    private val sessionMessages = mutableMapOf<String, List<AgorMessage>>()
    private val sessionMessageErrors = mutableMapOf<String, String>()
    private val loadingSessionMessages = mutableSetOf<String>()
    private val streamingMessages = mutableMapOf<String, LiveSessionMessage>()
    private var activeConversationScroll: JBScrollPane? = null
    val component: JPanel = JPanel(BorderLayout()).apply {
        background = AgorTheme.SurfaceBase
    }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.border = JBUI.Borders.empty(6, 8)
        tree.background = AgorTheme.SurfacePanel
        tree.foreground = AgorTheme.TextPrimary
        tree.addTreeSelectionListener { showSelection() }
        tree.cellRenderer = AgorTreeRenderer { snapshot }
        TreeSpeedSearch(tree)

        AgorTheme.styleInput(searchField)
        searchField.toolTipText = "Search boards, worktrees, sessions"
        searchField.emptyText.text = "Search"
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = refilterObjectList()
            override fun removeUpdate(event: DocumentEvent) = refilterObjectList()
            override fun changedUpdate(event: DocumentEvent) = refilterObjectList()
        })

        inspector.background = AgorTheme.SurfaceBase

        splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createObjectList(), inspector).apply {
            dividerSize = JBUI.scale(3)
            border = JBUI.Borders.empty()
            background = AgorTheme.BorderSubtle
        }

        component.add(createRail(), BorderLayout.WEST)
        component.add(splitPane, BorderLayout.CENTER)
        applySplitLayout()
        showEmptyInspector()
    }

    private fun createRail(): JPanel =
        AgorTheme.panel(AgorTheme.SurfaceBase).apply {
            layout = BorderLayout()
            preferredSize = Dimension(JBUI.scale(48), 1)
            border = AgorTheme.RightBorder

            val top = AgorTheme.panel(AgorTheme.SurfaceBase).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(8, 6)
                add(railButton("Sessions", AgorIcons.Send, active = true) { })
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(railButton("Refresh", AgorIcons.Refresh) { refresh() })
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(railButton("New worktree", AgorIcons.NewWorktree) { showNewWorktree() })
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(railButton("New session", AgorIcons.NewSession) {
                    selectedWorktree()?.let { showNewSession(it) } ?: showText("Agor", "Select a worktree first.")
                })
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(railButton("Layout", AgorIcons.Layout) { cycleSplitLayout() })
            }
            val bottom = AgorTheme.panel(AgorTheme.SurfaceBase).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(8, 6)
                add(railButton("Settings", AgorIcons.Settings) { showSettings() })
            }
            add(top, BorderLayout.NORTH)
            add(bottom, BorderLayout.SOUTH)
        }

    private fun createObjectList(): JPanel =
        AgorTheme.panel(AgorTheme.SurfacePanel).apply {
            layout = BorderLayout(0, 10)
            border = JBUI.Borders.empty(12, 12)
            preferredSize = Dimension(JBUI.scale(310), 1)
            minimumSize = Dimension(JBUI.scale(220), JBUI.scale(180))

            val header = AgorTheme.panel(AgorTheme.SurfacePanel).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(AgorTheme.label("Agor", 15f, bold = true, color = AgorTheme.TextPrimary).leftAligned())
                add(Box.createVerticalStrut(JBUI.scale(3)))
                add(listSummary.leftAligned())
                add(Box.createVerticalStrut(JBUI.scale(10)))
                add(searchField.leftAligned())
                add(Box.createVerticalStrut(JBUI.scale(10)))
                add(filterRow().leftAligned())
            }
            val treeScroll = JBScrollPane(tree).apply {
                border = JBUI.Borders.empty()
                viewport.background = AgorTheme.SurfacePanel
                background = AgorTheme.SurfacePanel
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }
            add(header, BorderLayout.NORTH)
            add(treeScroll, BorderLayout.CENTER)
        }

    private fun filterRow(): JPanel =
        AgorTheme.panel(AgorTheme.SurfacePanel).apply {
            layout = FlowLayout(FlowLayout.LEFT, 6, 0)
            AgorObjectFilterMode.entries.forEach { mode ->
                val button = pillButton(mode.label, objectFilterMode == mode) {
                    objectFilterMode = mode
                    updateFilterButtons()
                    refilterObjectList()
                }
                filterButtons[mode] = button
                add(button)
            }
        }

    private fun updateFilterButtons() {
        filterButtons.forEach { (mode, button) ->
            stylePillButton(button, objectFilterMode == mode)
        }
    }

    private fun railButton(label: String, icon: javax.swing.Icon, active: Boolean = false, action: () -> Unit): JButton =
        JButton(icon).apply {
            toolTipText = label
            preferredSize = Dimension(JBUI.scale(36), JBUI.scale(36))
            maximumSize = preferredSize
            alignmentX = Component.CENTER_ALIGNMENT
            AgorTheme.styleIconButton(this, active)
            addActionListener { action() }
        }

    private fun pillButton(text: String, selected: Boolean, action: () -> Unit): JButton =
        JButton(text).apply {
            stylePillButton(this, selected)
            addActionListener { action() }
        }

    private fun stylePillButton(button: JButton, selected: Boolean) {
        button.isOpaque = true
        button.background = if (selected) AgorTheme.AccentMuted else AgorTheme.SurfaceRaised
        button.foreground = if (selected) AgorTheme.TextPrimary else AgorTheme.TextSecondary
        button.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(if (selected) AgorTheme.Accent else AgorTheme.BorderSubtle),
            JBUI.Borders.empty(4, 9),
        )
        button.isFocusPainted = false
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
        updateListSummary()
        renderObjectTree(selectionToRestore, expandedToRestore, promptFocusToRestore)
    }

    private fun refilterObjectList() {
        updateListSummary()
        renderObjectTree(selectedNodeRef()?.key(), expandedNodeRefs(), focusedPromptSessionId())
    }

    private fun updateListSummary() {
        val activeSessions = snapshot.sessions.count { it.status == AgorSessionStatus.RUNNING || it.status == AgorSessionStatus.QUEUED }
        val permissions = snapshot.permissionRequests.size
        listSummary.text = "${snapshot.worktrees.size} worktrees  /  ${snapshot.sessions.size} sessions  /  $activeSessions active  /  $permissions approvals"
    }

    private fun renderObjectTree(
        selectionToRestore: AgorNodeKey?,
        expandedToRestore: Set<AgorNodeKey>,
        promptFocusToRestore: String?,
    ) {
        val visible = AgorObjectListFilter.apply(snapshot, searchField.text, objectFilterMode)
        val root = toSwingTree(AgorTreeModelBuilder().build(visible.boards, visible.worktrees, visible.sessions))
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
        val body = messageStack().apply {
            add(infoCard("Board overview", listOf("Worktrees: $worktreeCount", "Sessions: $sessionCount")))
        }
        replaceInspector(mainPane(
            title = name,
            context = "Board",
            meta = listOf("$worktreeCount worktrees", "$sessionCount sessions"),
            actions = buttonRow(actionButton("New Worktree", AgorIcons.NewWorktree) { showNewWorktree(boardId.takeUnless { it == "unassigned" }) }),
            body = body,
        ))
    }

    private fun showWorktree(worktree: AgorWorktree) {
        val sessions = snapshot.sessions.filter { it.worktreeId == worktree.worktreeId }
        val body = messageStack().apply {
            add(infoCard(
                "Worktree context",
                listOf(
                    "Branch/ref: ${worktree.ref ?: "-"}",
                    "Sessions: ${sessions.size}",
                    "Path: ${worktree.path}",
                ),
            ))
        }
        replaceInspector(mainPane(
            title = worktree.name,
            context = "Worktree",
            meta = listOf(worktree.ref ?: "No branch", "${sessions.size} sessions"),
            actions = buttonRow(
                actionButton("New Session", AgorIcons.NewSession) { showNewSession(worktree) },
                actionButton("Open Path", AgorIcons.OpenPath) {
                        val file = File(worktree.path)
                        if (file.exists()) {
                            val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return@actionButton
                            if (virtualFile.isDirectory) {
                                ProjectView.getInstance(project).select(null, virtualFile, true)
                            } else {
                                FileEditorManager.getInstance(project).openFile(virtualFile, true)
                            }
                        }
                },
            ),
            body = body,
        ))
    }

    private fun showSession(session: AgorSession) {
        ensureSessionMessages(session.sessionId)
        val prompt = JBTextArea(4, 32).apply {
            text = promptDrafts[session.sessionId].orEmpty()
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(8)
            background = AgorTheme.SurfaceRaised
            foreground = AgorTheme.TextPrimary
            caretColor = AgorTheme.Accent
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

        val body = messageStack().apply {
            val messages = sessionMessages[session.sessionId]
            val error = sessionMessageErrors[session.sessionId]
            when {
                messages != null && messages.isNotEmpty() -> {
                    messages.forEach { add(messageCard(it)) }
                }
                messages != null -> add(infoCard("No messages yet", listOf("This session has no persisted conversation messages.")))
                error != null -> add(infoCard("Could not load conversation", listOf(error)))
                else -> add(infoCard("Loading conversation", listOf("Fetching previous messages from Agor...")))
            }
            streamingMessages.values
                .filter { it.sessionId == session.sessionId }
                .sortedWith(compareBy<LiveSessionMessage> { it.index ?: Int.MAX_VALUE }.thenBy { it.messageId ?: it.sessionId })
                .forEach { add(streamingMessageCard(it)) }
            snapshot.permissionRequests
                .filter { it.sessionId == session.sessionId }
                .forEach { add(permissionPanel(session.sessionId, it)) }
        }
        val composer = composer(prompt, session)
        val shouldScrollToEnd = sessionMessages.containsKey(session.sessionId) ||
            streamingMessages.values.any { it.sessionId == session.sessionId }
        replaceInspector(mainPane(
            title = session.title,
            context = "Agor session",
            meta = listOf(session.status.name.lowercase(), session.agenticTool),
            actions = buttonRow(
                actionButton("Start", AgorIcons.ScrollStart) { scrollConversationToStart() },
                actionButton("End", AgorIcons.ScrollEnd) { scrollConversationToEnd() },
                actionButton("Stop", AgorIcons.Stop) { runClientAction { stopSession(session.sessionId) } },
                actionButton("Fork", AgorIcons.Fork) { showForkSession(session) },
                actionButton("Spawn", AgorIcons.Spawn) { showSpawnSession(session) },
            ),
            body = body,
            footer = composer,
            onBodyScroll = { scroll ->
                activeConversationScroll = scroll
                if (shouldScrollToEnd) scrollConversationToEnd(scroll)
            },
        ))
        if (promptFocusSessionToRestore == session.sessionId) {
            promptFocusSessionToRestore = null
            SwingUtilities.invokeLater {
                prompt.requestFocusInWindow()
                prompt.caretPosition = prompt.text.length
            }
        }
    }

    private fun composer(prompt: JBTextArea, session: AgorSession): JPanel {
        val send = actionButton("Send", AgorIcons.Send, primary = true) {
            val text = prompt.text.trim()
            if (text.isNotEmpty()) runClientAction(AgorNodeKey(AgorTreeNodeKind.SESSION, session.sessionId)) {
                SwingUtilities.invokeLater {
                    sessionMessages.remove(session.sessionId)
                    sessionMessageErrors.remove(session.sessionId)
                }
                promptSession(session.sessionId, text)
                SwingUtilities.invokeLater {
                    promptDrafts.remove(session.sessionId)
                    prompt.text = ""
                }
            }
        }
        return AgorTheme.panel(AgorTheme.SurfaceBase).apply {
            layout = BorderLayout(8, 0)
            border = JBUI.Borders.empty(10, 14, 14, 14)
            val promptScroll = JBScrollPane(prompt).apply {
                border = JBUI.Borders.compound(AgorTheme.PanelBorder, JBUI.Borders.empty())
                preferredSize = Dimension(JBUI.scale(420), JBUI.scale(96))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(120))
                viewport.background = AgorTheme.SurfaceRaised
            }
            add(promptScroll, BorderLayout.CENTER)
            add(send, BorderLayout.EAST)
        }
    }

    private fun permissionPanel(sessionId: String, permission: AgorPermissionRequest): JPanel {
        val panel = infoCard(
            "Permission Required",
            listOf(
                "Tool: ${permission.toolName}",
                "Request: ${permission.requestId}",
                "Task: ${permission.taskId ?: "-"}",
                "Input: ${permission.toolInputJson}",
            ),
        )
        panel.add(buttonRow(
            actionButton("Approve Once", AgorIcons.Approve, primary = true) {
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

    private fun ensureSessionMessages(sessionId: String) {
        if (sessionMessages.containsKey(sessionId) || sessionMessageErrors.containsKey(sessionId) || !loadingSessionMessages.add(sessionId)) return
        val agorUrl = settings.state.agorUrl
        ApplicationManager.getApplication().executeOnPooledThread {
            val client = AgorApiClient(agorUrl, settings.agorToken)
            runCatching { client.loadSessionMessages(sessionId) }
                .onSuccess { loaded ->
                    SwingUtilities.invokeLater {
                        loadingSessionMessages.remove(sessionId)
                        sessionMessageErrors.remove(sessionId)
                        sessionMessages[sessionId] = loaded
                        val persistedIds = loaded.map { it.messageId }.toSet()
                        streamingMessages.entries.removeIf { (_, live) ->
                            live.sessionId == sessionId && live.messageId != null && live.messageId in persistedIds
                        }
                        rerenderSessionIfSelected(sessionId)
                    }
                }
                .onFailure { error ->
                    SwingUtilities.invokeLater {
                        loadingSessionMessages.remove(sessionId)
                        sessionMessageErrors[sessionId] = error.userFacingMessage("Could not load conversation")
                        LOG.warn("Could not load Agor session messages", error)
                        rerenderSessionIfSelected(sessionId)
                    }
                }
        }
    }

    private fun showEmptyInspector() {
        val activeSessions = snapshot.sessions.count { it.status == AgorSessionStatus.RUNNING || it.status == AgorSessionStatus.QUEUED }
        val pendingPermissions = snapshot.permissionRequests.size
        val body = messageStack().apply {
            add(infoCard(
                "Workspace",
                listOf(
                    "Boards: ${snapshot.boards.size}",
                    "Worktrees: ${snapshot.worktrees.size}",
                    "Sessions: ${snapshot.sessions.size}",
                    "Active: $activeSessions",
                    "Pending permissions: $pendingPermissions",
                ),
            ))
        }
        replaceInspector(mainPane(
            title = "Agor",
            context = "Connected workspace",
            meta = listOf("${snapshot.worktrees.size} worktrees", "${snapshot.sessions.size} sessions"),
            actions = buttonRow(actionButton("New Worktree", AgorIcons.NewWorktree) { showNewWorktree() }),
            body = body,
        ))
    }

    private fun showText(title: String, body: String) {
        replaceInspector(mainPane(title, "Agor", emptyList(), null, messageStack().apply { add(infoCard(body, emptyList())) }))
    }

    private fun showConnectionError(error: Throwable) {
        replaceInspector(mainPane(
            title = "Connection unavailable",
            context = "Agor",
            meta = listOf(settings.state.agorUrl),
            actions = buttonRow(
                actionButton("Configure", AgorIcons.Settings) { showSettings() },
                actionButton("Retry", AgorIcons.Refresh, primary = true) { refresh() },
            ),
            body = messageStack().apply {
                add(infoCard("Could not load Agor", listOf(error.userFacingMessage("Could not load Agor"))))
            },
        ))
    }

    private fun showActionError(error: Throwable) {
        replaceInspector(mainPane(
            title = "Action failed",
            context = "Agor",
            meta = emptyList(),
            actions = null,
            body = messageStack().apply {
                add(infoCard("Agor action failed", listOf(error.userFacingMessage("Agor action failed"))))
            },
        ))
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
            AgorTheme.LeftBorder
        } else {
            AgorTheme.TopBorder
        }
        splitPane.revalidate()
        splitPane.repaint()
    }

    private fun mainPane(
        title: String,
        context: String,
        meta: List<String>,
        actions: JPanel?,
        body: JPanel,
        footer: JPanel? = null,
        onBodyScroll: ((JBScrollPane) -> Unit)? = null,
    ): JPanel =
        AgorTheme.panel(AgorTheme.SurfaceBase).apply {
            layout = BorderLayout()
            add(chatTopbar(title, context, meta, actions), BorderLayout.NORTH)
            val bodyScroll = JBScrollPane(body).apply {
                border = JBUI.Borders.empty()
                viewport.background = AgorTheme.SurfaceBase
                background = AgorTheme.SurfaceBase
            }
            onBodyScroll?.invoke(bodyScroll)
            add(bodyScroll, BorderLayout.CENTER)
            footer?.let { add(it, BorderLayout.SOUTH) }
        }

    private fun chatTopbar(title: String, context: String, meta: List<String>, actions: JPanel?): JPanel =
        AgorTheme.panel(AgorTheme.SurfaceBase).apply {
            layout = BorderLayout(12, 0)
            border = JBUI.Borders.compound(AgorTheme.TopBorder, JBUI.Borders.empty(12, 16))
            val titleBox = AgorTheme.panel(AgorTheme.SurfaceBase).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(AgorTheme.label(context.uppercase(), 10f, bold = true, color = AgorTheme.TextMuted).leftAligned())
                add(Box.createVerticalStrut(JBUI.scale(4)))
                add(AgorTheme.label(title, 17f, bold = true, color = AgorTheme.TextPrimary).leftAligned())
                if (meta.isNotEmpty()) {
                    add(Box.createVerticalStrut(JBUI.scale(5)))
                    add(AgorTheme.label(meta.joinToString("  /  "), 11f, color = AgorTheme.TextSecondary).leftAligned())
                }
            }
            add(titleBox, BorderLayout.CENTER)
            actions?.let { add(it, BorderLayout.EAST) }
        }

    private fun messageStack(): JPanel =
        AgorTheme.panel(AgorTheme.SurfaceBase).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(14, 16, 16, 16)
        }

    private fun infoCard(title: String, lines: List<String>): JPanel =
        AgorTheme.panel(AgorTheme.SurfaceRaised).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.compound(AgorTheme.PanelBorder, JBUI.Borders.empty(12, 14))
            add(selectableText(title, 12f, AgorTheme.TextPrimary, bold = true).leftAligned())
            if (lines.isNotEmpty()) add(Box.createVerticalStrut(JBUI.scale(8)))
            lines.forEach { add(selectableText(it, 11f, AgorTheme.TextSecondary).leftAligned()) }
            add(Box.createVerticalStrut(JBUI.scale(8)))
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun messageCard(message: AgorMessage): JPanel =
        chatCard(message.role.displayName(), message.timestamp, message.text.ifBlank { message.contentPreview }, message.status)

    private fun streamingMessageCard(message: LiveSessionMessage): JPanel {
        val text = buildString {
            if (message.thinking.isNotBlank()) {
                append("Thinking\n")
                append(message.thinking)
                if (message.text.isNotBlank()) append("\n\n")
            }
            append(message.text.ifBlank { "Streaming..." })
            message.error?.let {
                append("\n\n")
                append(it)
            }
        }
        return chatCard("Agent", message.timestamp, text, if (message.finished) "stream complete" else "streaming")
    }

    private fun chatCard(author: String, timestamp: String?, text: String, status: String?): JPanel =
        AgorTheme.panel(AgorTheme.SurfaceRaised).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.compound(AgorTheme.PanelBorder, JBUI.Borders.empty(12, 14))

            val header = listOfNotNull(author, timestamp, status?.takeIf { it.isNotBlank() }).joinToString("  /  ")
            add(selectableText(header, 11f, AgorTheme.TextMuted, bold = true).leftAligned())
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(selectableText(text, 12f, AgorTheme.TextPrimary).leftAligned())
            add(Box.createVerticalStrut(JBUI.scale(10)))
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun selectableText(text: String, size: Float, color: Color, bold: Boolean = false): JBTextArea =
        JBTextArea(text).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            foreground = color
            caretColor = AgorTheme.Accent
            selectedTextColor = AgorTheme.TextPrimary
            selectionColor = AgorTheme.AccentMuted
            border = JBUI.Borders.empty()
            font = font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, size)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun buttonRow(vararg buttons: JButton): JPanel =
        AgorTheme.panel(AgorTheme.SurfaceBase).apply {
            layout = FlowLayout(FlowLayout.LEFT, 8, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
            buttons.forEach { add(it) }
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun actionButton(text: String, icon: javax.swing.Icon, primary: Boolean = false, action: () -> Unit): JButton =
        JButton(text, icon).apply {
            AgorTheme.styleActionButton(this, primary)
            addActionListener { action() }
        }

    private fun replaceInspector(panel: JPanel) {
        inspector.removeAll()
        inspector.add(panel, BorderLayout.CENTER)
        inspector.revalidate()
        inspector.repaint()
    }

    private fun scrollConversationToStart() {
        activeConversationScroll?.let { scrollConversationToStart(it) }
    }

    private fun scrollConversationToEnd() {
        activeConversationScroll?.let { scrollConversationToEnd(it) }
    }

    private fun scrollConversationToStart(scroll: JBScrollPane) {
        SwingUtilities.invokeLater {
            scroll.verticalScrollBar.value = scroll.verticalScrollBar.minimum
        }
    }

    private fun scrollConversationToEnd(scroll: JBScrollPane) {
        SwingUtilities.invokeLater {
            val bar = scroll.verticalScrollBar
            bar.value = bar.maximum
        }
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
        socketClient = AgorSocketClient(agorUrl, agorToken) { event ->
            SwingUtilities.invokeLater { handleSocketEvent(event) }
        }.also {
            runCatching { it.connect() }
                .onFailure { error ->
                    socketConnectionKey = null
                    LOG.warn("Could not connect Agor socket", error)
                    showConnectionError(error)
                }
        }
    }

    private fun handleSocketEvent(event: AgorSocketEvent) {
        when (event) {
            is AgorSocketEvent.SnapshotChanged -> {
                event.messageId?.let { streamingMessages.remove(it) }
                event.sessionId?.let {
                    sessionMessages.remove(it)
                    sessionMessageErrors.remove(it)
                }
                scheduleBackgroundRefresh()
            }
            is AgorSocketEvent.StreamingStarted -> {
                if (event.sessionId.isBlank()) return
                val key = event.streamKey()
                streamingMessages[key] = streamingMessages[key]?.copy(
                    taskId = event.taskId,
                    index = event.index,
                    timestamp = event.timestamp,
                    finished = false,
                    error = null,
                ) ?: LiveSessionMessage(
                    sessionId = event.sessionId,
                    messageId = event.messageId,
                    taskId = event.taskId,
                    index = event.index,
                    timestamp = event.timestamp,
                )
                rerenderSessionIfSelected(event.sessionId)
            }
            is AgorSocketEvent.StreamingChunk -> {
                val key = event.streamKey()
                val current = streamingMessages[key] ?: LiveSessionMessage(
                    sessionId = event.sessionId,
                    messageId = event.messageId,
                )
                streamingMessages[key] = if (event.thinking) {
                    current.copy(thinking = current.thinking + event.text, finished = false)
                } else {
                    current.copy(text = current.text + event.text, finished = false)
                }
                rerenderSessionIfSelected(event.sessionId)
            }
            is AgorSocketEvent.StreamingEnded -> {
                streamingMessages[event.streamKey()]?.let {
                    streamingMessages[event.streamKey()] = it.copy(finished = true)
                }
                scheduleBackgroundRefresh()
                rerenderSessionIfSelected(event.sessionId)
            }
            is AgorSocketEvent.StreamingFailed -> {
                val key = event.streamKey()
                val current = streamingMessages[key] ?: LiveSessionMessage(
                    sessionId = event.sessionId,
                    messageId = event.messageId,
                )
                streamingMessages[key] = current.copy(error = event.error, finished = true)
                rerenderSessionIfSelected(event.sessionId)
            }
        }
    }

    private fun scheduleBackgroundRefresh() {
        socketRefreshTimer?.stop()
        socketRefreshTimer = Timer(250) {
            socketRefreshTimer = null
            refresh(interactive = false)
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun rerenderSessionIfSelected(sessionId: String) {
        val ref = selectedNodeRef() ?: return
        if (ref.kind != AgorTreeNodeKind.SESSION || ref.id != sessionId) return
        snapshot.sessions.firstOrNull { it.sessionId == sessionId }?.let { showSession(it) }
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

private data class LiveSessionMessage(
    val sessionId: String,
    val messageId: String? = null,
    val taskId: String? = null,
    val index: Int? = null,
    val timestamp: String? = null,
    val text: String = "",
    val thinking: String = "",
    val finished: Boolean = false,
    val error: String? = null,
)

private fun AgorSocketEvent.StreamingStarted.streamKey(): String = messageId ?: sessionId

private fun AgorSocketEvent.StreamingChunk.streamKey(): String = messageId ?: sessionId

private fun AgorSocketEvent.StreamingEnded.streamKey(): String = messageId ?: sessionId

private fun AgorSocketEvent.StreamingFailed.streamKey(): String = messageId ?: sessionId

private fun AgorMessageRole.displayName(): String =
    when (this) {
        AgorMessageRole.USER -> "You"
        AgorMessageRole.ASSISTANT -> "Agent"
        AgorMessageRole.SYSTEM -> "System"
        AgorMessageRole.UNKNOWN -> "Message"
    }

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

private fun <T : JComponent> T.leftAligned(): T =
    apply { alignmentX = Component.LEFT_ALIGNMENT }

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun Throwable.userFacingMessage(fallback: String): String =
    message?.takeIf { it.isNotBlank() } ?: fallback

private fun fallbackPanel(message: String): JPanel =
    AgorTheme.panel(AgorTheme.SurfaceBase).apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(16)
        add(AgorTheme.label(message, 12f, color = AgorTheme.Error), BorderLayout.NORTH)
    }
