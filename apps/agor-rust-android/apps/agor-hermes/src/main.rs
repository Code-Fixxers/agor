use dioxus::prelude::*;

use agor_lib::state::auth::{AuthState, AuthStore};
use agor_lib::state::chat::ChatStore;
use agor_lib::state::navigation::NavStore;
use agor_lib::state::storage::AppStorage;
use agor_lib::ui::app_shell::AppShell;
use agor_lib::ui::login::LoginScreen;
use agor_hermes::models::HermesConfig;
use agor_hermes::session_store::HermesSessionStore;
use agor_hermes::ui::hermes_screen::HermesScreen;
use agor_hermes::ui::setup_screen::HermesSetupScreen;

fn main() {
    tracing_subscriber::fmt()
        .with_env_filter("agor=debug,info")
        .init();

    dioxus::launch(App);
}

#[derive(Debug, Clone, PartialEq)]
enum AppTab {
    Agor,
    Hermes,
    HermesSetup,
}

#[component]
fn App() -> Element {
    let _storage = use_context_provider(|| Signal::new(AppStorage::load()));
    let auth = use_context_provider(|| Signal::new(AuthStore::new()));
    let _nav = use_context_provider(|| Signal::new(NavStore::new()));
    let _chat = use_context_provider(|| Signal::new(ChatStore::new()));
    let _hermes_store = use_context_provider(|| Signal::new(HermesSessionStore::new()));

    let mut hermes_config = use_signal(|| load_hermes_config());
    let mut active_tab = use_signal(|| AppTab::Agor);

    let is_authenticated = use_memo(move || {
        matches!(auth.read().state, AuthState::Authenticated { .. })
    });

    let hermes_configured = use_memo(move || {
        let c = hermes_config.read();
        c.base_url.is_some() && c.token.is_some()
    });

    if !is_authenticated() {
        return rsx! { LoginScreen {} };
    }

    rsx! {
        div { class: "app-root",
            // Tab bar at the bottom
            div { class: "tab-bar",
                button {
                    class: if *active_tab.read() == AppTab::Agor { "tab active" } else { "tab" },
                    onclick: move |_| active_tab.set(AppTab::Agor),
                    "Agor"
                }
                button {
                    class: if matches!(*active_tab.read(), AppTab::Hermes | AppTab::HermesSetup) { "tab active" } else { "tab" },
                    onclick: move |_| {
                        if hermes_configured() {
                            active_tab.set(AppTab::Hermes);
                        } else {
                            active_tab.set(AppTab::HermesSetup);
                        }
                    },
                    "Hermes"
                }
            }

            // Content
            match active_tab.read().clone() {
                AppTab::Agor => rsx! {
                    AppShell {}
                },
                AppTab::Hermes => rsx! {
                    HermesScreen {
                        config: hermes_config.read().clone(),
                        on_open_settings: move |_| active_tab.set(AppTab::HermesSetup),
                    }
                },
                AppTab::HermesSetup => rsx! {
                    HermesSetupScreen {
                        config: hermes_config.read().clone(),
                        on_save: move |new_config: HermesConfig| {
                            save_hermes_config(&new_config);
                            hermes_config.set(new_config);
                            active_tab.set(AppTab::Hermes);
                        },
                        on_close: move |_| {
                            if hermes_configured() {
                                active_tab.set(AppTab::Hermes);
                            } else {
                                active_tab.set(AppTab::Agor);
                            }
                        },
                    }
                },
            }
        }
    }
}

fn hermes_config_path() -> std::path::PathBuf {
    dirs::data_local_dir()
        .unwrap_or_else(|| std::path::PathBuf::from("."))
        .join("agor-android")
        .join("hermes_config.json")
}

fn load_hermes_config() -> HermesConfig {
    let path = hermes_config_path();
    if path.exists() {
        std::fs::read_to_string(&path)
            .ok()
            .and_then(|data| serde_json::from_str(&data).ok())
            .unwrap_or_default()
    } else {
        HermesConfig::default()
    }
}

fn save_hermes_config(config: &HermesConfig) {
    let path = hermes_config_path();
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if let Ok(data) = serde_json::to_string_pretty(config) {
        let _ = std::fs::write(&path, data);
    }
}
