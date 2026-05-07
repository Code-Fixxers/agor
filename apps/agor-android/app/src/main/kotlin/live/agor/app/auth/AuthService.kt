package live.agor.app.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val client: AgorClient,
    val tokens: SecureTokenStore,
    val profiles: ServerProfileManager,
    val biometricStore: BiometricCredentialStore,
) {
    private val _state = MutableStateFlow(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    suspend fun bootstrap() {
        val url = tokens.serverUrl
        val token = tokens.accessToken
        if (url.isNullOrEmpty()) {
            _state.value = AuthState.NeedsLogin
            return
        }
        client.setBaseUrl(url)
        if (!token.isNullOrEmpty()) {
            try {
                val u = client.me()
                _user.value = u
                _state.value = AuthState.Authenticated
                return
            } catch (_: AgorClient.AuthException) {
                tokens.clearTokensKeepUrl()
            } catch (t: Throwable) {
                AppLogger.log("bootstrap token restore failed: ${t.message}", LogLevel.WARNING, "Auth")
            }
        }
        _state.value = if (restoreWithSavedLogin(url)) {
            AuthState.Authenticated
        } else {
            AuthState.NeedsLogin
        }
    }

    suspend fun login(rawUrl: String, email: String, password: String) {
        val normalizedUrl = normalizeUrl(rawUrl)
        val canonicalEmail = normalizeEmailForLogin(email)
        if (normalizedUrl.isBlank() || canonicalEmail.isBlank()) {
            throw AgorClient.HttpException(0, "Server URL and email are required", "")
        }
        val emailCandidates = buildList {
            add(canonicalEmail)
            val lowered = canonicalEmail.lowercase()
            if (lowered != canonicalEmail) add(lowered)
        }
        val resolved = client.probeBaseUrl(normalizedUrl)
            ?: throw AgorClient.HttpException(0, "Could not reach server at $rawUrl", "")
        client.setBaseUrl(resolved)
        var lastError: Throwable? = null
        var result: AgorClient.LoginResult? = null
        var usedEmail: String = canonicalEmail
        for (candidate in emailCandidates) {
            try {
                result = client.login(candidate, password)
                usedEmail = candidate
                break
            } catch (t: Throwable) {
                lastError = t
            }
        }
        if (result == null) throw lastError
            ?: AgorClient.HttpException(0, "Could not authenticate", "")

        tokens.lastEmail = usedEmail
        tokens.savedLoginPassword = password
        tokens.savedApiKey = null
        _user.value = result.user
        _state.value = AuthState.Authenticated
        // Save profile
        runCatching {
            val list = profiles.profiles.first()
            profiles.upsert(
                live.agor.app.models.ServerProfile(
                    id = resolved,
                    label = result.user.email ?: result.user.name,
                    url = resolved,
                    email = result.user.email,
                ),
                list,
            )
        }.onFailure {
            AppLogger.log(
                "Failed to persist server profile: ${it.message}",
                LogLevel.WARNING,
                "Auth",
            )
        }
    }

    suspend fun loginWithApiKey(rawUrl: String, apiKey: String) {
        val resolved = client.probeBaseUrl(rawUrl)
            ?: throw AgorClient.HttpException(0, "Could not reach server at $rawUrl", "")
        client.setBaseUrl(resolved)
        val result = client.loginWithApiKey(apiKey)
        tokens.savedApiKey = apiKey
        tokens.savedLoginPassword = null
        _user.value = result.user
        _state.value = AuthState.Authenticated
    }

    /** Clears tokens but keeps URL + email so the login form pre-fills. */
    fun softLogout() {
        tokens.clearTokensKeepUrl()
        _user.value = null
        _state.value = AuthState.NeedsLogin
    }

    fun logout() {
        tokens.clearAll()
        biometricStore.clearStoredCredentials()
        _user.value = null
        _state.value = AuthState.NeedsLogin
    }

    private fun normalizeUrl(input: String): String = input.trim().trimEnd('/')
    private fun normalizeEmailForLogin(input: String): String = input.trim()

    private suspend fun restoreWithSavedLogin(url: String): Boolean {
        val apiKey = tokens.savedApiKey
        if (!apiKey.isNullOrBlank()) {
            return runCatching {
                loginWithApiKey(url, apiKey)
            }.onFailure {
                AppLogger.log("saved API-key login failed: ${it.message}", LogLevel.WARNING, "Auth")
            }.isSuccess
        }

        val email = tokens.lastEmail
        val password = tokens.savedLoginPassword
        if (email.isNullOrBlank() || password.isNullOrBlank()) return false
        return runCatching {
            login(url, email, password)
        }.onFailure {
            AppLogger.log("saved password login failed: ${it.message}", LogLevel.WARNING, "Auth")
        }.isSuccess
    }
}
