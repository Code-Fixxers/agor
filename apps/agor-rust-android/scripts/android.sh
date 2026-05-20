#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOL_ROOT="$ROOT/target/dev-tools"
BIN_DIR="$TOOL_ROOT/bin"
DX_VERSION="${DX_VERSION:-0.6.3}"
PACKAGE="${AGOR_RUST_ANDROID_PACKAGE:-agor-hermes-app}"
ARCH="${AGOR_RUST_ANDROID_ARCH:-arm64}"
COMPILE_SDK="${AGOR_RUST_ANDROID_COMPILE_SDK:-35}"
TARGET_SDK="${AGOR_RUST_ANDROID_TARGET_SDK:-35}"
BUILD_TOOLS="${AGOR_RUST_ANDROID_BUILD_TOOLS:-35.0.0}"
BUILD_PROFILE="${AGOR_RUST_ANDROID_PROFILE:-}"
MODE="${1:-build}"

if [[ "$MODE" == "build" || "$MODE" == "install" || "$MODE" == "run" ]]; then
  shift || true
fi

export PATH="$BIN_DIR:$PATH"
export NO_DOWNLOADS=1
export XDG_DATA_HOME="${XDG_DATA_HOME:-$ROOT/target/xdg-data}"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-$ROOT/target/xdg-cache}"
export RUSTC_BOOTSTRAP="${RUSTC_BOOTSTRAP:-1}"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="${CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS:--C link-arg=-Wl,-z,max-page-size=16384}"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_RUSTFLAGS="${CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_RUSTFLAGS:--C link-arg=-Wl,-z,max-page-size=16384}"
export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS="${CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS:--C link-arg=-Wl,-z,max-page-size=16384}"
export CARGO_TARGET_I686_LINUX_ANDROID_RUSTFLAGS="${CARGO_TARGET_I686_LINUX_ANDROID_RUSTFLAGS:--C link-arg=-Wl,-z,max-page-size=16384}"

configure_openssl() {
  if ldconfig -p 2>/dev/null | grep -q 'libssl\.so\.3'; then
    return
  fi

  local ssl_lib
  for ssl_lib in /run/current-system/sw/lib/libssl.so.3 /nix/store/*-openssl-*/lib/libssl.so.3; do
    if [[ -e "$ssl_lib" ]]; then
      export LD_LIBRARY_PATH="$(dirname "$ssl_lib"):${LD_LIBRARY_PATH:-}"
      return
    fi
  done
}

ensure_cargo_tool() {
  local bin="$1"
  local crate="$2"
  local version="$3"

  if command -v "$bin" >/dev/null 2>&1 && "$bin" --version | grep -q "$version"; then
    return
  fi

  if [[ "$crate" == "dioxus-cli" ]]; then
    cargo install --git https://github.com/DioxusLabs/dioxus \
      --tag "v$version" "$crate" --locked --root "$TOOL_ROOT"
  else
    cargo install "$crate" --version "$version" --locked --root "$TOOL_ROOT"
  fi
}

configure_android_rustflags() {
  case " ${RUSTFLAGS:-} " in
    *"max-page-size=16384"*) ;;
    *) export RUSTFLAGS="${RUSTFLAGS:-} -Clink-arg=-Wl,-z,max-page-size=16384" ;;
  esac
}

