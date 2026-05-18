use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BiometricSecret {
    pub kind: String,
    pub server_url: String,
    pub email: Option<String>,
    pub secret: String,
}

#[cfg(target_arch = "wasm32")]
#[wasm_bindgen::prelude::wasm_bindgen(inline_js = r#"
function bridge() {
  return globalThis.AgorBiometrics || null;
}

function ensureCallbackHub() {
  if (!globalThis.__agorBiometricCallbacks) {
    globalThis.__agorBiometricCallbacks = {};
  }
  if (!globalThis.__agorBiometricResult) {
    globalThis.__agorBiometricResult = (id, ok, payload) => {
      const callback = globalThis.__agorBiometricCallbacks?.[id];
      if (!callback) return;
      delete globalThis.__agorBiometricCallbacks[id];
      callback(Boolean(ok), payload || "");
    };
  }
}

export function wasm_biometric_available() {
  const native = bridge();
  return Boolean(native && native.isAvailable && native.isAvailable());
}

export function wasm_biometric_has_secret(profileId) {
  const native = bridge();
  return Boolean(native && native.hasSecret && native.hasSecret(profileId));
}

export function wasm_biometric_save_secret(profileId, kind, serverUrl, email, secret) {
  const native = bridge();
  if (!native || !native.save) return false;
  return Boolean(native.save(profileId, kind, serverUrl, email || "", secret));
}

export function wasm_biometric_clear_secret(profileId) {
  const native = bridge();
  if (!native || !native.clear) return false;
  native.clear(profileId);
  return true;
}

export function wasm_biometric_unlock_secret(profileId) {
  ensureCallbackHub();
  const native = bridge();
  if (!native || !native.unlock) {
    return Promise.reject(new Error("Biometric unlock is not available in this build"));
  }
  const id = (globalThis.crypto && crypto.randomUUID)
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random()}`;
  return new Promise((resolve, reject) => {
    globalThis.__agorBiometricCallbacks[id] = (ok, payload) => {
      if (ok) {
        resolve(payload);
      } else {
        reject(new Error(payload || "Biometric unlock failed"));
      }
    };
    native.unlock(id, profileId);
  });
}
"#)]
extern "C" {
    fn wasm_biometric_available() -> bool;
    fn wasm_biometric_has_secret(profile_id: &str) -> bool;
    fn wasm_biometric_save_secret(
        profile_id: &str,
        kind: &str,
        server_url: &str,
        email: &str,
        secret: &str,
    ) -> bool;
    fn wasm_biometric_clear_secret(profile_id: &str) -> bool;

    #[wasm_bindgen::prelude::wasm_bindgen(catch)]
    async fn wasm_biometric_unlock_secret(
        profile_id: &str,
    ) -> Result<wasm_bindgen::JsValue, wasm_bindgen::JsValue>;
}

#[cfg(target_arch = "wasm32")]
pub fn is_biometric_available() -> bool {
    wasm_biometric_available()
}

#[cfg(not(target_arch = "wasm32"))]
pub fn is_biometric_available() -> bool {
    false
}

#[cfg(target_arch = "wasm32")]
pub fn has_biometric_secret(profile_id: &str) -> bool {
    wasm_biometric_has_secret(profile_id)
}

#[cfg(not(target_arch = "wasm32"))]
pub fn has_biometric_secret(_profile_id: &str) -> bool {
    false
}

#[cfg(target_arch = "wasm32")]
pub fn save_biometric_secret(secret: &BiometricSecret, profile_id: &str) -> Result<(), String> {
    let ok = wasm_biometric_save_secret(
        profile_id,
        &secret.kind,
        &secret.server_url,
        secret.email.as_deref().unwrap_or_default(),
        &secret.secret,
    );
    if ok {
        Ok(())
    } else {
        Err("Biometric storage is not available in this build".to_string())
    }
}

#[cfg(not(target_arch = "wasm32"))]
pub fn save_biometric_secret(_secret: &BiometricSecret, _profile_id: &str) -> Result<(), String> {
    Err("Biometric storage is not available on this platform".to_string())
}

#[cfg(target_arch = "wasm32")]
pub async fn unlock_biometric_secret(profile_id: &str) -> Result<BiometricSecret, String> {
    let value = wasm_biometric_unlock_secret(profile_id)
        .await
        .map_err(js_error_to_string)?;
    let json = value
        .as_string()
        .ok_or_else(|| "Biometric bridge returned a non-string payload".to_string())?;
    serde_json::from_str(&json).map_err(|e| format!("Invalid biometric payload: {e}"))
}

#[cfg(not(target_arch = "wasm32"))]
pub async fn unlock_biometric_secret(_profile_id: &str) -> Result<BiometricSecret, String> {
    Err("Biometric unlock is not available on this platform".to_string())
}

#[cfg(target_arch = "wasm32")]
pub fn clear_biometric_secret(profile_id: &str) -> bool {
    wasm_biometric_clear_secret(profile_id)
}

#[cfg(not(target_arch = "wasm32"))]
pub fn clear_biometric_secret(_profile_id: &str) -> bool {
    false
}

#[cfg(target_arch = "wasm32")]
fn js_error_to_string(value: wasm_bindgen::JsValue) -> String {
    value
        .as_string()
        .unwrap_or_else(|| "Biometric bridge failed".to_string())
}
