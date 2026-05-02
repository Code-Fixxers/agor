package live.agor.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import live.agor.app.BuildConfig
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Polls a rolling GitHub Release for a newer debug APK.
 *
 * The Release tag (`android-latest`) is moved to HEAD on every successful CI
 * build, with the APK attached as the asset `agor-android-debug.apk`. We parse
 * the release name (set to the short SHA by CI) and the body field
 * `versionCode: <int>` to compare against the running app's [BuildConfig.VERSION_CODE_FIELD].
 *
 * No auth required — public release endpoint. Rate limit is 60/hr unauthenticated,
 * which is plenty for a "check on launch + manual button" pattern.
 */
class UpdateChecker(
    private val http: OkHttpClient,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE_FIELD,
    private val releaseUrl: String = BuildConfig.UPDATE_RELEASE_URL,
) {
    @Serializable
    private data class GhAsset(
        @SerialName("name") val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
        @SerialName("size") val size: Long = 0,
    )

    @Serializable
    private data class GhRelease(
        @SerialName("name") val name: String? = null,
        @SerialName("tag_name") val tag: String? = null,
        @SerialName("body") val body: String? = null,
        @SerialName("assets") val assets: List<GhAsset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(releaseUrl)
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.log("Update check HTTP ${resp.code}", LogLevel.WARNING, "Update")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                val release = json.decodeFromString(GhRelease.serializer(), body)
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    ?: return@withContext null
                val remoteCode = parseVersionCode(release.body)
                    ?: return@withContext null
                if (remoteCode <= currentVersionCode) return@withContext null
                UpdateInfo(
                    versionCode = remoteCode,
                    versionName = release.name ?: release.tag ?: "unknown",
                    downloadUrl = apk.downloadUrl,
                    sizeBytes = apk.size,
                )
            }
        } catch (t: Throwable) {
            AppLogger.log("Update check failed: ${t.message}", LogLevel.WARNING, "Update")
            null
        }
    }

    /**
     * Pulls "versionCode: 1234" out of the markdown release body. Looser than
     * a JSON contract but matches what CI writes; tolerates whitespace and
     * surrounding markdown decoration.
     */
    private fun parseVersionCode(body: String?): Int? {
        if (body.isNullOrBlank()) return null
        val match = Regex("versionCode[^0-9]*([0-9]+)").find(body) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)
