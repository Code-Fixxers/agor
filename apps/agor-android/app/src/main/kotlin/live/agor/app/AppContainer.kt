package live.agor.app

import android.content.Context
import live.agor.app.auth.AuthService
import live.agor.app.auth.SecureTokenStore
import live.agor.app.auth.ServerProfileManager
import live.agor.app.data.SidebarCache
import live.agor.app.network.AgorClient
import live.agor.app.network.SocketService
import live.agor.app.network.StreamingService
import live.agor.app.notifications.AgorNotificationManager
import live.agor.app.util.AppLogger

/**
 * Manual DI container — owned by AgorApplication, lifetime = process.
 * ViewModels read services from here via `LocalAppContainer`.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val logger: AppLogger = AppLogger
    val tokenStore: SecureTokenStore = SecureTokenStore(appContext)
    val serverProfiles: ServerProfileManager = ServerProfileManager(appContext)
    val client: AgorClient = AgorClient(tokenStore)
    val authService: AuthService = AuthService(appContext, client, tokenStore, serverProfiles)
    val socket: SocketService = SocketService(client, logger)
    val streaming: StreamingService = StreamingService(socket, logger)
    val sidebarCache: SidebarCache = SidebarCache(appContext)
    val notifications: AgorNotificationManager = AgorNotificationManager(appContext)
}
