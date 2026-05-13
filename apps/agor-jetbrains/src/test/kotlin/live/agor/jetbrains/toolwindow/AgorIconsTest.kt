package live.agor.jetbrains.toolwindow

import org.junit.Assert.assertNotNull
import org.junit.Test

class AgorIconsTest {
    @Test
    fun `loads action vector icons`() {
        assertNotNull(AgorIcons.Refresh)
        assertNotNull(AgorIcons.NewWorktree)
        assertNotNull(AgorIcons.NewSession)
        assertNotNull(AgorIcons.Send)
        assertNotNull(AgorIcons.Stop)
        assertNotNull(AgorIcons.Fork)
        assertNotNull(AgorIcons.Spawn)
        assertNotNull(AgorIcons.Approve)
        assertNotNull(AgorIcons.Deny)
        assertNotNull(AgorIcons.Settings)
    }
}
