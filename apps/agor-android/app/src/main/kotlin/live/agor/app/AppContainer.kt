package live.agor.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import live.agor.app.auth.AuthService
import live.agor.app.auth.BiometricCredentialStore
import live.agor.app.auth.SecureTokenStore
import live.agor.app.auth.ServerProfileManager
import live.agor.app.data.ChatCache
import live.agor.app.data.HermesImageStore
import live.agor.app.data.HermesSessionStore
import live.agor.app.data.SidebarCache
import live.agor.app.network.AgorClient
import live.agor.app.network.HermesClient
import live.agor.app.network.SocketService
import live.agor.app.network.StreamingService
import live.agor.app.notifications.AgorNotificationManager
import live.agor.app.update.UpdateChecker
import live.agor.app.update.UpdateInstaller
import live.agor.app.util.AppLogger
import live.agor.app.voice.HermesVoiceManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual DI container — owned by AgorApplication, lifetime = process.
 * ViewModels read services from here via `LocalAppContainer`.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val logger: AppLogger = AppLogger
    val tokenStore: SecureTokenStore = SecureTokenStore(appContext)
    val serverProfiles: ServerProfileManager = ServerProfileManager(appContext)
    val biometricStore: BiometricCredentialStore = BiometricCredentialStore(appContext, tokenStore)
    val client: AgorClient = AgorClient(tokenStore)
    val hermesClient: HermesClient = HermesClient(tokenStore)
    val authService: AuthService = AuthService(client, tokenStore, serverProfiles, biometricStore)
    val socket: SocketService = SocketService(client, logger)
    val streaming: StreamingService = StreamingService(socket, logger)
    val sidebarCache: SidebarCache = SidebarCache(appContext)
    val chatCache: ChatCache = ChatCache(appContext, tokenStore)
    val hermesSessions: HermesSessionStore = HermesSessionStore(appContext, tokenStore)
    val hermesImages: HermesImageStore = HermesImageStore(appContext)
    val hermesVoice: HermesVoiceManager = HermesVoiceManager(appContext, tokenStore, hermesSessions)
    val notifications: AgorNotificationManager = AgorNotificationManager(appContext)

    // Dedicated HTTP client for the in-app updater. Long read timeout because
    // the APK download streams ~22 MB on slow networks; redirect-following is
    // required because GitHub release asset URLs 302 to S3.
    private val updateHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    val updateChecker: UpdateChecker = UpdateChecker(updateHttp)
    val updateInstaller: UpdateInstaller = UpdateInstaller(appContext, updateHttp)

    private val _pendingSessionId = MutableStateFlow<String?>(null)
    /** Latest session id requested by an external entry point (notification / deep-link). */
    val pendingSessionId: StateFlow<String?> = _pendingSessionId.asStateFlow()

    private val _pendingHermesSessionId = MutableStateFlow<String?>(null)
    val pendingHermesSessionId: StateFlow<String?> = _pendingHermesSessionId.asStateFlow()

    fun requestOpenSession(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        _pendingSessionId.value = sessionId
    }

    fun consumePendingSessionId() {
        _pendingSessionId.value = null
    }

    fun requestOpenHermesSession(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        _pendingHermesSessionId.value = sessionId
    }

    fun consumePendingHermesSessionId() {
        _pendingHermesSessionId.value = null
    }
}