patch_generated_android_project() {
  local gradle_root="$1"
  local app_gradle="$gradle_root/app/build.gradle.kts"
  local manifest="$gradle_root/app/src/main/AndroidManifest.xml"
  local wry_activity="$gradle_root/app/src/main/kotlin/dev/dioxus/main/WryActivity.kt"
  local main_activity="$gradle_root/app/src/main/kotlin/dev/dioxus/main/MainActivity.kt"
  local build_config_alias="$gradle_root/app/src/main/kotlin/dev/dioxus/main/BuildConfig.kt"
  local biometric_bridge="$gradle_root/app/src/main/kotlin/dev/dioxus/main/AgorBiometricBridge.kt"

  if [[ ! -f "$app_gradle" ]]; then
    echo "Missing generated Gradle file: $app_gradle" >&2
    return 1
  fi

  perl -0pi -e "s/compileSdk = \\d+/compileSdk = $COMPILE_SDK/" "$app_gradle"
  perl -0pi -e "s/targetSdk = \\d+/targetSdk = $TARGET_SDK/" "$app_gradle"

  if ! grep -q 'buildToolsVersion' "$app_gradle"; then
    perl -0pi -e "s/(compileSdk = $COMPILE_SDK\\n)/\$1    buildToolsVersion = \"$BUILD_TOOLS\"\\n/" "$app_gradle"
  fi

  if ! grep -q 'androidx.biometric:biometric' "$app_gradle"; then
    perl -0pi -e 's#(dependencies \{\n)#$1    implementation("androidx.biometric:biometric:1.1.0")\n#' "$app_gradle"
  fi

  if [[ -f "$manifest" ]] && ! grep -q 'usesCleartextTraffic' "$manifest"; then
    perl -0pi -e 's/<application /<application android:usesCleartextTraffic="true" /' "$manifest"
  fi

  if [[ -f "$manifest" ]]; then
    for permission in \
      android.permission.RECORD_AUDIO \
      android.permission.MODIFY_AUDIO_SETTINGS \
      android.permission.USE_BIOMETRIC
    do
      if ! grep -q "$permission" "$manifest"; then
        perl -0pi -e "s#(<manifest[^>]*>\\s*)#\$1    <uses-permission android:name=\"$permission\" />\\n#" "$manifest"
      fi
    done
  fi

  if [[ -f "$wry_activity" ]]; then
    perl -0pi -e 's/return info\.versionName$/return info.versionName ?: ""/mg' "$wry_activity"
  fi

  local android_namespace
  android_namespace="$(sed -n 's/.*namespace *= *"\([^"]*\)".*/\1/p' "$app_gradle" | head -n1)"
  if [[ -z "$android_namespace" ]]; then
    echo "Could not determine generated Android namespace from $app_gradle" >&2
    return 1
  fi

  cat >"$build_config_alias" <<KOTLIN
package dev.dioxus.main

import ${android_namespace}.BuildConfig as AppBuildConfig

typealias BuildConfig = AppBuildConfig
KOTLIN

  if [[ -f "$main_activity" ]]; then
    cat >"$main_activity" <<'KOTLIN'
package dev.dioxus.main

import android.webkit.WebView

class MainActivity : WryActivity() {
    override fun onWebViewCreate(webView: WebView) {
        webView.addJavascriptInterface(AgorBiometricBridge(this, webView), "AgorBiometrics")
    }
}
KOTLIN
  fi

  cat >"$biometric_bridge" <<'KOTLIN'
package dev.dioxus.main

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

private const val AGOR_KEY_ALIAS = "agor_rust_android_biometric_v1"
private const val AGOR_KEY_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
private const val AGOR_KEY_STORE = "AndroidKeyStore"

class AgorBiometricBridge(
    private val activity: WryActivity,
    private val webView: WebView,
) {
    private val prefs = activity.getSharedPreferences("agor_biometric_secrets", 0)

    @JavascriptInterface
    fun isAvailable(): Boolean {
        return BiometricManager.from(activity).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    @JavascriptInterface
    fun hasSecret(profileId: String): Boolean {
        return prefs.contains(key(profileId, "ciphertext"))
    }

    @JavascriptInterface
    fun save(profileId: String, kind: String, serverUrl: String, email: String, secret: String): Boolean {
        if (profileId.isBlank() || secret.isBlank()) return false
        return runCatching {
            val cipher = Cipher.getInstance(AGOR_KEY_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, unrestrictedPublicKey(getOrCreateKeyPair().public))
            val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(key(profileId, "kind"), kind)
                .putString(key(profileId, "server_url"), serverUrl)
                .putString(key(profileId, "email"), email)
                .putString(key(profileId, "ciphertext"), Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply()
            true
        }.getOrElse {
            false
        }
    }

    @JavascriptInterface
    fun clear(profileId: String) {
        prefs.edit()
            .remove(key(profileId, "kind"))
            .remove(key(profileId, "server_url"))
            .remove(key(profileId, "email"))
            .remove(key(profileId, "ciphertext"))
            .apply()
    }

    @JavascriptInterface
    fun unlock(callbackId: String, profileId: String) {
        if (!isAvailable()) {
            complete(callbackId, false, "Biometric authentication is not available.")
            return
        }

        val ciphertext = prefs.getString(key(profileId, "ciphertext"), null)
        if (ciphertext.isNullOrBlank()) {
            complete(callbackId, false, "No biometric login is saved for this profile.")
            return
        }

        val cipher = try {
            createDecryptCipher()
        } catch (_: KeyPermanentlyInvalidatedException) {
            clear(profileId)
            complete(callbackId, false, "Biometric enrollment changed. Login again to refresh saved credentials.")
            return
        } catch (_: Exception) {
            complete(callbackId, false, "Unable to initialize biometric unlock.")
            return
        }

        val encrypted = runCatching { Base64.decode(ciphertext, Base64.NO_WRAP) }.getOrNull()
        if (encrypted == null) {
            clear(profileId)
            complete(callbackId, false, "Saved biometric credentials are corrupted.")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val decrypted = runCatching {
                        val useCipher = result.cryptoObject?.cipher ?: cipher
                        String(useCipher.doFinal(encrypted), Charsets.UTF_8)
                    }.getOrNull()

                    if (decrypted.isNullOrBlank()) {
                        complete(callbackId, false, "Unable to unlock saved credentials.")
                        return
                    }

                    val payload = JSONObject()
                        .put("kind", prefs.getString(key(profileId, "kind"), "password"))
                        .put("server_url", prefs.getString(key(profileId, "server_url"), ""))
                        .put("email", prefs.getString(key(profileId, "email"), ""))
                        .put("secret", decrypted)
                        .toString()
                    complete(callbackId, true, payload)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    complete(callbackId, false, errString.toString())
                }

                override fun onAuthenticationFailed() {
                    complete(callbackId, false, "Biometric check did not match.")
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sign in to Agor")
            .setSubtitle("Use biometrics to unlock saved login credentials")
            .setNegativeButtonText("Use password")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    private fun complete(callbackId: String, ok: Boolean, payload: String) {
        val script = "window.__agorBiometricResult && window.__agorBiometricResult(" +
            JSONObject.quote(callbackId) + "," +
            ok.toString() + "," +
            JSONObject.quote(payload) +
            ")"
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun key(profileId: String, suffix: String): String = profileId.trim() + "." + suffix

    private fun createDecryptCipher(): Cipher {
        val key = getOrCreateKeyPair().private
        val cipher = Cipher.getInstance(AGOR_KEY_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key)
        return cipher
    }

    private fun getOrCreateKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(AGOR_KEY_STORE)
        keyStore.load(null)
        val privateKey = keyStore.getKey(AGOR_KEY_ALIAS, null) as? PrivateKey
        val publicKey = keyStore.getCertificate(AGOR_KEY_ALIAS)?.publicKey
        if (privateKey != null && publicKey != null) return KeyPair(publicKey, privateKey)

        val keyGen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, AGOR_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            AGOR_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).apply {
            setKeySize(2048)
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
}
KOTLIN
}

build_apk() {
  configure_openssl
  ensure_cargo_tool dx dioxus-cli "$DX_VERSION"
  configure_openssl
  configure_android_rustflags

  cd "$ROOT"

  local profile_args=()
  local clean_gradle=false
  if [[ -n "$BUILD_PROFILE" ]]; then
    profile_args=(--profile "$BUILD_PROFILE")
    clean_gradle=true
  elif [[ "${CI:-}" == "true" ]]; then
    profile_args=(--profile android-ci)
    clean_gradle=true
  fi

  set +e
  dx build \
    --platform android \
    --package "$PACKAGE" \
    --arch "$ARCH" \
    --device true \
    "${profile_args[@]}" \
    -- "$@" -Z build-std=std,panic_abort
  local dx_status=$?
  set -e

  local gradle_root="$ROOT/target/dx/$PACKAGE/debug/android/app"
  if [[ $dx_status -ne 0 ]]; then
    if [[ ! -d "$gradle_root" ]]; then
      return "$dx_status"
    fi
    echo "dx generated Android project but Gradle assembly failed; patching generated SDK settings."
  fi

  patch_generated_android_project "$gradle_root"

  cd "$gradle_root"
  if [[ "$clean_gradle" == "true" ]]; then
    ./gradlew :app:clean :app:assembleDebug
  else
    ./gradlew :app:assembleDebug
  fi

  local apk="$gradle_root/app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -f "$apk" ]]; then
    echo "APK was not produced at: $apk" >&2
    return 1
  fi

  echo "$apk"
}

install_apk() {
  local apk
  apk="$(build_apk "$@" | tail -n 1)"
  adb install -r "$apk"
}

run_app() {
  install_apk "$@"
  adb shell am start -n com.example.AgorHermesApp/dev.dioxus.main.MainActivity
}

case "$MODE" in
  build)
    build_apk "$@"
    ;;
  install)
    install_apk "$@"
    ;;
  run)
    run_app "$@"
    ;;
  *)
    echo "Usage: $0 [build|install|run] [cargo args...]" >&2
    exit 2
    ;;
esac
