package live.agor.app.ui.messageblocks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
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
            val dataUri = src.data?.let { data ->
                "data:${src.mediaType ?: "image/png"};base64,$data"
            }
            if (dataUri != null) {
                AsyncImage(
                    model = dataUri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
