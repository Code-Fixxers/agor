use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;

use crate::models::server_profile::{ProfileCredentials, ServerProfile};

fn storage_dir() -> PathBuf {
    let base = dirs::data_local_dir().unwrap_or_else(|| PathBuf::from("."));
    base.join("agor-android")
}

fn ensure_dir() {
    let dir = storage_dir();
    std::fs::create_dir_all(&dir).ok();
}

fn profiles_path() -> PathBuf {
    storage_dir().join("profiles.json")
}

fn credentials_path() -> PathBuf {
    storage_dir().join("credentials.json")
}

fn prefs_path() -> PathBuf {
    storage_dir().join("preferences.json")
}

fn sidebar_cache_path() -> PathBuf {
    storage_dir().join("sidebar_cache.json")
}

fn drafts_path() -> PathBuf {
    storage_dir().join("drafts.json")
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Preferences {
    #[serde(default)]
    pub active_profile_id: Option<String>,
    #[serde(default)]
    pub collapsed_boards: HashSet<String>,
    #[serde(default)]
    pub collapsed_worktrees: HashSet<String>,
    #[serde(default)]
    pub favorites: HashSet<String>,
    #[serde(default)]
    pub drawer_session_filter: String,
}

impl Default for Preferences {
    fn default() -> Self {
        Self {
            active_profile_id: None,
            collapsed_boards: HashSet::new(),
            collapsed_worktrees: HashSet::new(),
            favorites: HashSet::new(),
            drawer_session_filter: "7d".to_string(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct AppStorage {
    pub profiles: Vec<ServerProfile>,
    pub credentials: HashMap<String, ProfileCredentials>,
    pub preferences: Preferences,
    pub drafts: HashMap<String, String>,
}

impl AppStorage {
    pub fn load() -> Self {
        ensure_dir();

        let profiles: Vec<ServerProfile> = std::fs::read_to_string(profiles_path())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();

        let credentials: HashMap<String, ProfileCredentials> =
            std::fs::read_to_string(credentials_path())
                .ok()
                .and_then(|s| serde_json::from_str(&s).ok())
                .unwrap_or_default();

        let preferences: Preferences = std::fs::read_to_string(prefs_path())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();

        let drafts: HashMap<String, String> = std::fs::read_to_string(drafts_path())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();

        Self {
            profiles,
            credentials,
            preferences,
            drafts,
        }
    }

    pub fn save_profiles(&self) {
        ensure_dir();
        if let Ok(json) = serde_json::to_string_pretty(&self.profiles) {
            std::fs::write(profiles_path(), json).ok();
        }
    }

    pub fn save_credentials(&self) {
        ensure_dir();
        if let Ok(json) = serde_json::to_string_pretty(&self.credentials) {
            std::fs::write(credentials_path(), json).ok();
        }
    }

    pub fn save_preferences(&self) {
        ensure_dir();
        if let Ok(json) = serde_json::to_string_pretty(&self.preferences) {
            std::fs::write(prefs_path(), json).ok();
        }
    }

    pub fn save_drafts(&self) {
        ensure_dir();
        if let Ok(json) = serde_json::to_string_pretty(&self.drafts) {
            std::fs::write(drafts_path(), json).ok();
        }
    }

    pub fn active_profile(&self) -> Option<&ServerProfile> {
        let active_id = self.preferences.active_profile_id.as_deref()?;
        self.profiles.iter().find(|p| p.id == active_id)
    }

    pub fn default_profile(&self) -> Option<&ServerProfile> {
        self.profiles.iter().find(|p| p.is_default)
    }

    pub fn active_credentials(&self) -> Option<&ProfileCredentials> {
        let active_id = self.preferences.active_profile_id.as_deref()?;
        self.credentials.get(active_id)
    }

    pub fn set_active_profile(&mut self, id: &str) {
        self.preferences.active_profile_id = Some(id.to_string());
        self.save_preferences();
    }

    pub fn save_profile_credentials(&mut self, profile_id: &str, creds: ProfileCredentials) {
        self.credentials.insert(profile_id.to_string(), creds);
        self.save_credentials();
    }

    pub fn add_profile(&mut self, profile: ServerProfile) {
        if self.profiles.is_empty() {
            let mut p = profile;
            p.is_default = true;
            self.profiles.push(p);
        } else {
            self.profiles.push(profile);
        }
        self.save_profiles();
    }

    pub fn remove_profile(&mut self, id: &str) {
        self.profiles.retain(|p| p.id != id);
        self.credentials.remove(id);
        if self.preferences.active_profile_id.as_deref() == Some(id) {
            self.preferences.active_profile_id = self.profiles.first().map(|p| p.id.clone());
        }
        self.save_profiles();
        self.save_credentials();
        self.save_preferences();
    }

    pub fn toggle_favorite(&mut self, session_id: &str) -> bool {
        let is_fav = if self.preferences.favorites.contains(session_id) {
            self.preferences.favorites.remove(session_id);
            false
        } else {
            self.preferences.favorites.insert(session_id.to_string());
            true
        };
        self.save_preferences();
        is_fav
    }

    pub fn toggle_board_collapsed(&mut self, board_id: &str) {
        if !self.preferences.collapsed_boards.remove(board_id) {
            self.preferences.collapsed_boards.insert(board_id.to_string());
        }
        self.save_preferences();
    }

    pub fn toggle_worktree_collapsed(&mut self, worktree_id: &str) {
        if !self.preferences.collapsed_worktrees.remove(worktree_id) {
            self.preferences
                .collapsed_worktrees
                .insert(worktree_id.to_string());
        }
        self.save_preferences();
    }

    pub fn get_draft(&self, session_id: &str) -> String {
        self.drafts.get(session_id).cloned().unwrap_or_default()
    }

    pub fn set_draft(&mut self, session_id: &str, text: &str) {
        if text.is_empty() {
            self.drafts.remove(session_id);
        } else {
            self.drafts.insert(session_id.to_string(), text.to_string());
        }
        self.save_drafts();
    }

    pub fn clear_all_credentials(&mut self) {
        self.credentials.clear();
        self.preferences.active_profile_id = None;
        self.save_credentials();
        self.save_preferences();
    }
}
