package live.agor.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AttachmentPickerDialog(
    onDismiss: () -> Unit,
    onFile: () -> Unit,
    onPicture: () -> Unit,
    onCamera: () -> Unit,
    onLogs: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach") },
        text = {
            Column {
                AttachmentChoice(Icons.Default.AttachFile, "File from device", onFile)
                AttachmentChoice(Icons.Default.Image, "Picture", onPicture)
                AttachmentChoice(Icons.Default.CameraAlt, "Camera photo", onCamera)
                AttachmentChoice(Icons.AutoMirrored.Filled.Article, "Application logs", onLogs)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun AttachmentChoice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Text(label, modifier = Modifier.padding(start = 12.dp))
        }
    }
}
