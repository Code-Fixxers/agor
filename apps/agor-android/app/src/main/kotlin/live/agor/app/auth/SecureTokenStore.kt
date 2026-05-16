package live.agor.app.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import live.agor.app.models.DrawerSessionFilter
import live.agor.app.network.AgorTokenStore
import live.agor.app.network.HermesTokenStore
import live.agor.app.voice.DEFAULT_REMOTE_WHISPER_URL

/**
 * Encrypted persistence for JWT/refresh tokens, server URL, and last login email.
 * Backed by Android Jetpack Security; survives reboot before first unlock thanks to
 * MasterKey AES_GCM defaults.
 */
class SecureTokenStore(context: Context) : AgorTokenStore, HermesTokenStore {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "agor_secure_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    override var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    override var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    override var lastEmail: String?
        get() = prefs.getString(KEY_LAST_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_LAST_EMAIL, value).apply()

    /** Encrypted reusable password for silent re-login after process restarts/token expiry. */
    var savedLoginPassword: String?
        get() = prefs.getString(KEY_SAVED_LOGIN_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_SAVED_LOGIN_PASSWORD, value?.takeIf { it.isNotBlank() }).apply()

    /** Encrypted reusable API key for silent API-key re-login after process restarts/token expiry. */
    var savedApiKey: String?
        get() = prefs.getString(KEY_SAVED_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_SAVED_API_KEY, value?.takeIf { it.isNotBlank() }).apply()

    /** Hermes/OpenAI-compatible base URL. Accepts either a server root or a `/v1` gateway URL. */
    override var hermesUrl: String?
        get() = prefs.getString(KEY_HERMES_URL, null)
        set(value) = prefs.edit().putString(KEY_HERMES_URL, value).apply()

    /** LiteLLM virtual key or direct Hermes bearer token. */
    override var hermesToken: String?
        get() = prefs.getString(KEY_HERMES_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_HERMES_TOKEN, value).apply()

    /** Hermes model name as advertised by the gateway. Default: hermes-agent. */
    override var hermesModel: String?
        get() = prefs.getString(KEY_HERMES_MODEL, null)
        set(value) = prefs.edit().putString(KEY_HERMES_MODEL, value).apply()

    /** Optional GitHub token used by the in-app updater to avoid anonymous API rate limits. */
    var githubToken: String?
        get() = prefs.getString(KEY_GITHUB_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_GITHUB_TOKEN, value?.takeIf { it.isNotBlank() }).apply()

