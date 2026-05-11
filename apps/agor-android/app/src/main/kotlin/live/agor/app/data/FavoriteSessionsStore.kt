package live.agor.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import live.agor.app.network.AgorJson

private val Context.dataStore by preferencesDataStore(name = "agor_favorite_sessions")

/** Persists sidebar-favorited chat session ids across restarts and app updates. */
class FavoriteSessionsStore(private val context: Context) {

    private val key = stringPreferencesKey("favorite_session_ids")

    val favorites: Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            val raw = prefs[key] ?: return@map emptySet()
            runCatching {
                AgorJson.decodeFromString(ListSerializer(String.serializer()), raw)
                    .filter { it.isNotBlank() }
                    .toSet()
            }.getOrDefault(emptySet())
        }

    suspend fun load(): Set<String> {
        return favorites.map { it }.first()
    }

    suspend fun save(favorites: Set<String>) {
        val raw = AgorJson.encodeToString(
            ListSerializer(String.serializer()),
            favorites.filter { it.isNotBlank() }.sorted(),
        )
        context.dataStore.edit { it[key] = raw }
    }
}
