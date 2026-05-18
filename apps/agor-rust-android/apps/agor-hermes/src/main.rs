use dioxus::prelude::*;

use agor_hermes::models::HermesConfig;
use agor_hermes::session_store::HermesSessionStore;
use agor_hermes::ui::hermes_screen::HermesScreen;
use agor_hermes::ui::setup_screen::HermesSetupScreen;
use agor_lib::models::user::User;
#[cfg(target_arch = "wasm32")]
use agor_lib::models::user::UserRole;
use agor_lib::network::agor_client::{AgorClient, LoginResult};
use agor_lib::state::auth::{self, AuthState, AuthStore};
use agor_lib::state::chat::ChatStore;
use agor_lib::state::navigation::NavStore;
use agor_lib::state::storage::AppStorage;
use agor_lib::ui::app_shell::AppShell;
use agor_lib::ui::login::LoginScreen;
use agor_shared::logger::AppLogger;
use agor_shared::update::AppMetadata;

const MAIN_CSS: Asset = asset!("/assets/main.css");

fn main() {
    #[cfg(not(target_arch = "wasm32"))]
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
    let mut storage = use_context_provider(|| Signal::new(AppStorage::load()));
    let mut auth = use_context_provider(|| Signal::new(AuthStore::new()));
    let _nav = use_context_provider(|| Signal::new(NavStore::new()));
    let _chat = use_context_provider(|| Signal::new(ChatStore::new()));
    let _hermes_store = use_context_provider(|| Signal::new(HermesSessionStore::new()));
    let _meta = use_context_provider(|| {
        Signal::new(AppMetadata {
            version_code: env!("VERSION_CODE").parse().unwrap_or(0),
            version_name: env!("VERSION_NAME").to_string(),
            update_manifest_url: env!("UPDATE_MANIFEST_URL").to_string(),
        })
    });

    let mut hermes_config = use_signal(|| load_hermes_config());
    let mut active_tab = use_signal(|| AppTab::Agor);
    let mut dev_login_started = use_signal(|| false);

    use_effect(move || {
        if *dev_login_started.read() {
            return;
        }

        if let Some(token_login) = dev_token_login_config() {
            dev_login_started.set(true);
            let mut s = storage.write();
            let state = auth::persist_login(
                &mut s,
                token_login.base_url,
                "Sideview Dev",
                token_login.user.email.clone(),
                LoginResult {
                    access_token: token_login.access_token,
                    refresh_token: token_login.refresh_token,
                    user: token_login.user,
                },
                None,
                None,
                None,
                true,
                &AppLogger::new(),
            );
            drop(s);

            match state {
                Ok(AuthState::Authenticated { user }) => {
                    let mut store = auth.write();
                    store.user = Some(user.clone());
                    store.state = AuthState::Authenticated { user };
                    store.error = None;
                }
                Ok(state) => {
                    auth.write().state = state;
                }
                Err(e) => {
                    auth.write().error = Some(e);
                }
            }
            return;
        }

        let Some((url, api_key)) = dev_login_config() else {
            return;
        };

        dev_login_started.set(true);
        spawn(async move {
            let logger = AppLogger::new();
            let client = AgorClient::new(logger.clone());
            match auth::authenticate_with_api_key(&client, &logger, &url, &api_key).await {
                Ok((base_url, result)) => {
                    let mut s = storage.write();
                    let state = auth::persist_login(
                        &mut s,
                        base_url,
                        "Sideview Dev",
                        result.user.email.clone(),
                        result,
                        None,
                        None,
                        None,
                        true,
                        &logger,
                    );
                    drop(s);

                    match state {
                        Ok(AuthState::Authenticated { user }) => {
                            let mut store = auth.write();
                            store.user = Some(user.clone());
                            store.state = AuthState::Authenticated { user };
                            store.error = None;
                        }
                        Ok(state) => {
                            auth.write().state = state;
                        }
                        Err(e) => {
                            auth.write().error = Some(e);
                        }
                    }
                }
                Err(e) => {
                    auth.write().error = Some(e);
                }
            }
        });
    });

    let is_authenticated =
        use_memo(move || matches!(auth.read().state, AuthState::Authenticated { .. }));
    let dev_login_active = use_memo(move || dev_login_config().is_some());

    let hermes_configured = use_memo(move || {
        let c = hermes_config.read();
        c.base_url.is_some() && c.token.is_some()
    });

    rsx! {
        document::Stylesheet { href: MAIN_CSS }

        if !is_authenticated() && dev_login_active() {
            div { class: "login-screen",
                div { class: "login-card",
                    div { class: "login-header",
                        h1 { "Agor" }
                        p { class: "login-subtitle", "Connecting to Agor..." }
                    }
                }
            }
        } else if !is_authenticated() {
            LoginScreen {}
        } else {
            div { class: "app-root connected-shell",
                nav { class: "app-rail", "aria-label": "App surfaces",
                    button {
                        class: if *active_tab.read() == AppTab::Agor { "rail-item active" } else { "rail-item" },
                        title: "Agor",
                        onclick: move |_| active_tab.set(AppTab::Agor),
                        span { class: "rail-mark", "A" }
                    }
                    button {
                        class: if matches!(*active_tab.read(), AppTab::Hermes | AppTab::HermesSetup) { "rail-item active" } else { "rail-item" },
                        title: "Hermes",
                        onclick: move |_| {
                            if hermes_configured() {
                                active_tab.set(AppTab::Hermes);
                            } else {
                                active_tab.set(AppTab::HermesSetup);
                            }
                        },
                        span { class: "rail-mark", "H" }
                    }
                    div { class: "rail-spacer" }
                    button {
                        class: if *active_tab.read() == AppTab::HermesSetup { "rail-item active" } else { "rail-item" },
                        title: "Hermes settings",
                        onclick: move |_| active_tab.set(AppTab::HermesSetup),
                        span { class: "rail-mark", "⚙" }
                    }
                }

                main { class: "app-surface",
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
    }
}

struct DevTokenLogin {
    base_url: String,
    access_token: String,
    refresh_token: Option<String>,
    user: User,
}

fn dev_token_login_config() -> Option<DevTokenLogin> {
    #[cfg(target_arch = "wasm32")]
    {
        let search = web_sys::window()?.location().search().ok()?;
        let query = search.strip_prefix('?').unwrap_or(&search);
        let mut base_url = None;
        let mut access_token = None;
        let mut refresh_token = None;
        let mut user_id = None;
        let mut email = None;
        let mut name = None;

        for (key, value) in url::form_urlencoded::parse(query.as_bytes()) {
            match key.as_ref() {
                "agor_dev_base_url" => base_url = Some(value.into_owned()),
                "agor_dev_access_token" => access_token = Some(value.into_owned()),
                "agor_dev_refresh_token" => refresh_token = Some(value.into_owned()),
                "agor_dev_user_id" => user_id = Some(value.into_owned()),
                "agor_dev_user_email" => email = Some(value.into_owned()),
                "agor_dev_user_name" => name = Some(value.into_owned()),
                _ => {}
            }
        }

        let email = email.filter(|value| !value.is_empty());
        return Some(DevTokenLogin {
            base_url: base_url.filter(|value| !value.is_empty())?,
            access_token: access_token.filter(|value| !value.is_empty())?,
            refresh_token: refresh_token.filter(|value| !value.is_empty()),
            user: User {
                user_id: user_id.filter(|value| !value.is_empty())?,
                name: name.unwrap_or_else(|| "Dev User".to_string()),
                email,
                emoji: None,
                role: UserRole::Admin,
                unix_username: None,
                must_change_password: Some(false),
            },
        });
    }

    #[cfg(not(target_arch = "wasm32"))]
    None
}

fn dev_login_config() -> Option<(String, String)> {
    #[cfg(target_arch = "wasm32")]
    if let Some(config) = dev_login_config_from_query() {
        return Some(config);
    }

    let url = option_env!("AGOR_DEV_LOGIN_URL")?.to_string();
    let api_key = option_env!("AGOR_DEV_LOGIN_API_KEY")?.to_string();
    if url.is_empty() || api_key.is_empty() {
        None
    } else {
        Some((url, api_key))
    }
}

#[cfg(target_arch = "wasm32")]
fn dev_login_config_from_query() -> Option<(String, String)> {
    let search = web_sys::window()?.location().search().ok()?;
    let query = search.strip_prefix('?').unwrap_or(&search);
    let mut url = None;
    let mut api_key = None;

    for (key, value) in url::form_urlencoded::parse(query.as_bytes()) {
        match key.as_ref() {
            "agor_dev_login_url" => url = Some(value.into_owned()),
            "agor_dev_login_api_key" => api_key = Some(value.into_owned()),
            _ => {}
        }
    }

    match (url, api_key) {
        (Some(url), Some(api_key)) if !url.is_empty() && !api_key.is_empty() => {
            Some((url, api_key))
        }
        _ => None,
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
