package live.agor.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import live.agor.app.network.AgorJson

private val Context.sidebarExpansionDataStore by preferencesDataStore(name = "agor_sidebar_expansion")

@Serializable
data class SidebarExpansionState(
    val boardIds: Set<String> = emptySet(),
    val worktreeIds: Set<String> = emptySet(),
)

fun decodeSidebarExpansionState(raw: String?): SidebarExpansionState {
    if (raw.isNullOrBlank()) return SidebarExpansionState()
    return runCatching {
        AgorJson.decodeFromString(SidebarExpansionState.serializer(), raw).normalized()
    }.getOrDefault(SidebarExpansionState())
}

private fun SidebarExpansionState.normalized(): SidebarExpansionState =
    SidebarExpansionState(
        boardIds = boardIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        worktreeIds = worktreeIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    )

class SidebarExpansionStore(private val context: Context) {
    private val key = stringPreferencesKey("expanded_state")

    val state: Flow<SidebarExpansionState> =
        context.sidebarExpansionDataStore.data.map { prefs ->
            decodeSidebarExpansionState(prefs[key])
        }

    suspend fun load(): SidebarExpansionState = state.first()

    suspend fun save(state: SidebarExpansionState) {
        val normalized = state.normalized()
        val raw = AgorJson.encodeToString(SidebarExpansionState.serializer(), normalized)
        context.sidebarExpansionDataStore.edit { it[key] = raw }
    }
}
