package live.agor.jetbrains.toolwindow

import live.agor.jetbrains.client.AgorSessionStatus
import live.agor.jetbrains.client.AgorSnapshot

internal enum class AgorObjectFilterMode(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    PERMISSIONS("Needs approval"),
}

internal object AgorObjectListFilter {
    fun apply(snapshot: AgorSnapshot, query: String, mode: AgorObjectFilterMode): AgorSnapshot {
        val normalizedQuery = query.trim().lowercase()
        val permissionSessionIds = snapshot.permissionRequests.mapTo(mutableSetOf()) { it.sessionId }
        val queryMatchedBoardIds = snapshot.boards
            .filter { board -> normalizedQuery.isNotBlank() && board.name.contains(normalizedQuery, ignoreCase = true) }
            .mapTo(mutableSetOf()) { it.boardId }
        val queryMatchedWorktreeIds = snapshot.worktrees
            .filter { worktree ->
                normalizedQuery.isBlank() ||
                    worktree.boardId in queryMatchedBoardIds ||
                    worktree.name.contains(normalizedQuery, ignoreCase = true) ||
                    worktree.ref.orEmpty().contains(normalizedQuery, ignoreCase = true) ||
                    worktree.path.contains(normalizedQuery, ignoreCase = true)
            }
            .mapTo(mutableSetOf()) { it.worktreeId }

        val sessions = snapshot.sessions.filter { session ->
            val matchesMode = when (mode) {
                AgorObjectFilterMode.ALL -> true
                AgorObjectFilterMode.ACTIVE -> session.status == AgorSessionStatus.RUNNING || session.status == AgorSessionStatus.QUEUED
                AgorObjectFilterMode.PERMISSIONS -> session.sessionId in permissionSessionIds
            }
            val matchesQuery = normalizedQuery.isBlank() ||
                session.title.contains(normalizedQuery, ignoreCase = true) ||
                session.agenticTool.contains(normalizedQuery, ignoreCase = true) ||
                session.worktreeId in queryMatchedWorktreeIds
            matchesMode && matchesQuery
        }

        val visibleWorktreeIds = sessions.mapTo(mutableSetOf()) { it.worktreeId } + queryMatchedWorktreeIds
        val worktrees = snapshot.worktrees.filter { worktree ->
            worktree.worktreeId in visibleWorktreeIds &&
                when (mode) {
                    AgorObjectFilterMode.ALL -> true
                    AgorObjectFilterMode.ACTIVE -> sessions.any { it.worktreeId == worktree.worktreeId }
                    AgorObjectFilterMode.PERMISSIONS -> sessions.any { it.worktreeId == worktree.worktreeId }
                }
        }
        val visibleBoardIds = worktrees.mapNotNullTo(mutableSetOf()) { it.boardId?.takeIf(String::isNotBlank) }

        return snapshot.copy(
            boards = snapshot.boards.filter { it.boardId in visibleBoardIds || it.boardId in queryMatchedBoardIds },
            worktrees = worktrees,
            sessions = sessions,
        )
    }
}
