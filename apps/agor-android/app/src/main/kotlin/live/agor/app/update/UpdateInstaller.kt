package live.agor.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.agor.app.auth.SecureTokenStore
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File

/**
 * Downloads an APK and hands it to the system installer.
 *
 *  - APK lands in `cacheDir/updates/<versionCode>.apk`. cacheDir is auto-cleaned
 *    by Android under storage pressure, so we don't have to delete it after install.
 *  - The file is exposed to the installer via a FileProvider content:// URI —
 *    a plain file:// path throws FileUriExposedException on API 24+.
 *  - The system "install unknown apps" dialog appears the first time the app
 *    requests this; once granted, future installs skip straight to the install
 *    confirmation.
 */
class UpdateInstaller(
    private val context: Context,
    private val http: OkHttpClient,
    private val tokens: SecureTokenStore,
) {
    /** Streams the APK to disk. Returns the file written, or null on failure. */
    suspend fun download(
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "${info.versionCode}.apk")
        try {
            val req = Request.Builder()
                .url(info.downloadUrl)
                .withGithubAuth(info.downloadUrl)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.log("APK download HTTP ${resp.code}", LogLevel.ERROR, "Update")
                    return@withContext null
                }
                val body = resp.body ?: return@withContext null
                val total = if (info.sizeBytes > 0) info.sizeBytes else body.contentLength()
                body.byteStream().use { input ->
                    out.sink().buffer().use { sink ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            sink.write(buf, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            out
        } catch (t: Throwable) {
            AppLogger.log("APK download failed: ${t.message}", LogLevel.ERROR, "Update")
            out.delete()
            null
        }
    }

    /**
     * Launches the system installer for the given APK. Returns false if the
     * caller doesn't have REQUEST_INSTALL_PACKAGES granted — in that case the
     * caller should redirect the user to the per-app "Install unknown apps"
     * settings via [requestInstallPermission].
     */
    fun install(apk: File): Boolean {
        if (!canRequestInstall()) return false
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.update.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    fun canRequestInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Opens the system "Install unknown apps" settings page for our package.
     * Returning the user back to the app after granting the toggle is the
     * caller's responsibility (typically a "Try install again" button).
     */
    fun requestInstallPermission() {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun Request.Builder.withGithubAuth(url: String): Request.Builder {
        if (!url.contains("github.com", ignoreCase = true)) return this
        val token = tokens.githubToken?.trim().orEmpty()
        if (token.isNotBlank()) {
            header("Authorization", "Bearer $token")
        }
        return this
    }
}
