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
        val out = upsertServerProfile(profile, current)
        save(out)
        return out
    }

    suspend fun remove(id: String, current: List<ServerProfile>): List<ServerProfile> {
        val out = current.filter { it.id != id }
        save(out)
        return out
    }

    suspend fun setDefault(id: String, current: List<ServerProfile>): List<ServerProfile> {
        val out = current.withDefaultServerProfile(id)
        save(out)
        return out
    }
}

fun upsertServerProfile(profile: ServerProfile, current: List<ServerProfile>): List<ServerProfile> {
    val normalized = profile.normalized()
    val deduped = current.filter { it.id != normalized.id }
    val merged = (listOf(normalized) + deduped).distinctBy { it.url.trimEnd('/') }
    return if (normalized.isDefault) merged.withDefaultServerProfile(normalized.id)
    else if (merged.none { it.isDefault }) merged.withDefaultServerProfile(merged.firstOrNull()?.id.orEmpty())
    else merged
}

fun List<ServerProfile>.withDefaultServerProfile(id: String): List<ServerProfile> =
    map { it.copy(isDefault = it.id == id) }

fun migrateLegacyServerProfile(
    current: List<ServerProfile>,
    serverUrl: String?,
    email: String?,
): List<ServerProfile> {
    val normalizedUrl = serverUrl?.trim()?.trimEnd('/').orEmpty()
    if (normalizedUrl.isBlank()) return current
    if (current.any { it.url.trim().trimEnd('/') == normalizedUrl }) return current
    val profile = ServerProfile(
        id = normalizedUrl,
        label = email?.trim()?.takeIf { it.isNotBlank() } ?: normalizedUrl,
        url = normalizedUrl,
        email = email?.trim()?.takeIf { it.isNotBlank() },
        isDefault = current.none { it.isDefault },
    )
    return upsertServerProfile(profile, current)
}

private fun ServerProfile.normalized(): ServerProfile {
    val normalizedUrl = url.trim().trimEnd('/')
    val normalizedId = id.trim().trimEnd('/').ifBlank { normalizedUrl }
    return copy(id = normalizedId, url = normalizedUrl, email = email?.trim()?.takeIf { it.isNotBlank() })
}
