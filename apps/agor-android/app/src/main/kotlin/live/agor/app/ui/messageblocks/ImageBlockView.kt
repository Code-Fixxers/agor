package live.agor.app.ui.messageblocks

import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.agor.app.models.ContentBlock

@Composable
fun ImageBlockView(block: ContentBlock.Image) {
    val src = block.source
    when (src.type) {
        "url" -> {
            AsyncImage(
                model = src.url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        "base64" -> {
            val data = src.data
            if (data != null) {
                val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, key1 = data) {
                    value = withContext(Dispatchers.Default) {
                        runCatching {
                            val bytes = Base64.decode(data, Base64.DEFAULT)
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }.getOrNull()?.asImageBitmap()
                    }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
