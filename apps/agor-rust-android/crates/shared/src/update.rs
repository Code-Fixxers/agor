use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, PartialEq)]
pub struct AppMetadata {
    pub version_code: u64,
    pub version_name: String,
    pub update_manifest_url: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct UpdateManifest {
    #[serde(rename = "versionCode")]
    pub version_code: u64,
    #[serde(rename = "versionName")]
    pub version_name: String,
    pub commit: String,
    #[serde(rename = "apkUrl")]
    pub apk_url: String,
    #[serde(rename = "sizeBytes")]
    pub size_bytes: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub enum UpdateState {
    Idle,
    Checking,
    UpToDate,
    Available(UpdateManifest),
    Downloading,
    Ready(PathBuf),
    Failed(String),
}

pub async fn check_for_update(
    manifest_url: &str,
    current_version_code: u64,
) -> Result<Option<UpdateManifest>, String> {
    let client = reqwest::Client::new();

    let resp = client
        .get(manifest_url)
        .header("Cache-Control", "no-cache")
        .send()
        .await
        .map_err(|e| format!("Network error: {e}"))?;

    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }

    let manifest: UpdateManifest = resp.json().await.map_err(|e| format!("Parse error: {e}"))?;

    if manifest.version_code > current_version_code {
        Ok(Some(manifest))
    } else {
        Ok(None)
    }
}

pub async fn download_apk(manifest: &UpdateManifest) -> Result<PathBuf, String> {
    let download_dir = dirs::cache_dir()
        .unwrap_or_else(|| PathBuf::from("/tmp"))
        .join("agor-updates");

    std::fs::create_dir_all(&download_dir).map_err(|e| format!("Cannot create update dir: {e}"))?;

    let apk_path = download_dir.join(format!("{}.apk", manifest.version_code));

    if apk_path.exists() {
        if let Ok(meta) = std::fs::metadata(&apk_path) {
            if meta.len() == manifest.size_bytes {
                return Ok(apk_path);
            }
        }
        let _ = std::fs::remove_file(&apk_path);
    }

    let client = reqwest::Client::new();
    let resp = client
        .get(&manifest.apk_url)
        .send()
        .await
        .map_err(|e| format!("Download error: {e}"))?;

    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }

    let bytes = resp.bytes().await.map_err(|e| format!("Read error: {e}"))?;

    std::fs::write(&apk_path, &bytes).map_err(|e| format!("Write error: {e}"))?;

    Ok(apk_path)
}
