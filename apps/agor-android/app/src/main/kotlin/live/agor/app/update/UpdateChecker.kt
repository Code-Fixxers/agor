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
 * build, with a small JSON manifest and the APK attached as stable assets.
 * The manifest path avoids GitHub's unauthenticated API rate limit; the Releases
 * API remains as a fallback for older releases that do not have the manifest yet.
 *
 * No auth required.
 */
class UpdateChecker(
    private val http: OkHttpClient,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE_FIELD,
    private val manifestUrl: String = BuildConfig.UPDATE_MANIFEST_URL,
    private val apkUrl: String = BuildConfig.UPDATE_APK_URL,
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

    @Serializable
    private data class UpdateManifest(
        @SerialName("versionCode") val versionCode: Int,
        @SerialName("versionName") val versionName: String,
        @SerialName("commit") val commit: String? = null,
        @SerialName("apkUrl") val apkUrl: String? = null,
        @SerialName("sizeBytes") val sizeBytes: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    var lastError: String? = null
        private set

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        lastError = null
        checkManifest()?.let { return@withContext it }
        checkReleaseApi()
    }

    private fun checkManifest(): UpdateInfo? {
        return try {
            val req = Request.Builder()
                .url(manifestUrl)
                .header("Cache-Control", "no-cache")
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return null
                if (!resp.isSuccessful) {
                    lastError = "Update manifest HTTP ${resp.code}"
                    AppLogger.log(lastError!!, LogLevel.WARNING, "Update")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val manifest = json.decodeFromString(UpdateManifest.serializer(), body)
                if (manifest.versionCode <= currentVersionCode) return null
                return UpdateInfo(
                    versionCode = manifest.versionCode,
                    versionName = manifest.versionName,
                    downloadUrl = manifest.apkUrl ?: apkUrl,
                    sizeBytes = manifest.sizeBytes,
                )
            }
        } catch (t: Throwable) {
            lastError = "Update manifest failed: ${t.message}"
            AppLogger.log(lastError!!, LogLevel.WARNING, "Update")
            null
        }
    }

    private fun checkReleaseApi(): UpdateInfo? {
        return try {
            val req = Request.Builder()
                .url(releaseUrl)
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    lastError = "Update check HTTP ${resp.code}"
                    AppLogger.log(lastError!!, LogLevel.WARNING, "Update")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val release = json.decodeFromString(GhRelease.serializer(), body)
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    ?: return null
                val remoteCode = parseVersionCode(release.body)
                    ?: return null
                if (remoteCode <= currentVersionCode) return null
                return UpdateInfo(
                    versionCode = remoteCode,
                    versionName = release.name ?: release.tag ?: "unknown",
                    downloadUrl = apk.downloadUrl,
                    sizeBytes = apk.size,
                )
            }
        } catch (t: Throwable) {
            lastError = "Update check failed: ${t.message}"
            AppLogger.log(lastError!!, LogLevel.WARNING, "Update")
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
        val match = Regex(
            "version[_\\s-]*code[^0-9]*([0-9]+)",
            RegexOption.IGNORE_CASE,
        ).find(body) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)