    /** Optional self-hosted WhisperLiveKit server URL for faster voice transcription. */
    var remoteWhisperUrl: String?
        get() {
            if (prefs.getBoolean(KEY_REMOTE_WHISPER_DISABLED, false)) return null
            return prefs.getString(KEY_REMOTE_WHISPER_URL, null) ?: DEFAULT_REMOTE_WHISPER_URL
        }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) {
                    remove(KEY_REMOTE_WHISPER_URL)
                    putBoolean(KEY_REMOTE_WHISPER_DISABLED, true)
                } else {
                    putString(KEY_REMOTE_WHISPER_URL, value)
                    putBoolean(KEY_REMOTE_WHISPER_DISABLED, false)
                }
            }.apply()
        }

    /** Optional bearer token for the self-hosted WhisperLiveKit server. */
    var remoteWhisperToken: String?
        get() = prefs.getString(KEY_REMOTE_WHISPER_TOKEN, null) ?: hermesToken
        set(value) = prefs.edit().putString(KEY_REMOTE_WHISPER_TOKEN, value).apply()

    /** Explicit Whisper token override; when blank, voice uses the Hermes LiteLLM key. */
    var remoteWhisperTokenOverride: String?
        get() = prefs.getString(KEY_REMOTE_WHISPER_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REMOTE_WHISPER_TOKEN, value?.takeIf { it.isNotBlank() }).apply()

    var drawerSessionFilter: String
        get() = prefs.getString(KEY_DRAWER_SESSION_FILTER, null) ?: DrawerSessionFilter.SevenDays.token
        set(value) = prefs.edit().putString(KEY_DRAWER_SESSION_FILTER, value).apply()

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    var biometricServerUrl: String?
        get() = prefs.getString(KEY_BIOMETRIC_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_SERVER_URL, value).apply()

    var biometricEmail: String?
        get() = prefs.getString(KEY_BIOMETRIC_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_EMAIL, value).apply()

    var biometricPasswordHash: String?
        get() = prefs.getString(KEY_BIOMETRIC_PASSWORD_HASH, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_PASSWORD_HASH, value).apply()

    var biometricPasswordCipherText: String?
        get() = prefs.getString(KEY_BIOMETRIC_PASSWORD_CIPHERTEXT, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_PASSWORD_CIPHERTEXT, value).apply()

    var biometricPasswordIv: String?
        get() = prefs.getString(KEY_BIOMETRIC_PASSWORD_IV, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_PASSWORD_IV, value).apply()

    var biometricPasswordScheme: String?
        get() = prefs.getString(KEY_BIOMETRIC_PASSWORD_SCHEME, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_PASSWORD_SCHEME, value).apply()

    var biometricCredentialType: String?
        get() = prefs.getString(KEY_BIOMETRIC_CREDENTIAL_TYPE, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_CREDENTIAL_TYPE, value).apply()

    fun snapshotCurrentProfile(profileId: String? = serverUrl) {
        val id = profileId?.trim()?.trimEnd('/').orEmpty()
        val url = serverUrl?.trim()?.trimEnd('/').orEmpty()
        if (id.isBlank() || url.isBlank()) return
        val hasCredential = !accessToken.isNullOrBlank() ||
            !refreshToken.isNullOrBlank() ||
            !savedLoginPassword.isNullOrBlank() ||
            !savedApiKey.isNullOrBlank()
        if (!hasCredential) return
        saveProfileCredentials(
            id,
            ProfileCredentialSnapshot(
                serverUrl = url,
                email = lastEmail,
                accessToken = accessToken,
                refreshToken = refreshToken,
                savedLoginPassword = savedLoginPassword,
                savedApiKey = savedApiKey,
            ),
        )
    }

    fun saveProfileCredentials(profileId: String, snapshot: ProfileCredentialSnapshot) {
        val id = profileId.trim().trimEnd('/')
        if (id.isBlank()) return
        val next = profileCredentials().toMutableMap()
        next[id] = snapshot
        prefs.edit()
            .putString(KEY_PROFILE_CREDENTIALS, encodeProfileCredentialSnapshots(next))
            .apply()
    }

    fun profileCredentials(profileId: String): ProfileCredentialSnapshot? =
        profileCredentials()[profileId.trim().trimEnd('/')]

    fun removeProfileCredentials(profileId: String) {
        val id = profileId.trim().trimEnd('/')
        if (id.isBlank()) return
        val next = profileCredentials().toMutableMap()
        next.remove(id)
        prefs.edit()
            .putString(KEY_PROFILE_CREDENTIALS, encodeProfileCredentialSnapshots(next))
            .apply()
    }

    fun applyProfileCredentials(profileId: String, fallbackUrl: String, fallbackEmail: String?) {
        val snapshot = profileCredentials(profileId)
        serverUrl = snapshot?.serverUrl ?: fallbackUrl.trim().trimEnd('/')
        lastEmail = snapshot?.email ?: fallbackEmail
        accessToken = snapshot?.accessToken
        refreshToken = snapshot?.refreshToken
        savedLoginPassword = snapshot?.savedLoginPassword
        savedApiKey = snapshot?.savedApiKey
    }

    private fun profileCredentials(): Map<String, ProfileCredentialSnapshot> =
        decodeProfileCredentialSnapshots(prefs.getString(KEY_PROFILE_CREDENTIALS, null))

    fun clearTokensKeepUrl() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    fun clearBiometricCredentials() {
        prefs.edit()
            .remove(KEY_BIOMETRIC_ENABLED)
            .remove(KEY_BIOMETRIC_SERVER_URL)
            .remove(KEY_BIOMETRIC_EMAIL)
            .remove(KEY_BIOMETRIC_PASSWORD_HASH)
            .remove(KEY_BIOMETRIC_PASSWORD_CIPHERTEXT)
            .remove(KEY_BIOMETRIC_PASSWORD_IV)
            .remove(KEY_BIOMETRIC_PASSWORD_SCHEME)
            .remove(KEY_BIOMETRIC_CREDENTIAL_TYPE)
            .apply()
    }

    fun clearAuthCredentialsKeepAppSettings() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_SERVER_URL)
            .remove(KEY_LAST_EMAIL)
            .remove(KEY_SAVED_LOGIN_PASSWORD)
            .remove(KEY_SAVED_API_KEY)
            .remove(KEY_BIOMETRIC_ENABLED)
            .remove(KEY_BIOMETRIC_SERVER_URL)
            .remove(KEY_BIOMETRIC_EMAIL)
            .remove(KEY_BIOMETRIC_PASSWORD_HASH)
            .remove(KEY_BIOMETRIC_PASSWORD_CIPHERTEXT)
            .remove(KEY_BIOMETRIC_PASSWORD_IV)
            .remove(KEY_BIOMETRIC_PASSWORD_SCHEME)
            .remove(KEY_BIOMETRIC_CREDENTIAL_TYPE)
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_LAST_EMAIL = "last_email"
        const val KEY_SAVED_LOGIN_PASSWORD = "saved_login_password"
        const val KEY_SAVED_API_KEY = "saved_api_key"
        const val KEY_HERMES_URL = "hermes_url"
        const val KEY_HERMES_TOKEN = "hermes_token"
        const val KEY_HERMES_MODEL = "hermes_model"
        const val KEY_GITHUB_TOKEN = "github_token"
        const val KEY_REMOTE_WHISPER_URL = "remote_whisper_url"
        const val KEY_REMOTE_WHISPER_TOKEN = "remote_whisper_token"
        const val KEY_REMOTE_WHISPER_DISABLED = "remote_whisper_disabled"
        const val KEY_DRAWER_SESSION_FILTER = "drawer_session_filter"
        const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        const val KEY_BIOMETRIC_SERVER_URL = "biometric_server_url"
        const val KEY_BIOMETRIC_EMAIL = "biometric_email"
        const val KEY_BIOMETRIC_PASSWORD_HASH = "biometric_password_hash"
        const val KEY_BIOMETRIC_PASSWORD_CIPHERTEXT = "biometric_password_ciphertext"
        const val KEY_BIOMETRIC_PASSWORD_IV = "biometric_password_iv"
        const val KEY_BIOMETRIC_PASSWORD_SCHEME = "biometric_password_scheme"
        const val KEY_BIOMETRIC_CREDENTIAL_TYPE = "biometric_credential_type"
        const val KEY_PROFILE_CREDENTIALS = "profile_credentials"
    }
}
