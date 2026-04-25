package live.agor.app.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import live.agor.app.models.User
import live.agor.app.network.AgorClient
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

enum class AuthState { Unknown, NeedsLogin, Authenticated }

/**
 * Coordinates client + token store: smart-URL probe, login, restore-on-launch,
 * soft logout (keep URL/email), and hard logout.
 */
class AuthService(
    private val context: Context,
    val client: AgorClient,
    val tokens: SecureTokenStore,
    val profiles: ServerProfileManager,
) {
    private val _state = MutableStateFlow(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    suspend fun bootstrap() {
        val url = tokens.serverUrl
        val token = tokens.accessToken
        if (url.isNullOrEmpty() || token.isNullOrEmpty()) {
            _state.value = AuthState.NeedsLogin
            return
        }
        client.setBaseUrl(url)
        _state.value = try {
            val u = client.me()
            _user.value = u
            AuthState.Authenticated
        } catch (_: AgorClient.AuthException) {
            // Retry once via refresh inside the client; if me() still fails we soft-logout.
            softLogout()
            AuthState.NeedsLogin
        } catch (t: Throwable) {
            AppLogger.log("bootstrap failed: ${t.message}", LogLevel.WARNING, "Auth")
            AuthState.NeedsLogin
        }
    }

    suspend fun login(rawUrl: String, email: String, password: String) {
        val resolved = client.probeBaseUrl(rawUrl)
            ?: throw AgorClient.HttpException(0, "Could not reach server at $rawUrl", "")
        client.setBaseUrl(resolved)
        val result = client.login(email, password)
        _user.value = result.user
        _state.value = AuthState.Authenticated
        // Save profile
        val list = profiles.profiles.let { /* one-shot read */ emptyList<live.agor.app.models.ServerProfile>() }
        profiles.upsert(
            live.agor.app.models.ServerProfile(
                id = resolved,
                label = result.user.email ?: result.user.name,
                url = resolved,
                email = result.user.email,
            ),
            list,
        )
    }

    /** Clears tokens but keeps URL + email so the login form pre-fills. */
    fun softLogout() {
        tokens.clearTokensKeepUrl()
        _user.value = null
        _state.value = AuthState.NeedsLogin
    }

    fun logout() {
        tokens.clearAll()
        _user.value = null
        _state.value = AuthState.NeedsLogin
    }
}
