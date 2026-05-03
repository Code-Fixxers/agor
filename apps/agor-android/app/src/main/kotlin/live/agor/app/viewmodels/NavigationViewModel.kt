package live.agor.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import live.agor.app.AppContainer
import live.agor.app.data.SidebarCache
import live.agor.app.models.Board
import live.agor.app.models.Session
import live.agor.app.models.SessionStatus
import live.agor.app.models.Worktree
import live.agor.app.ui.nav.SidebarRow
import live.agor.app.ui.nav.SidebarRowFlattener
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

/**
 * Owns the sidebar tree (boards → worktrees → sessions) plus Important and Needs Attention
 * sections. Restores from cache on launch and refreshes from the API. Polls every 45s.
 *
 * `isLoading` / `errorMessage` are intentionally **not** in [State] — they live in [loadState].
 * A 45 s polling refresh that flips loading would otherwise change `state` identity and
 * invalidate every downstream `remember(state, …)` block (sidebar row flatten, etc.).
 */
class NavigationViewModel(private val container: AppContainer) : ViewModel() {

    @androidx.compose.runtime.Immutable
    data class State(
        val boards: List<Board> = emptyList(),
        val worktreesByBoard: Map<String, List<Worktree>> = emptyMap(),
        val sessionsByWorktree: Map<String, List<Session>> = emptyMap(),
        val sessions: List<Session> = emptyList(),
        val favorites: Set<String> = emptySet(),
    )

    @androidx.compose.runtime.Immutable
    data class LoadState(
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _loadState = MutableStateFlow(LoadState())
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private val _expandedBoards = MutableStateFlow<Set<String>>(emptySet())
    val expandedBoards: StateFlow<Set<String>> = _expandedBoards.asStateFlow()

    private val _expandedWorktrees = MutableStateFlow<Set<String>>(emptySet())
    val expandedWorktrees: StateFlow<Set<String>> = _expandedWorktrees.asStateFlow()

    private val rowFlattener = SidebarRowFlattener()

    /**
     * Pre-flattened sidebar rows. Derived off-Main on [Dispatchers.Default] so
     * the importance/attention scans, board → worktree → session walk, and
     * per-worktree sort no longer block the UI thread on every patch. The
     * `WhileSubscribed(5_000)` window keeps the result alive across short
     * navigations (drawer hide/show, screen rotation).
     */
    val rows: StateFlow<List<SidebarRow>> = combine(
        _state,
        _expandedBoards,
        _expandedWorktrees,
    ) { s, eb, ew ->
        rowFlattener.flatten(s, eb, ew, container.hermesClient.isConfigured)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var pollJob: Job? = null
    private var cacheSaveJob: Job? = null

    init {
        container.socket.onSessionPatched { patched ->
            viewModelScope.launch { applySessionPatch(patched) }
        }
    }

    fun start() {
        viewModelScope.launch {
            val cached = container.sidebarCache.load()
            if (cached != null) {
                _state.value = _state.value.copy(
                    boards = cached.boards,
                    worktreesByBoard = cached.worktrees.groupBy { it.boardId ?: "" },
                    sessionsByWorktree = cached.sessions.groupBy { it.worktreeId },
                    sessions = cached.sessions,
                )
            }
            refresh()
            startPolling()
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun toggleBoard(boardId: String) {
        val cur = _expandedBoards.value
        _expandedBoards.value = if (cur.contains(boardId)) cur - boardId else cur + boardId
    }

    fun toggleWorktree(worktreeId: String) {
        val cur = _expandedWorktrees.value
        _expandedWorktrees.value = if (cur.contains(worktreeId)) cur - worktreeId else cur + worktreeId
    }

    fun toggleFavorite(sessionId: String) {
        val cur = _state.value.favorites
        _state.value = _state.value.copy(
            favorites = if (cur.contains(sessionId)) cur - sessionId else cur + sessionId,
        )
    }

    /**
     * Targeted update — touch only the affected entries in `sessionsByWorktree`,
     * not a full `groupBy` over all sessions. Critical for sidebar perf when an
     * active board patches sessions multiple times per minute.
     */
    private fun applySessionPatch(patched: Session) {
        val current = _state.value
        val sessions = current.sessions
        val idx = sessions.indexOfFirst { it.sessionId == patched.sessionId }
        val oldWtId = if (idx >= 0) sessions[idx].worktreeId else null
        val newWtId = patched.worktreeId

        val newSessions: List<Session> = if (idx >= 0) {
            sessions.toMutableList().apply { set(idx, patched) }
        } else {
            sessions + patched
        }

        val newByWt = current.sessionsByWorktree.toMutableMap()
        if (oldWtId != null && oldWtId != newWtId) {
            // Session moved to a different worktree — remove from old slot, add to new.
            newByWt[oldWtId] = newByWt[oldWtId].orEmpty().filter { it.sessionId != patched.sessionId }
            newByWt[newWtId] = newByWt[newWtId].orEmpty() + patched
        } else {
            // Same worktree — replace within the slot, or append if not present.
            val list = newByWt[newWtId].orEmpty()
            val localIdx = list.indexOfFirst { it.sessionId == patched.sessionId }
            newByWt[newWtId] = if (localIdx >= 0) {
                list.toMutableList().apply { set(localIdx, patched) }
            } else {
                list + patched
            }
        }

        _state.value = current.copy(sessions = newSessions, sessionsByWorktree = newByWt)
        scheduleCacheSave()

        if (current.favorites.contains(patched.sessionId) &&
            patched.status == SessionStatus.IDLE
        ) {
            container.notifications.notifySessionIdle(
                sessionId = patched.sessionId,
                title = patched.displayTitle,
                sessionUrl = patched.url,
            )
        }
    }

    suspend fun refresh() {
        _loadState.value = LoadState(isLoading = true, errorMessage = null)
        try {
            val boards = container.client.listBoards()
            val worktrees = container.client.listWorktrees()
            val sessions = container.client.listSessions()
            _state.value = _state.value.copy(
                boards = boards,
                worktreesByBoard = worktrees.groupBy { it.boardId ?: "" },
                sessionsByWorktree = sessions.groupBy { it.worktreeId },
                sessions = sessions,
            )
            _loadState.value = LoadState(isLoading = false, errorMessage = null)
            container.sidebarCache.save(
                SidebarCache.Snapshot(
                    System.currentTimeMillis(),
                    boards,
                    worktrees,
                    sessions,
                ),
            )
        } catch (t: Throwable) {
            AppLogger.log("Sidebar refresh failed: ${t.message}", LogLevel.WARNING, "Nav")
            _loadState.value = LoadState(isLoading = false, errorMessage = t.message)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(45_000)
                refresh()
            }
        }
    }

    private fun scheduleCacheSave() {
        cacheSaveJob?.cancel()
        cacheSaveJob = viewModelScope.launch {
            delay(500)
            val s = _state.value
            container.sidebarCache.save(
                SidebarCache.Snapshot(
                    System.currentTimeMillis(),
                    s.boards,
                    s.worktreesByBoard.values.flatten(),
                    s.sessions,
                ),
            )
        }
    }
}
