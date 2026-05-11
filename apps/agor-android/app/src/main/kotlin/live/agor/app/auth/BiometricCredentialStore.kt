package live.agor.app.auth

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

private const val KEY_ALIAS = "agor_password_vault_rsa"
private const val KEY_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
private const val KEY_STORE = "AndroidKeyStore"
private const val KEY_SIZE_BITS = 2048
private const val PASSWORD_SCHEME_RSA_OAEP = "rsa-oaep-sha256-biometric-v1"
private const val CREDENTIAL_TYPE_PASSWORD = "password"
private const val CREDENTIAL_TYPE_API_KEY = "api-key"

class BiometricCredentialStore(
    private val context: Context,
    private val tokenStore: SecureTokenStore,
) {

    val canUnlock: Boolean
        get() {
            if (!tokenStore.biometricEnabled) return false
            return when (tokenStore.biometricCredentialType ?: CREDENTIAL_TYPE_PASSWORD) {
                CREDENTIAL_TYPE_API_KEY -> !tokenStore.savedApiKey.isNullOrBlank()
                else -> !tokenStore.savedLoginPassword.isNullOrBlank() ||
                    (
                        tokenStore.biometricPasswordCipherText != null &&
                            tokenStore.biometricPasswordHash != null &&
                            tokenStore.biometricPasswordScheme == PASSWORD_SCHEME_RSA_OAEP
                        )
            }
        }

    fun canUnlockFor(serverUrl: String, email: String): Boolean {
        if (!canUnlock) return false
        if ((tokenStore.biometricCredentialType ?: CREDENTIAL_TYPE_PASSWORD) != CREDENTIAL_TYPE_PASSWORD) return false
        val normalizedUrl = normalize(serverUrl)
        val normalizedEmail = email.trim().lowercase()
        if (normalizedUrl.isEmpty() || normalizedEmail.isEmpty()) return false
        return tokenStore.biometricServerUrl == normalizedUrl &&
            tokenStore.biometricEmail == normalizedEmail
    }

    fun canUnlockApiKeyFor(serverUrl: String): Boolean {
        if (!canUnlock) return false
        if (tokenStore.biometricCredentialType != CREDENTIAL_TYPE_API_KEY) return false
        val normalizedUrl = normalize(serverUrl)
        if (normalizedUrl.isEmpty()) return false
        return tokenStore.biometricServerUrl == normalizedUrl
    }

    fun prefersApiKeyLogin(): Boolean {
        return canUnlock && tokenStore.biometricCredentialType == CREDENTIAL_TYPE_API_KEY
    }

    fun canEnrollBiometrics(): Boolean = isBiometricAvailable()

    fun saveCredentials(serverUrl: String, email: String, password: String) {
        saveSecret(serverUrl, email.trim().lowercase(), password, CREDENTIAL_TYPE_PASSWORD)
    }

    fun saveApiKeyCredentials(serverUrl: String, apiKey: String) {
        saveSecret(serverUrl, null, apiKey, CREDENTIAL_TYPE_API_KEY)
    }

    private fun saveSecret(serverUrl: String, email: String?, secret: String, credentialType: String) {
        val normalizedUrl = normalize(serverUrl)
        if (normalizedUrl.isEmpty() || secret.isBlank()) return
        if (credentialType == CREDENTIAL_TYPE_PASSWORD && email.isNullOrBlank()) return

        val cipher = createEncryptCipher()
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))

        tokenStore.biometricEnabled = true
        tokenStore.biometricServerUrl = normalizedUrl
        tokenStore.biometricEmail = email
        tokenStore.biometricPasswordHash = hashPassword(secret)
        tokenStore.biometricPasswordCipherText = encode(encrypted)
        tokenStore.biometricPasswordIv = null
        tokenStore.biometricPasswordScheme = PASSWORD_SCHEME_RSA_OAEP
        tokenStore.biometricCredentialType = credentialType
    }

    fun authenticateToSaveCredentials(
        activity: FragmentActivity,
        serverUrl: String,
        email: String,
        password: String,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        authenticateToSaveSecret(
            activity = activity,
            subtitle = "Use biometrics next time instead of typing your password",
            onSave = { saveCredentials(serverUrl, email, password) },
            onComplete = onComplete,
        )
    }

    fun authenticateToSaveApiKeyCredentials(
        activity: FragmentActivity,
        serverUrl: String,
        apiKey: String,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        authenticateToSaveSecret(
            activity = activity,
            subtitle = "Use biometrics next time instead of pasting your API key",
            onSave = { saveApiKeyCredentials(serverUrl, apiKey) },
            onComplete = onComplete,
        )
    }

    fun clearStoredCredentials() {
        tokenStore.biometricEnabled = false
        tokenStore.biometricServerUrl = null
        tokenStore.biometricEmail = null
        tokenStore.biometricPasswordHash = null
        tokenStore.biometricPasswordCipherText = null
        tokenStore.biometricPasswordIv = null
        tokenStore.biometricPasswordScheme = null
        tokenStore.biometricCredentialType = null
    }

    fun authenticateWithBiometrics(
        activity: FragmentActivity,
        negativeButtonText: String = "Use password",
        onSuccess: (String) -> Unit,
        onFailure: (String?) -> Unit,
    ) {
        if (!isBiometricAvailable()) {
            onFailure("Biometric authentication is not available.")
            return
        }

        val savedSecret = when (tokenStore.biometricCredentialType ?: CREDENTIAL_TYPE_PASSWORD) {
            CREDENTIAL_TYPE_API_KEY -> tokenStore.savedApiKey
            else -> tokenStore.savedLoginPassword
        }
        if (!savedSecret.isNullOrBlank()) {
            authenticateToUnlockSavedSecret(
                activity = activity,
                subtitle = "Use biometrics to unlock your saved credentials",
                negativeButtonText = negativeButtonText,
                onUnlock = { onSuccess(savedSecret) },
                onFailure = onFailure,
            )
            return
        }

        val ciphertext = tokenStore.biometricPasswordCipherText
            ?: return onFailure("No saved password available.")
        if (tokenStore.biometricPasswordScheme != PASSWORD_SCHEME_RSA_OAEP) {
            return onFailure("Saved biometric credentials need to be refreshed.")
        }

        val decryptCipher = runCatching { createDecryptCipher() }.getOrNull()
            ?: return onFailure("Unable to initialize biometric storage.")

        val cipherTextBytes = runCatching { decode(ciphertext) }.getOrNull()
            ?: return onFailure("Stored credentials are corrupted.")

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val decrypted = runCatching {
                        val useCipher = result.cryptoObject?.cipher ?: decryptCipher
                        val plainBytes = useCipher.doFinal(cipherTextBytes)
                        String(plainBytes, Charsets.UTF_8)
                    }.getOrNull()
                    if (decrypted.isNullOrBlank()) {
                        onFailure("Unable to unlock saved password.")
                    } else {
                        onSuccess(decrypted)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    onFailure("Biometric check did not match.")
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sign in")
            .setSubtitle("Use biometrics to unlock your saved credentials")
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(decryptCipher))
    }

    private fun isBiometricAvailable(): Boolean {
        return BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun authenticateToSaveSecret(
        activity: FragmentActivity,
        subtitle: String,
        onSave: () -> Unit,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        if (!isBiometricAvailable()) {
            onComplete(false, "Biometric authentication is not available.")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    runCatching {
                        onSave()
                    }.onSuccess {
                        onComplete(true, null)
                    }.onFailure {
                        onComplete(false, "Unable to save biometric login.")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val message = when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> null
                        else -> errString.toString()
                    }
                    onComplete(false, message)
                }

                override fun onAuthenticationFailed() = Unit
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Enable biometric login")
            .setSubtitle(subtitle)
            .setNegativeButtonText("Not now")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(promptInfo)
    }

    private fun authenticateToUnlockSavedSecret(
        activity: FragmentActivity,
        subtitle: String,
        negativeButtonText: String,
        onUnlock: () -> Unit,
        onFailure: (String?) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    onFailure("Biometric check did not match.")
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sign in")
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(promptInfo)
    }

    private fun normalize(url: String): String = url.trim().trimEnd('/')

    private fun createEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance(KEY_TRANSFORMATION)
        runCatching {
            cipher.init(Cipher.ENCRYPT_MODE, unrestrictedPublicKey(getOrCreateKeyPair().public))
            return cipher
        }

        deleteKeyPair()
        cipher.init(Cipher.ENCRYPT_MODE, unrestrictedPublicKey(getOrCreateKeyPair().public))
        return cipher
    }

    private fun createDecryptCipher(): Cipher {
        val key = getOrCreateKeyPair().private
        val cipher = Cipher.getInstance(KEY_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key)
        return cipher
    }

    private fun getOrCreateKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(KEY_STORE)
        keyStore.load(null)
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
        val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
        if (privateKey != null && publicKey != null) return KeyPair(publicKey, privateKey)

        val keyGen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).apply {
            setKeySize(KEY_SIZE_BITS)
            setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            setUserAuthenticationRequired(true)
            setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                @Suppress("DEPRECATION")
                setUserAuthenticationValidityDurationSeconds(-1)
            }
        }.build()

        keyGen.initialize(spec)
        return keyGen.generateKeyPair()
    }

    private fun unrestrictedPublicKey(publicKey: PublicKey): PublicKey {
        val spec = X509EncodedKeySpec(publicKey.encoded)
        return KeyFactory.getInstance(publicKey.algorithm).generatePublic(spec)
    }

    private fun deleteKeyPair() {
        runCatching {
            val keyStore = KeyStore.getInstance(KEY_STORE)
            keyStore.load(null)
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun hashPassword(password: String): String {
        return runCatching {
            val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format((it.toInt() and 0xFF)) }
        }.getOrElse { throw IllegalStateException("Cannot hash password", it) }
    }

    private fun encode(input: ByteArray): String = Base64.encodeToString(input, Base64.NO_WRAP)

    private fun decode(input: String?): ByteArray {
        if (input.isNullOrBlank()) throw IllegalArgumentException("Input is blank")
        return Base64.decode(input, Base64.NO_WRAP)
    }
}
