fn main() {
    let version_code = std::env::var("VERSION_CODE").unwrap_or_else(|_| {
        std::process::Command::new("git")
            .args(["rev-list", "--count", "HEAD"])
            .output()
            .ok()
            .and_then(|o| String::from_utf8(o.stdout).ok())
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|| "0".to_string())
    });

    let version_name = std::env::var("VERSION_NAME").unwrap_or_else(|_| {
        std::process::Command::new("git")
            .args(["rev-parse", "--short", "HEAD"])
            .output()
            .ok()
            .and_then(|o| String::from_utf8(o.stdout).ok())
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|| "dev".to_string())
    });

    let repo =
        std::env::var("GITHUB_REPOSITORY").unwrap_or_else(|_| "Code-Fixxers/agor".to_string());

    let manifest_url = std::env::var("UPDATE_MANIFEST_URL").unwrap_or_else(|_| {
        format!(
            "https://github.com/{repo}/releases/download/rust-android-latest/agor-android-manifest.json"
        )
    });

    println!("cargo:rustc-env=VERSION_CODE={version_code}");
    println!("cargo:rustc-env=VERSION_NAME={version_name}");
    println!("cargo:rustc-env=UPDATE_MANIFEST_URL={manifest_url}");
}
