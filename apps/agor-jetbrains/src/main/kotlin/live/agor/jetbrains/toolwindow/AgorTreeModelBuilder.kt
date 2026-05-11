package live.agor.jetbrains.toolwindow

import live.agor.jetbrains.client.AgorBoard
import live.agor.jetbrains.client.AgorSession
import live.agor.jetbrains.client.AgorWorktree

enum class AgorTreeNodeKind {
    BOARD,
    WORKTREE,
    SESSION,
}

data class AgorTreeNode(
    val kind: AgorTreeNodeKind,
    val id: String,
    val label: String,
    val children: List<AgorTreeNode> = emptyList(),
)

class AgorTreeModelBuilder {
    fun build(
        boards: List<AgorBoard>,
        worktrees: List<AgorWorktree>,
        sessions: List<AgorSession>,
    ): List<AgorTreeNode> {
        val sessionsByWorktree = sessions.groupBy { it.worktreeId }
        val worktreesByBoard = worktrees.groupBy { it.boardId }
        val boardNodes = boards.sortedBy { it.name.lowercase() }.map { board ->
            AgorTreeNode(
                kind = AgorTreeNodeKind.BOARD,
                id = board.boardId,
                label = board.name,
                children = worktreeNodes(worktreesByBoard[board.boardId].orEmpty(), sessionsByWorktree),
            )
        }

        val orphanWorktrees = worktreesByBoard[null].orEmpty() + worktreesByBoard[""].orEmpty()
        if (orphanWorktrees.isEmpty()) return boardNodes

        return boardNodes + AgorTreeNode(
            kind = AgorTreeNodeKind.BOARD,
            id = "unassigned",
            label = "Unassigned",
            children = worktreeNodes(orphanWorktrees, sessionsByWorktree),
        )
    }

    private fun worktreeNodes(
        worktrees: List<AgorWorktree>,
        sessionsByWorktree: Map<String, List<AgorSession>>,
    ): List<AgorTreeNode> =
        worktrees.sortedBy { it.name.lowercase() }.map { worktree ->
            AgorTreeNode(
                kind = AgorTreeNodeKind.WORKTREE,
                id = worktree.worktreeId,
                label = worktree.name,
                children = sessionsByWorktree[worktree.worktreeId].orEmpty()
                    .sortedBy { it.title.lowercase() }
                    .map { session ->
                        AgorTreeNode(
                            kind = AgorTreeNodeKind.SESSION,
                            id = session.sessionId,
                            label = session.title,
                        )
                    },
            )
        }
}
