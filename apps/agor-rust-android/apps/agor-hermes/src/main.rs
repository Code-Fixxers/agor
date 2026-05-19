use dioxus::prelude::*;

use agor_hermes::client::HermesClient;
use agor_hermes::models::{HermesConfig, DEFAULT_MODEL, DEFAULT_WEB_UI_URL};
use agor_hermes::session_store::HermesSessionStore;
use agor_hermes::ui::webui_screen::HermesWebUiNativeScreen;
use agor_lib::models::user::User;
#[cfg(target_arch = "wasm32")]
use agor_lib::models::user::UserRole;
use agor_lib::network::agor_client::{AgorClient, LoginResult};
use agor_lib::state::auth::{self, AuthState, AuthStore};
use agor_lib::state::chat::ChatStore;
use agor_lib::state::navigation::NavStore;
use agor_lib::state::storage::AppStorage;
use agor_lib::ui::app_shell::AppShellWithSettings;
use agor_lib::ui::login::LoginScreen;
use agor_lib::ui::settings::SettingsScreenWithExtra;
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
    HermesWebFallback,
    Settings,
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
        if *dev_login_started.peek() {
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

    let hermes_web_configured = use_memo(move || {
        let c = hermes_config.read();
        c.web_ui_url
            .as_deref()
            .map(str::trim)
            .is_some_and(|url| !url.is_empty())
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
                        class: if *active_tab.read() == AppTab::Hermes { "rail-item active" } else { "rail-item" },
                        title: "Hermes",
                        onclick: move |_| {
                            if hermes_web_configured() {
                                active_tab.set(AppTab::Hermes);
                            } else {
                                active_tab.set(AppTab::Settings);
                            }
                        },
                        span { class: "rail-mark", "H" }
                    }
                    div { class: "rail-spacer" }
                    button {
                        class: if *active_tab.read() == AppTab::Settings { "rail-item active" } else { "rail-item" },
                        title: "Settings",
                        onclick: move |_| active_tab.set(AppTab::Settings),
                        span { class: "rail-mark", "⚙" }
                    }
                }

                main { class: "app-surface",
                    match active_tab.read().clone() {
                        AppTab::Agor => rsx! {
                            AppShellWithSettings {
                                settings_extra_sections: rsx! {
                                    HermesSettingsSection {
                                        config: hermes_config.read().clone(),
                                        on_save: move |new_config: HermesConfig| {
                                            save_hermes_config(&new_config);
                                            hermes_config.set(new_config);
                                        },
                                    }
                                },
                            }
                        },
                        AppTab::Hermes => rsx! {
                            HermesWebUiNativeScreen {
                                config: hermes_config.read().clone(),
                                on_open_settings: move |_| active_tab.set(AppTab::Settings),
                                on_open_web_fallback: move |_| active_tab.set(AppTab::HermesWebFallback),
                            }
                        },
                        AppTab::HermesWebFallback => rsx! {
                            HermesWebScreen {
                                config: hermes_config.read().clone(),
                                on_open_settings: move |_| active_tab.set(AppTab::Settings),
                            }
                        },
                        AppTab::Settings => rsx! {
                            SettingsScreenWithExtra {
                                on_back: move |_| {
                                    active_tab.set(AppTab::Agor);
                                },
                                on_open_drawer: move |_| {
                                    active_tab.set(AppTab::Agor);
                                },
                                extra_sections: rsx! {
                                    HermesSettingsSection {
                                        config: hermes_config.read().clone(),
                                        on_save: move |new_config: HermesConfig| {
                                            save_hermes_config(&new_config);
                                            hermes_config.set(new_config);
                                        },
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

#[component]
fn HermesWebScreen(config: HermesConfig, on_open_settings: EventHandler<()>) -> Element {
    let web_url = config
        .web_ui_url
        .as_deref()
        .map(str::trim)
        .filter(|url| !url.is_empty())
        .unwrap_or(DEFAULT_WEB_UI_URL)
        .to_string();

    rsx! {
        div { class: "hermes-web-screen",
            div { class: "hermes-web-topbar",
                div { class: "topbar-center",
                    span { class: "topbar-title", "Hermes" }
                    span { class: "topbar-subtitle", "{web_url}" }
                }
                button {
                    class: "icon-btn",
                    title: "Settings",
                    onclick: move |_| on_open_settings.call(()),
                    "⚙"
                }
            }
            iframe {
                class: "hermes-web-frame",
                title: "Hermes Web UI",
                src: "{web_url}",
                allow: "microphone; clipboard-read; clipboard-write; fullscreen",
            }
        }
    }
}

#[component]
fn HermesSettingsSection(config: HermesConfig, on_save: EventHandler<HermesConfig>) -> Element {
    let mut web_ui_url = use_signal(|| {
        config
            .web_ui_url
            .clone()
            .unwrap_or_else(|| DEFAULT_WEB_UI_URL.to_string())
    });
    let mut url = use_signal(|| config.base_url.clone().unwrap_or_default());
    let mut token = use_signal(|| config.token.clone().unwrap_or_default());
    let mut model = use_signal(|| config.model.clone().unwrap_or_default());
    let mut whisper_url = use_signal(|| config.whisper_url.clone().unwrap_or_default());
    let mut whisper_token = use_signal(|| config.whisper_token.clone().unwrap_or_default());
    let mut whisper_model = use_signal(|| {
        config.whisper_model.clone().unwrap_or_else(|| {
            agor_lib::network::transcription::DEFAULT_REMOTE_WHISPER_MODEL.to_string()
        })
    });
    let mut whisper_model_artifact_url = use_signal(|| {
        config
            .whisper_model_artifact_url
            .clone()
            .unwrap_or_default()
    });
    let mut whisper_model_path =
        use_signal(|| config.whisper_model_path.clone().unwrap_or_default());
    let mut probing = use_signal(|| false);
    let mut status = use_signal(|| Option::<String>::None);
    let mut status_ok = use_signal(|| false);

    let has_url = !url.read().trim().is_empty();
    let has_token = !token.read().trim().is_empty();
    let can_test = has_url && has_token && !*probing.read();

    let on_test = move |_| {
        let test_url = HermesClient::normalize_url(&url.read());
        let test_token = token.read().trim().to_string();
        probing.set(true);
        status.set(None);

        spawn(async move {
            let client = HermesClient::new();
            match client.probe(&test_url, &test_token).await {
                Ok(models) => {
                    let model_list = models.join(", ");
                    status.set(Some(format!("OK - models: {model_list}")));
                    status_ok.set(true);
                }
                Err(e) => {
                    status.set(Some(format!("Error: {e}")));
                    status_ok.set(false);
                }
            }
            probing.set(false);
        });
    };

    let on_save_click = move |_| {
        let clean = |value: String| {
            let value = value.trim().to_string();
            if value.is_empty() {
                None
            } else {
                Some(value)
            }
        };
        let normalized_url = |value: String| {
            let value = value.trim().to_string();
            if value.is_empty() {
                None
            } else {
                Some(HermesClient::normalize_url(&value))
            }
        };

        on_save.call(HermesConfig {
            web_ui_url: clean(web_ui_url.read().clone()),
            base_url: normalized_url(url.read().clone()),
            token: clean(token.read().clone()),
            model: clean(model.read().clone()),
            whisper_url: clean(whisper_url.read().clone()),
            whisper_token: clean(whisper_token.read().clone()),
            whisper_model: clean(whisper_model.read().clone()),
            whisper_model_artifact_url: clean(whisper_model_artifact_url.read().clone()),
            whisper_model_path: clean(whisper_model_path.read().clone()),
        });
        status.set(Some("Hermes settings saved".to_string()));
        status_ok.set(true);
    };

    rsx! {
        div { class: "settings-section",
            h3 { "Hermes" }
            div { class: "form-group",
                label { "Hermes Web UI URL" }
                input {
                    r#type: "text",
                    placeholder: "{DEFAULT_WEB_UI_URL}",
                    value: "{web_ui_url}",
                    oninput: move |e| web_ui_url.set(e.value()),
                }
            }
            div { class: "form-group",
                label { "Hermes API base URL" }
                input {
                    r#type: "text",
                    placeholder: "https://llm.example.com",
                    value: "{url}",
                    oninput: move |e| {
                        url.set(e.value());
                        status.set(None);
                    },
                }
            }
            div { class: "form-group",
                label { "Hermes API key" }
                input {
                    r#type: "password",
                    placeholder: "sk-...",
                    value: "{token}",
                    oninput: move |e| {
                        token.set(e.value());
                        status.set(None);
                    },
                }
            }
            div { class: "form-group",
                label { "Hermes model" }
                input {
                    r#type: "text",
                    placeholder: "{DEFAULT_MODEL}",
                    value: "{model}",
                    oninput: move |e| model.set(e.value()),
                }
            }
            div { class: "form-group",
                label { "Hermes remote Whisper URL" }
                input {
                    r#type: "text",
                    placeholder: "http://host:8080",
                    value: "{whisper_url}",
                    oninput: move |e| whisper_url.set(e.value()),
                }
            }
            div { class: "form-group",
                label { "Hermes remote Whisper token" }
                input {
                    r#type: "password",
                    placeholder: "Token",
                    value: "{whisper_token}",
                    oninput: move |e| whisper_token.set(e.value()),
                }
            }
            div { class: "form-group",
                label { "Hermes Whisper model" }
                input {
                    r#type: "text",
                    placeholder: "{agor_lib::network::transcription::DEFAULT_REMOTE_WHISPER_MODEL}",
                    value: "{whisper_model}",
                    oninput: move |e| whisper_model.set(e.value()),
                }
            }
            div { class: "form-group",
                label { "Hermes local model artifact URL" }
                input {
                    r#type: "text",
                    placeholder: "{agor_lib::network::transcription::DEFAULT_BASE_EN_MODEL_ARTIFACT_URL}",
                    value: "{whisper_model_artifact_url}",
                    oninput: move |e| whisper_model_artifact_url.set(e.value()),
                }
            }
            div { class: "form-group",
                label { "Hermes local model path" }
                input {
                    r#type: "text",
                    placeholder: "Downloaded by Android bridge",
                    value: "{whisper_model_path}",
                    oninput: move |e| whisper_model_path.set(e.value()),
                }
            }
            if let Some(st) = status.read().as_ref() {
                p {
                    class: if *status_ok.read() { "form-status ok" } else { "form-status error" },
                    "{st}"
                }
            }
            div { class: "setup-actions",
                button {
                    class: "btn-secondary",
                    disabled: !can_test,
                    onclick: on_test,
                    if *probing.read() { "Testing..." } else { "Test Connection" }
                }
                button {
                    class: "btn-primary",
                    onclick: on_save_click,
                    "Save Hermes Settings"
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
    let config = if path.exists() {
        std::fs::read_to_string(&path)
            .ok()
            .and_then(|data| serde_json::from_str(&data).ok())
            .unwrap_or_default()
    } else {
        HermesConfig::default()
    };

    apply_hermes_query_overrides(config)
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

#[cfg(target_arch = "wasm32")]
fn apply_hermes_query_overrides(mut config: HermesConfig) -> HermesConfig {
    if let Some(web_url) = query_value("agor_hermes_web_url") {
        config.web_ui_url = Some(web_url);
    }
    config
}

#[cfg(not(target_arch = "wasm32"))]
fn apply_hermes_query_overrides(config: HermesConfig) -> HermesConfig {
    config
}

#[cfg(target_arch = "wasm32")]
fn query_value(name: &str) -> Option<String> {
    let search = web_sys::window()?.location().search().ok()?;
    let query = search.strip_prefix('?').unwrap_or(&search);
    url::form_urlencoded::parse(query.as_bytes())
        .find_map(|(key, value)| (key.as_ref() == name).then(|| value.into_owned()))
        .filter(|value| !value.trim().is_empty())
}
