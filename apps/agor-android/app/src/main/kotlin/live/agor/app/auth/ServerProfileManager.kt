package live.agor.app.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import live.agor.app.models.ServerProfile
import live.agor.app.network.AgorJson

private val Context.dataStore by preferencesDataStore(name = "agor_server_profiles")

/** Persists multiple Agor server profiles (URL + label + email). Plain prefs — non-secret. */
class ServerProfileManager(private val context: Context) {

    private val key = stringPreferencesKey("profiles")

    val profiles: Flow<List<ServerProfile>> =
        context.dataStore.data.map { prefs ->
            val raw = prefs[key] ?: return@map emptyList()
            runCatching {
                AgorJson.decodeFromString(ListSerializer(ServerProfile.serializer()), raw)
            }.getOrDefault(emptyList())
        }

    suspend fun save(list: List<ServerProfile>) {
        val raw = AgorJson.encodeToString(ListSerializer(ServerProfile.serializer()), list)
        context.dataStore.edit { it[key] = raw }
    }

    suspend fun upsert(profile: ServerProfile, current: List<ServerProfile>): List<ServerProfile> {
        val deduped = current.filter { it.id != profile.id }
        val out = (listOf(profile) + deduped).distinctBy { it.url.trimEnd('/') }
        save(out)
        return out
    }

    suspend fun remove(id: String, current: List<ServerProfile>): List<ServerProfile> {
        val out = current.filter { it.id != id }
        save(out)
        return out
    }
}
