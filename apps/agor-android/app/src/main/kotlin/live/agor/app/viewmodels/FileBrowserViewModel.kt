package live.agor.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import live.agor.app.AppContainer
import live.agor.app.models.FileDetail
import live.agor.app.models.FileListItem
import live.agor.app.models.VirtualNode
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

/**
 * Builds a virtual directory tree from the daemon's flat file list. Mirrors
 * apps/agor-ios/AgorApp/ViewModels/FileBrowserViewModel.swift.
 */
class FileBrowserViewModel(private val container: AppContainer, val worktreeId: String) : ViewModel() {

    data class State(
        val isLoading: Boolean = false,
        val tree: VirtualNode = VirtualNode("", "", isDirectory = true),
        val cwd: String = "",
        val expanded: Set<String> = emptySet(),
        val flatList: List<FileListItem> = emptyList(),
        val errorMessage: String? = null,
        val openFile: FileDetail? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val list = container.client.listFiles(worktreeId)
                _state.value = _state.value.copy(
                    isLoading = false,
                    flatList = list,
                    tree = buildTree(list),
                )
            } catch (t: Throwable) {
                AppLogger.log("File list failed: ${t.message}", LogLevel.WARNING, "Files")
                _state.value = _state.value.copy(isLoading = false, errorMessage = t.message)
            }
        }
    }

    fun toggle(path: String) {
        val cur = _state.value.expanded
        _state.value = _state.value.copy(expanded = if (cur.contains(path)) cur - path else cur + path)
    }

    fun open(path: String) {
        viewModelScope.launch {
            try {
                val detail = container.client.getFile(worktreeId, path)
                _state.value = _state.value.copy(openFile = detail)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(errorMessage = t.message)
            }
        }
    }

    fun closeFile() { _state.value = _state.value.copy(openFile = null) }

    private fun buildTree(list: List<FileListItem>): VirtualNode {
        val root = VirtualNode("/", "", isDirectory = true)
        for (item in list) {
            val parts = item.path.trim('/').split('/').filter { it.isNotEmpty() }
            var node = root
            var pathSoFar = ""
            for ((idx, p) in parts.withIndex()) {
                pathSoFar = if (pathSoFar.isEmpty()) p else "$pathSoFar/$p"
                val isLast = idx == parts.size - 1
                val existing = node.children.find { it.name == p }
                node = if (existing != null) existing else {
                    val isDir = if (isLast) (item.isDirectory == true) else true
                    VirtualNode(p, pathSoFar, isDir).also {
                        if (isLast) it.size = item.size
                        node.children += it
                    }
                }
            }
        }
        sortRecursive(root)
        return root
    }

    private fun sortRecursive(node: VirtualNode) {
        node.children.sortWith(compareByDescending<VirtualNode> { it.isDirectory }.thenBy { it.name.lowercase() })
        node.children.forEach(::sortRecursive)
    }
}
