package live.agor.app.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import live.agor.app.network.AgorJson

@Serializable
data class ProfileCredentialSnapshot(
    val serverUrl: String,
    val email: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val savedLoginPassword: String? = null,
    val savedApiKey: String? = null,
)

fun decodeProfileCredentialSnapshots(raw: String?): Map<String, ProfileCredentialSnapshot> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        AgorJson.decodeFromString(ProfileCredentialSnapshotMapSerializer, raw)
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, snapshot) -> snapshot.normalized() }
    }.getOrDefault(emptyMap())
}

fun encodeProfileCredentialSnapshots(
    snapshots: Map<String, ProfileCredentialSnapshot>,
): String = AgorJson.encodeToString(
    ProfileCredentialSnapshotMapSerializer,
    snapshots
        .filterKeys { it.isNotBlank() }
        .mapValues { (_, snapshot) -> snapshot.normalized() },
)

private fun ProfileCredentialSnapshot.normalized(): ProfileCredentialSnapshot =
    copy(
        serverUrl = serverUrl.trim().trimEnd('/'),
        email = email?.trim()?.takeIf { it.isNotBlank() },
        accessToken = accessToken?.takeIf { it.isNotBlank() },
        refreshToken = refreshToken?.takeIf { it.isNotBlank() },
        savedLoginPassword = savedLoginPassword?.takeIf { it.isNotBlank() },
        savedApiKey = savedApiKey?.takeIf { it.isNotBlank() },
    )

private val ProfileCredentialSnapshotMapSerializer = MapSerializer(
    String.serializer(),
    ProfileCredentialSnapshot.serializer(),
)
