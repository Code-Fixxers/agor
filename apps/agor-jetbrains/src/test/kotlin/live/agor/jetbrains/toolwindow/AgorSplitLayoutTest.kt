package live.agor.jetbrains.toolwindow

import com.intellij.openapi.wm.ToolWindowAnchor
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.swing.JSplitPane

class AgorSplitLayoutTest {
    @Test
    fun `auto layout stacks panes when tool window is docked on the side`() {
        assertEquals(JSplitPane.VERTICAL_SPLIT, AgorSplitLayout.resolve("auto", ToolWindowAnchor.LEFT).splitPaneOrientation)
        assertEquals(JSplitPane.VERTICAL_SPLIT, AgorSplitLayout.resolve("auto", ToolWindowAnchor.RIGHT).splitPaneOrientation)
    }

    @Test
    fun `auto layout uses side by side panes when tool window is docked on top or bottom`() {
        assertEquals(JSplitPane.HORIZONTAL_SPLIT, AgorSplitLayout.resolve("auto", ToolWindowAnchor.TOP).splitPaneOrientation)
        assertEquals(JSplitPane.HORIZONTAL_SPLIT, AgorSplitLayout.resolve("auto", ToolWindowAnchor.BOTTOM).splitPaneOrientation)
    }

    @Test
    fun `manual layout mode overrides tool window dock position`() {
        assertEquals(JSplitPane.HORIZONTAL_SPLIT, AgorSplitLayout.resolve("side_by_side", ToolWindowAnchor.LEFT).splitPaneOrientation)
        assertEquals(JSplitPane.VERTICAL_SPLIT, AgorSplitLayout.resolve("stacked", ToolWindowAnchor.BOTTOM).splitPaneOrientation)
    }

    @Test
    fun `unknown layout mode falls back to automatic`() {
        assertEquals(JSplitPane.HORIZONTAL_SPLIT, AgorSplitLayout.resolve("unknown", ToolWindowAnchor.BOTTOM).splitPaneOrientation)
    }
}
