package live.agor.app.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.copyOnDoubleTap(
    text: String,
    copiedLabel: String = "Message copied",
): Modifier = composed {
    if (text.isBlank()) return@composed this
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    combinedClickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = {},
        onDoubleClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
            Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
        },
    )
}
