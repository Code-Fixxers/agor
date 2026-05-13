package live.agor.jetbrains.toolwindow

import com.intellij.openapi.wm.ToolWindowAnchor
import java.awt.Dimension
import javax.swing.JSplitPane

internal enum class AgorSplitLayoutMode(val id: String, val label: String) {
    AUTO("auto", "Auto"),
    STACKED("stacked", "Stacked"),
    SIDE_BY_SIDE("side_by_side", "Side by side");

    override fun toString(): String = label

    companion object {
        fun fromId(id: String?): AgorSplitLayoutMode =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

internal data class AgorResolvedSplitLayout(
    val mode: AgorSplitLayoutMode,
    val splitPaneOrientation: Int,
    val resizeWeight: Double,
    val treeMinimumSize: Dimension,
)

internal object AgorSplitLayout {
    fun resolve(modeId: String?, anchor: ToolWindowAnchor): AgorResolvedSplitLayout {
        val configuredMode = AgorSplitLayoutMode.fromId(modeId)
        val effectiveMode = when (configuredMode) {
            AgorSplitLayoutMode.AUTO ->
                if (anchor == ToolWindowAnchor.LEFT || anchor == ToolWindowAnchor.RIGHT) {
                    AgorSplitLayoutMode.STACKED
                } else {
                    AgorSplitLayoutMode.SIDE_BY_SIDE
                }
            else -> configuredMode
        }

        return when (effectiveMode) {
            AgorSplitLayoutMode.STACKED -> AgorResolvedSplitLayout(
                mode = effectiveMode,
                splitPaneOrientation = JSplitPane.VERTICAL_SPLIT,
                resizeWeight = 0.42,
                treeMinimumSize = Dimension(220, 180),
            )
            AgorSplitLayoutMode.SIDE_BY_SIDE -> AgorResolvedSplitLayout(
                mode = effectiveMode,
                splitPaneOrientation = JSplitPane.HORIZONTAL_SPLIT,
                resizeWeight = 0.36,
                treeMinimumSize = Dimension(260, 300),
            )
            AgorSplitLayoutMode.AUTO -> error("Auto must be resolved before creating the split pane.")
        }
    }
}
