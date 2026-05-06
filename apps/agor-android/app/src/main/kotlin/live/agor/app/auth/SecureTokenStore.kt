package live.agor.app.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted persistence for JWT/refresh tokens, server URL, and last login email.
 * Backed by Android Jetpack Security; survives reboot before first unlock thanks to
 * MasterKey AES_GCM defaults.
 */
class SecureTokenStore(context: Context) {

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

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var lastEmail: String?
        get() = prefs.getString(KEY_LAST_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_LAST_EMAIL, value).apply()

    /** Hermes Agent base URL (OpenAI-compatible /v1 endpoint). e.g. http://100.101.157.56:8642 */
    var hermesUrl: String?
        get() = prefs.getString(KEY_HERMES_URL, null)
        set(value) = prefs.edit().putString(KEY_HERMES_URL, value).apply()

    /** Hermes API server bearer token (sops-stored hermes_api_server_key on the host). */
    var hermesToken: String?
        get() = prefs.getString(KEY_HERMES_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_HERMES_TOKEN, value).apply()

    /** Hermes model name as advertised by API_SERVER_MODEL_NAME. Default: hermes-agent. */
    var hermesModel: String?
        get() = prefs.getString(KEY_HERMES_MODEL, null)
        set(value) = prefs.edit().putString(KEY_HERMES_MODEL, value).apply()

    /** Optional self-hosted whisper.cpp server URL for faster voice transcription. */
    var remoteWhisperUrl: String?
        get() = prefs.getString(KEY_REMOTE_WHISPER_URL, null)
        set(value) = prefs.edit().putString(KEY_REMOTE_WHISPER_URL, value).apply()

    /** Optional bearer token for the self-hosted whisper.cpp server. */
    var remoteWhisperToken: String?
        get() = prefs.getString(KEY_REMOTE_WHISPER_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REMOTE_WHISPER_TOKEN, value).apply()

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

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_LAST_EMAIL = "last_email"
        const val KEY_HERMES_URL = "hermes_url"
        const val KEY_HERMES_TOKEN = "hermes_token"
        const val KEY_HERMES_MODEL = "hermes_model"
        const val KEY_REMOTE_WHISPER_URL = "remote_whisper_url"
        const val KEY_REMOTE_WHISPER_TOKEN = "remote_whisper_token"
        const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        const val KEY_BIOMETRIC_SERVER_URL = "biometric_server_url"
        const val KEY_BIOMETRIC_EMAIL = "biometric_email"
        const val KEY_BIOMETRIC_PASSWORD_HASH = "biometric_password_hash"
        const val KEY_BIOMETRIC_PASSWORD_CIPHERTEXT = "biometric_password_ciphertext"
        const val KEY_BIOMETRIC_PASSWORD_IV = "biometric_password_iv"
        const val KEY_BIOMETRIC_PASSWORD_SCHEME = "biometric_password_scheme"
        const val KEY_BIOMETRIC_CREDENTIAL_TYPE = "biometric_credential_type"
    }
}
