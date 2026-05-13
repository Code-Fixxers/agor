package live.agor.jetbrains.toolwindow

import com.intellij.openapi.util.IconLoader

internal object AgorIcons {
    val Refresh = icon("refresh")
    val NewWorktree = icon("new-worktree")
    val NewSession = icon("new-session")
    val OpenPath = icon("open-path")
    val Send = icon("send")
    val Stop = icon("stop")
    val Fork = icon("fork")
    val Spawn = icon("spawn")
    val Approve = icon("approve")
    val Deny = icon("deny")
    val Layout = icon("layout")
    val Settings = icon("settings")

    private fun icon(name: String) = IconLoader.getIcon("/icons/actions/$name.svg", AgorIcons::class.java)
}
