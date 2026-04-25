package live.agor.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import live.agor.app.models.Board
import live.agor.app.models.Session
import live.agor.app.models.Worktree
import live.agor.app.network.AgorJson
import java.io.File

/**
 * 1-hour TTL JSON cache backing the sidebar tree, mirroring iOS SidebarCache.swift.
 * Restored on launch for instant UX while the network refresh runs in the background.
 */
class SidebarCache(context: Context) {

    private val file: File =
        File(context.filesDir, "sidebar_cache.json")

    @Serializable
    data class Snapshot(
        @SerialName("ts") val savedAtMillis: Long,
        @SerialName("boards") val boards: List<Board>,
        @SerialName("worktrees") val worktrees: List<Worktree>,
        @SerialName("sessions") val sessions: List<Session>,
    )

    suspend fun load(): Snapshot? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching {
            val json = file.readText()
            AgorJson.decodeFromString(Snapshot.serializer(), json)
        }.getOrNull()?.takeIf { System.currentTimeMillis() - it.savedAtMillis < TTL_MILLIS }
    }

    suspend fun save(snapshot: Snapshot) = withContext(Dispatchers.IO) {
        runCatching {
            val json = AgorJson.encodeToString(Snapshot.serializer(), snapshot)
            file.writeText(json)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }

    companion object {
        const val TTL_MILLIS = 60L * 60L * 1000L
    }
}
