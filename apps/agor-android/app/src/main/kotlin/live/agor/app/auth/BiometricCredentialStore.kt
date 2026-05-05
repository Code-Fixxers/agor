package live.agor.app.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "agor_password_vault"
private const val KEY_TRANSFORMATION = "AES/GCM/NoPadding"
private const val KEY_TAG_BITS = 128
private const val KEY_STORE = "AndroidKeyStore"

class BiometricCredentialStore(
    private val context: Context,
    private val tokenStore: SecureTokenStore,
) {

    val canUnlock: Boolean
        get() = tokenStore.biometricEnabled &&
            tokenStore.biometricPasswordCipherText != null &&
            tokenStore.biometricPasswordIv != null &&
            tokenStore.biometricPasswordHash != null

    fun canUnlockFor(serverUrl: String, email: String): Boolean {
        if (!canUnlock) return false
        val normalizedUrl = normalize(serverUrl)
        val normalizedEmail = email.trim().lowercase()
        if (normalizedUrl.isEmpty() || normalizedEmail.isEmpty()) return false
        return tokenStore.biometricServerUrl == normalizedUrl &&
            tokenStore.biometricEmail == normalizedEmail
    }

    fun saveCredentials(serverUrl: String, email: String, password: String) {
        val normalizedUrl = normalize(serverUrl)
        val normalizedEmail = email.trim().lowercase()
        if (normalizedUrl.isEmpty() || normalizedEmail.isEmpty() || password.isBlank()) return

        val cipher = createEncryptCipher()
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))

        tokenStore.biometricEnabled = true
        tokenStore.biometricServerUrl = normalizedUrl
        tokenStore.biometricEmail = normalizedEmail
        tokenStore.biometricPasswordHash = hashPassword(password)
        tokenStore.biometricPasswordCipherText = encode(encrypted)
        tokenStore.biometricPasswordIv = encode(cipher.iv)
    }

    fun clearStoredCredentials() {
        tokenStore.biometricEnabled = false
        tokenStore.biometricServerUrl = null
        tokenStore.biometricEmail = null
        tokenStore.biometricPasswordHash = null
        tokenStore.biometricPasswordCipherText = null
        tokenStore.biometricPasswordIv = null
    }

    fun authenticateWithBiometrics(
        activity: FragmentActivity,
        onSuccess: (String) -> Unit,
        onFailure: (String?) -> Unit,
    ) {
        if (!isBiometricAvailable()) {
            onFailure("Biometric authentication is not available.")
            return
        }

        val ciphertext = tokenStore.biometricPasswordCipherText
            ?: return onFailure("No saved password available.")
        val iv = runCatching { decode(tokenStore.biometricPasswordIv) }.getOrNull()
            ?: return onFailure("Stored credentials are corrupted.")

        val decryptCipher = runCatching { createDecryptCipher(iv) }.getOrNull()
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
            .setNegativeButtonText("Use password")
            .build()
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(decryptCipher))
    }

    private fun isBiometricAvailable(): Boolean {
        return BiometricManager.from(context).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun normalize(url: String): String = url.trim().trimEnd('/')

    private fun createEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance(KEY_TRANSFORMATION)
        runCatching {
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            return cipher
        }

        deleteSecretKey()
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return cipher
    }

    private fun createDecryptCipher(iv: ByteArray): Cipher {
        val key = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(KEY_TRANSFORMATION)
        val spec = GCMParameterSpec(KEY_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEY_STORE)
        keyStore.load(null)
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setRandomizedEncryptionRequired(true)
            setUserAuthenticationRequired(false)
        }.build()

        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun deleteSecretKey() {
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
