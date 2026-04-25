package live.agor.app.ui.filebrowser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import live.agor.app.LocalAppContainer
import live.agor.app.models.VirtualNode
import live.agor.app.ui.messageblocks.ImageBlockView
import live.agor.app.ui.simpleViewModelFactory
import live.agor.app.viewmodels.FileBrowserViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserSheet(worktreeId: String, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: FileBrowserViewModel = viewModel(
        key = "files-$worktreeId",
        factory = simpleViewModelFactory { FileBrowserViewModel(container, worktreeId) },
    )
    val state by vm.state.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { vm.load() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            if (state.openFile != null) {
                FilePreviewView(
                    detail = state.openFile!!,
                    onClose = { vm.closeFile() },
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    treeNodes(state.tree, depth = 0, expanded = state.expanded, onToggle = vm::toggle, onOpen = vm::open)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.treeNodes(
    node: VirtualNode,
    depth: Int,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val children = node.children
    children.forEach { child ->
        val isOpen = expanded.contains(child.path)
        item(key = child.path) {
            FileRow(child, depth, isOpen, onToggle = onToggle, onOpen = onOpen)
        }
        if (child.isDirectory && isOpen) {
            treeNodes(child, depth + 1, expanded, onToggle, onOpen)
        }
    }
}

@Composable
private fun FileRow(
    node: VirtualNode,
    depth: Int,
    isOpen: Boolean,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (node.isDirectory) onToggle(node.path) else onOpen(node.path)
            }
            .padding(start = (8 + depth * 12).dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when {
            node.isDirectory -> Icons.Default.Folder
            looksLikeImage(node.name) -> Icons.Default.Image
            else -> Icons.Default.Description
        }
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(node.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (!node.isDirectory && node.size != null) {
            Text(
                humanSize(node.size!!),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilePreviewView(detail: live.agor.app.models.FileDetail, onClose: () -> Unit) {
    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text(detail.path, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f))
        }
        if (looksLikeImage(detail.path) && detail.base64 != null) {
            ImageBlockView(
                live.agor.app.models.ContentBlock.Image(
                    live.agor.app.models.ImageSource(
                        type = "base64",
                        mediaType = detail.mediaType,
                        data = detail.base64,
                    ),
                ),
            )
        } else {
            androidx.compose.foundation.rememberScrollState().let { scroll ->
                Text(
                    text = detail.content.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .androidVerticalScroll(scroll),
                )
            }
        }
    }
}

private fun Modifier.androidVerticalScroll(state: androidx.compose.foundation.ScrollState): Modifier =
    this.then(androidx.compose.foundation.verticalScroll(state))

private fun looksLikeImage(name: String): Boolean {
    val lc = name.lowercase()
    return lc.endsWith(".png") || lc.endsWith(".jpg") || lc.endsWith(".jpeg") ||
        lc.endsWith(".gif") || lc.endsWith(".webp")
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${"%.1f".format(kb)} KB"
    val mb = kb / 1024.0
    return "${"%.1f".format(mb)} MB"
}
