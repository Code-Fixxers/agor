package live.agor.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SidebarExpansionStoreTest {
    @Test
    fun decodesPersistedExpansionStateWithBlankIdsRemoved() {
        val raw = """{"boardIds":["board-a","","board-b"],"worktreeIds":["wt-a"," ","wt-b"]}"""

        val state = decodeSidebarExpansionState(raw)

        assertEquals(setOf("board-a", "board-b"), state.boardIds)
        assertEquals(setOf("wt-a", "wt-b"), state.worktreeIds)
    }

    @Test
    fun invalidExpansionStateFallsBackToEmpty() {
        val state = decodeSidebarExpansionState("not-json")

        assertEquals(emptySet<String>(), state.boardIds)
        assertEquals(emptySet<String>(), state.worktreeIds)
    }
}
