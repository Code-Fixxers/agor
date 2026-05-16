use dioxus::prelude::*;

use agor_hermes::models::HermesConfig;
use agor_hermes::session_store::HermesSessionStore;
use agor_hermes::ui::hermes_screen::HermesScreen;
use agor_hermes::ui::setup_screen::HermesSetupScreen;

fn main() {
    tracing_subscriber::fmt()
        .with_env_filter("hermes=debug,info")
        .init();

    dioxus::launch(App);
}

#[derive(Debug, Clone, PartialEq)]
enum AppRoute {
    Setup,
    Chat,
}

#[component]
fn App() -> Element {
    let _hermes_store = use_context_provider(|| Signal::new(HermesSessionStore::new()));

    let mut config = use_signal(|| load_config());

    let is_configured = use_memo(move || {
        let c = config.read();
        c.base_url.is_some() && c.token.is_some()
    });

    let mut route = use_signal(|| {
        if load_config().base_url.is_some() {
            AppRoute::Chat
        } else {
            AppRoute::Setup
        }
    });

    rsx! {
        div { class: "hermes-app-root",
            match route.read().clone() {
                AppRoute::Setup => rsx! {
                    HermesSetupScreen {
                        config: config.read().clone(),
                        on_save: move |new_config: HermesConfig| {
                            save_config(&new_config);
                            config.set(new_config);
                            route.set(AppRoute::Chat);
                        },
                        on_close: move |_| {
                            if is_configured() {
                                route.set(AppRoute::Chat);
                            }
                        },
                    }
                },
                AppRoute::Chat => rsx! {
                    HermesScreen {
                        config: config.read().clone(),
                        on_open_settings: move |_| route.set(AppRoute::Setup),
                    }
                },
            }
        }
    }
}

fn config_path() -> std::path::PathBuf {
    dirs::data_local_dir()
        .unwrap_or_else(|| std::path::PathBuf::from("."))
        .join("hermes")
        .join("config.json")
}

fn load_config() -> HermesConfig {
    let path = config_path();
    if path.exists() {
        std::fs::read_to_string(&path)
            .ok()
            .and_then(|data| serde_json::from_str(&data).ok())
            .unwrap_or_default()
    } else {
        HermesConfig::default()
    }
}

fn save_config(config: &HermesConfig) {
    let path = config_path();
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if let Ok(data) = serde_json::to_string_pretty(config) {
        let _ = std::fs::write(&path, data);
    }
}
