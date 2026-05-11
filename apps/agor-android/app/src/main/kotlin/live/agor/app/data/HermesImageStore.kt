package live.agor.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

class HermesImageStore(private val context: Context) {
    private val root = File(context.filesDir, "hermes_images")

    suspend fun importUri(uri: Uri): HermesImageInput = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not read image")
        persistBytes(bytes).also {
            AppLogger.log(
                "Imported Hermes image bytes=${bytes.size} size=${it.attachment.width}x${it.attachment.height} in ${elapsedMs(started)}ms",
                LogLevel.INFO,
                "Hermes",
            )
        }
    }

    suspend fun importDataUrl(dataUrl: String): HermesImageInput = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:image/") || comma < 0) {
            throw IllegalArgumentException("Expected data:image/*;base64 data URL")
        }
        val raw = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        persistBytes(raw).also {
            AppLogger.log(
                "Imported Hermes data URL image bytes=${raw.size} size=${it.attachment.width}x${it.attachment.height} in ${elapsedMs(started)}ms",
                LogLevel.INFO,
                "Hermes",
            )
        }
    }

    private fun persistBytes(raw: ByteArray): HermesImageInput {
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: throw IllegalArgumentException("Unsupported image data")
        val scaled = decoded.scaleDown(maxSide = 1600)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val jpg = out.toByteArray()
        val id = UUID.randomUUID().toString()
        root.mkdirs()
        val file = File(root, "$id.jpg")
        file.writeBytes(jpg)
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpg, Base64.NO_WRAP)
        return HermesImageInput(
            dataUrl = dataUrl,
            attachment = HermesAttachment(
                id = id,
                mimeType = "image/jpeg",
                localPath = file.absolutePath,
                width = scaled.width,
                height = scaled.height,
            ),
        )
    }
}

private fun elapsedMs(startedNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

data class HermesImageInput(
    val dataUrl: String,
    val attachment: HermesAttachment,
)

private fun Bitmap.scaleDown(maxSide: Int): Bitmap {
    val largest = maxOf(width, height)
    if (largest <= maxSide) return this
    val scale = maxSide.toFloat() / largest.toFloat()
    val w = (width * scale).roundToInt().coerceAtLeast(1)
    val h = (height * scale).roundToInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(this, w, h, true)
    if (scaled != this) recycle()
    return scaled
}
