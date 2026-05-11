package live.agor.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.agor.app.auth.SecureTokenStore
import live.agor.app.models.AgorTask
import live.agor.app.models.Message
import live.agor.app.models.Session
import live.agor.app.network.AgorJson
import java.io.File
import java.security.MessageDigest

/**
 * Disk-backed per-session chat cache.
 *
 * Chat screens should render this snapshot immediately and let the network
 * refresh update it in the background. The cache is scoped by server URL so
 * switching Agor instances cannot leak session transcripts across profiles.
 */
class ChatCache(
    context: Context,
    private val tokens: SecureTokenStore,
) {
    private val root: File = File(context.filesDir, "chat_cache")

    @Serializable
    data class Snapshot(
        @SerialName("ts") val savedAtMillis: Long,
        @SerialName("session") val session: Session,
        @SerialName("tasks") val tasks: List<AgorTask>,
        @SerialName("messages") val messages: List<Message>,
    )

    suspend fun load(sessionId: String): Snapshot? = withContext(Dispatchers.IO) {
        val file = fileFor(sessionId)
        if (!file.exists()) return@withContext null
        runCatching {
            AgorJson.decodeFromString(Snapshot.serializer(), file.readText())
        }.getOrNull()
    }

    suspend fun save(
        session: Session,
        tasks: List<AgorTask>,
        messages: List<Message>,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val file = fileFor(session.sessionId)
            file.parentFile?.mkdirs()
            val snapshot = Snapshot(
                savedAtMillis = System.currentTimeMillis(),
                session = session,
                tasks = tasks,
                messages = messages.sortedBy { it.index },
            )
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(AgorJson.encodeToString(Snapshot.serializer(), snapshot))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        root.deleteRecursively()
    }

    private fun fileFor(sessionId: String): File {
        val server = tokens.serverUrl.orEmpty().ifBlank { "default" }
        val serverDir = File(root, server.sha256())
        return File(serverDir, "${sessionId.safeFilePart()}.json")
    }
}

private fun String.safeFilePart(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "_")

private fun String.sha256(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
