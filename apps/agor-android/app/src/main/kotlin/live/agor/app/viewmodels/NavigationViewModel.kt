package live.agor.app.viewmodels

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import live.agor.app.AppContainer
import live.agor.app.data.HermesSession
import live.agor.app.data.SidebarCache
import live.agor.app.data.SidebarExpansionState
import live.agor.app.models.AgenticTool
import live.agor.app.models.Board
import live.agor.app.models.DrawerSessionFilter
import live.agor.app.models.Repo
import live.agor.app.models.Session
import live.agor.app.models.Worktree
import live.agor.app.notifications.SessionTransitionTracker
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
        val reposById: Map<String, Repo> = emptyMap(),
        val sessionsByWorktree: Map<String, List<Session>> = emptyMap(),
        val sessions: List<Session> = emptyList(),
        val hermesSessions: List<HermesSession> = emptyList(),
        val favorites: Set<String> = emptySet(),
        val showArchived: Boolean = false,
        val searchQuery: String = "",
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
    private val transitionTracker = SessionTransitionTracker()

    private val _transitionEvents = MutableSharedFlow<SessionTransitionTracker.Event>(
        extraBufferCapacity = 16,
    )
    val transitionEvents: SharedFlow<SessionTransitionTracker.Event> = _transitionEvents.asSharedFlow()

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
        viewModelScope.launch {
            container.hermesSessions.load()
            container.hermesSessions.sessions.collect { sessions ->
                val filter = DrawerSessionFilter.fromToken(container.tokenStore.drawerSessionFilter)
                _state.value = _state.value.copy(hermesSessions = filterHermesSessionsForDrawer(sessions, filter))
            }
        }
    }

    fun start() {
        viewModelScope.launch {
            val favorites = container.favoriteSessions.load()
            val expansion = container.sidebarExpansion.load()
            _expandedBoards.value = expansion.boardIds
            _expandedWorktrees.value = expansion.worktreeIds
            val cached = container.sidebarCache.load()
            if (cached != null) {
                _state.value = _state.value.copy(
                    boards = cached.boards,
                    worktreesByBoard = cached.worktrees.groupBy { it.boardId ?: "" },
                    sessionsByWorktree = cached.sessions.groupBy { it.worktreeId },
                    sessions = cached.sessions,
                    favorites = favorites,
                )
                transitionTracker.observe(cached.sessions, favorites)
            } else {
                _state.value = _state.value.copy(favorites = favorites)
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
        val next = if (cur.contains(boardId)) cur - boardId else cur + boardId
        _expandedBoards.value = next
        saveExpansion()
    }

    fun toggleWorktree(worktreeId: String) {
        val cur = _expandedWorktrees.value
        val next = if (cur.contains(worktreeId)) cur - worktreeId else cur + worktreeId
        _expandedWorktrees.value = next
        saveExpansion()
    }

    fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    fun toggleFavorite(sessionId: String) {
        val cur = _state.value.favorites
        val updated = if (cur.contains(sessionId)) cur - sessionId else cur + sessionId
        _state.value = _state.value.copy(
            favorites = updated,
        )
        viewModelScope.launch {
            container.favoriteSessions.save(updated)
        }
    }

    fun createSession(worktreeId: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                container.client.createSession(
                    worktreeId = worktreeId,
                    agenticTool = AgenticTool.CLAUDE_CODE,
                )
            }.onSuccess { session ->
                applySessionPatch(session)
                onCreated(session.sessionId)
            }.onFailure { error ->
                AppLogger.log("Create session failed: ${error.message}", LogLevel.ERROR, "Navigation")
            }
        }
    }

    /**
     * Targeted update — touch only the affected entries in `sessionsByWorktree`,
     * not a full `groupBy` over all sessions. Critical for sidebar perf when an
     * active board patches sessions multiple times per minute.
     */
    private fun applySessionPatch(patched: Session) {
        val current = _state.value
        val filter = DrawerSessionFilter.fromToken(container.tokenStore.drawerSessionFilter)
        val patchVisible = filterSessionsForDrawer(listOf(patched), filter).isNotEmpty()
        val sessions = current.sessions
        val idx = sessions.indexOfFirst { it.sessionId == patched.sessionId }
        val oldWtId = if (idx >= 0) sessions[idx].worktreeId else null
        val newWtId = patched.worktreeId

        if (!patchVisible) {
            if (idx < 0) return
            val newByWt = current.sessionsByWorktree.toMutableMap()
            newByWt[oldWtId ?: newWtId] = newByWt[oldWtId ?: newWtId].orEmpty()
                .filter { it.sessionId != patched.sessionId }
            _state.value = current.copy(
                sessions = sessions.filter { it.sessionId != patched.sessionId },
                sessionsByWorktree = newByWt,
            )
            scheduleCacheSave()
            return
        }

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
        dispatchSessionTransitionEvents(transitionTracker.observe(listOf(patched), current.favorites))
    }

    suspend fun refresh() {
        _loadState.value = LoadState(isLoading = true, errorMessage = null)
        val started = SystemClock.elapsedRealtime()
        try {
            val filter = DrawerSessionFilter.fromToken(container.tokenStore.drawerSessionFilter)
            val (boards, worktrees, repos, sessions, hermesSessions) = coroutineScope {
                val boardsDeferred = async { container.client.listBoards() }
                val worktreesDeferred = async { container.client.listWorktrees(includeArchived = filter.includeArchived) }
                val reposDeferred = async { container.client.listRepos() }
                val sessionsDeferred = async {
                    container.client.listSessions(
                        compact = true,
                        includeArchived = filter.includeArchived,
                    )
                }
                val hermesDeferred = async { loadAndSyncHermesSessions(filter) }
                Quint(
                    boardsDeferred.await(),
                    worktreesDeferred.await(),
                    reposDeferred.await(),
                    sessionsDeferred.await(),
                    hermesDeferred.await(),
                )
            }
            val visibleSessions = filterSessionsForDrawer(sessions, filter)
            _state.value = _state.value.copy(
                boards = boards,
                worktreesByBoard = worktrees.groupBy { it.boardId ?: "" },
                reposById = repos.associateBy { it.repoId },
                sessionsByWorktree = visibleSessions.groupBy { it.worktreeId },
                sessions = visibleSessions,
                hermesSessions = hermesSessions,
                showArchived = filter.includeArchived,
            )
            dispatchSessionTransitionEvents(transitionTracker.observe(visibleSessions, _state.value.favorites))
            _loadState.value = LoadState(isLoading = false, errorMessage = null)
            val elapsed = SystemClock.elapsedRealtime() - started
            AppLogger.log(
                "Sidebar refresh loaded ${boards.size} boards, ${worktrees.size} worktrees, " +
                    "${repos.size} repos, ${visibleSessions.size}/${sessions.size} compact sessions and " +
                    "${hermesSessions.size} Hermes sessions in ${elapsed}ms",
                LogLevel.DEBUG,
                "Perf",
            )
            container.sidebarCache.save(
                SidebarCache.Snapshot(
                    System.currentTimeMillis(),
                    boards,
                    worktrees,
                    visibleSessions,
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

    private fun saveExpansion() {
        viewModelScope.launch {
            container.sidebarExpansion.save(
                SidebarExpansionState(
                    boardIds = _expandedBoards.value,
                    worktreeIds = _expandedWorktrees.value,
                ),
            )
        }
    }

    private fun dispatchSessionTransitionEvents(events: List<SessionTransitionTracker.Event>) {
        events.forEach { event ->
            if (event.kind == SessionTransitionTracker.EventKind.FAVORITE_IDLE) {
                val session = event.session
                container.notifications.notifySessionIdle(
                    sessionId = session.sessionId,
                    title = session.displayTitle,
                    sessionUrl = session.url,
                )
            } else {
                _transitionEvents.tryEmit(event)
            }
        }
    }

    private fun filterSessionsForDrawer(
        sessions: List<Session>,
        filter: DrawerSessionFilter,
    ): List<Session> {
        val cutoff = filter.cutoffDays?.let {
            System.currentTimeMillis() - it * 24L * 60L * 60L * 1000L
        }
        return sessions.filter { session ->
            if (!filter.includeArchived && session.archived == true) return@filter false
            if (cutoff == null) return@filter true
            val timestamp = parseEpochMillis(session.lastUpdated)
                ?: parseEpochMillis(session.createdAt)
                ?: return@filter true
            timestamp >= cutoff
        }
    }

    private suspend fun loadAndSyncHermesSessions(filter: DrawerSessionFilter): List<HermesSession> {
        val local = container.hermesSessions.load()
        val sessions = if (container.hermesClient.isConfigured) {
            runCatching {
                container.hermesSessions.syncRemote(container.hermesClient.downloadStoredSessions())
            }.onFailure {
                AppLogger.log("Hermes session import failed: ${it.message}", LogLevel.WARNING, "Hermes")
            }.getOrDefault(local)
        } else {
            local
        }
        return filterHermesSessionsForDrawer(sessions, filter)
    }

    private fun filterHermesSessionsForDrawer(
        sessions: List<HermesSession>,
        filter: DrawerSessionFilter,
    ): List<HermesSession> {
        val cutoff = filter.cutoffDays?.let {
            System.currentTimeMillis() - it * 24L * 60L * 60L * 1000L
        }
        return sessions.filter { session ->
            cutoff == null || session.updatedAtMillis >= cutoff
        }
    }

    private fun parseEpochMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }
            .getOrElse {
                runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                    .getOrElse {
                        runCatching {
                            LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
                        }.getOrNull()
                    }
            }
    }

    private data class Quint<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
    )
}
