package live.agor.jetbrains.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import javax.swing.tree.DefaultMutableTreeNode

class AgorTreeSelectionTest {
    @Test
    fun `finds matching node path after tree model is rebuilt`() {
        val root = DefaultMutableTreeNode("Agor")
        val board = DefaultMutableTreeNode(AgorNodeRef(AgorTreeNodeKind.BOARD, "board-1", "Main"))
        val worktree = DefaultMutableTreeNode(AgorNodeRef(AgorTreeNodeKind.WORKTREE, "wt-1", "Addon"))
        val session = DefaultMutableTreeNode(AgorNodeRef(AgorTreeNodeKind.SESSION, "sess-1", "Build"))
        root.add(board)
        board.add(worktree)
        worktree.add(session)

        val path = findNodePath(root, AgorNodeRef(AgorTreeNodeKind.SESSION, "sess-1", "Updated title"))

        assertNotNull(path)
        assertEquals(session, path?.lastPathComponent)
    }

    @Test
    fun `returns null when selected node no longer exists`() {
        val root = DefaultMutableTreeNode("Agor")
        root.add(DefaultMutableTreeNode(AgorNodeRef(AgorTreeNodeKind.BOARD, "board-1", "Main")))

        assertNull(findNodePath(root, AgorNodeRef(AgorTreeNodeKind.SESSION, "missing", "Missing")))
    }
}
