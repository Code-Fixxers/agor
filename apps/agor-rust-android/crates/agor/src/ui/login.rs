use dioxus::prelude::*;

use crate::network::agor_client::AgorClient;
use crate::network::biometrics::{
    has_biometric_secret, is_biometric_available, save_biometric_secret, unlock_biometric_secret,
    BiometricSecret,
};
use crate::state::auth::{self, AuthState, AuthStore};
use crate::state::storage::AppStorage;
use agor_shared::logger::AppLogger;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LoginMode {
    Password,
    ApiKey,
}

#[component]
pub fn LoginScreen() -> Element {
    let mut auth_store = use_context::<Signal<AuthStore>>();
    let mut storage = use_context::<Signal<AppStorage>>();

    let mut login_mode = use_signal(|| LoginMode::Password);
    let mut url = use_signal(default_daemon_url);
    let mut email = use_signal(|| String::new());
    let mut password = use_signal(|| String::new());
    let mut api_key = use_signal(|| String::new());
    let mut profile_name = use_signal(|| String::new());
    let mut save_secret = use_signal(|| false);
    let mut error = use_signal(|| Option::<String>::None);
    let mut loading = use_signal(|| false);

    let profiles = use_memo(move || storage.read().profiles.clone());
    let biometrics_available = is_biometric_available();

    use_effect(move || {
        let s = storage.read();
        if let Some(profile) = s.active_profile().or_else(|| s.default_profile()) {
            url.set(profile.url.clone());
            if let Some(e) = &profile.email {
                email.set(e.clone());
            }
            if let Some(creds) = s.credentials.get(&profile.id) {
                if let Some(saved_api_key) = &creds.saved_api_key {
                    api_key.set(saved_api_key.clone());
                }
            }
            profile_name.set(profile.label.clone());
        }
    });

    rsx! {
        div { class: "login-screen",
            div { class: "login-card",
                div { class: "login-header",
                    h1 { "Agor" }
                    p { class: "login-subtitle", "Connect to your Agor daemon" }
                }

                div { class: "login-mode-toggle", role: "tablist", "aria-label": "Login method",
                    button {
                        class: if *login_mode.read() == LoginMode::Password { "mode-toggle active" } else { "mode-toggle" },
                        r#type: "button",
                        onclick: move |_| {
                            login_mode.set(LoginMode::Password);
                            error.set(None);
                        },
                        "User + password"
                    }
                    button {
                        class: if *login_mode.read() == LoginMode::ApiKey { "mode-toggle active" } else { "mode-toggle" },
                        r#type: "button",
                        onclick: move |_| {
                            login_mode.set(LoginMode::ApiKey);
                            error.set(None);
                        },
                        "API key"
                    }
                }

                if !profiles.read().is_empty() {
                    div { class: "saved-profiles",
                        p { class: "label", "Saved Servers" }
                        div { class: "profile-chips",
                            for (_i, profile) in profiles.read().iter().enumerate() {
                                {
                                    let p_url = profile.url.clone();
                                    let p_email = profile.email.clone();
                                    let p_label = profile.label.clone();
                                    rsx! {
                        button {
                            class: "profile-chip",
                            onclick: move |_| {
                                url.set(p_url.clone());
                                if let Some(e) = &p_email {
                                    email.set(e.clone());
                                }
                                login_mode.set(LoginMode::Password);
                                profile_name.set(p_label.clone());
                            },
                            "{profile.label}"
                                        }
                                    }
                                }
                            }
                        }
                        for profile in profiles.read().iter() {
                            if has_biometric_secret(&profile.id) {
                                {
                                    let profile_id = profile.id.clone();
                                    let profile_label = profile.label.clone();
                                    let profile_url = profile.url.clone();
                                    let profile_email = profile.email.clone();
                                    rsx! {
                                        button {
                                            class: "biometric-unlock-btn",
                                            r#type: "button",
                                            disabled: *loading.read(),
                                            onclick: move |_| {
                                                let profile_id = profile_id.clone();
                                                let profile_label = profile_label.clone();
                                                let fallback_url = profile_url.clone();
                                                let fallback_email = profile_email.clone();

                                                loading.set(true);
                                                error.set(None);

                                                spawn(async move {
                                                    match unlock_biometric_secret(&profile_id).await {
                                                        Ok(secret) => {
                                                            let logger = AppLogger::new();
                                                            let client = AgorClient::new(logger.clone());
                                                            let server_url = if secret.server_url.is_empty() {
                                                                fallback_url.clone()
                                                            } else {
                                                                secret.server_url.clone()
                                                            };
                                                            let login_result = if secret.kind == "api-key" {
                                                                auth::authenticate_with_api_key(
                                                                    &client,
                                                                    &logger,
                                                                    &server_url,
                                                                    &secret.secret,
                                                                )
                                                                .await
                                                            } else {
                                                                let email_value = secret
                                                                    .email
                                                                    .clone()
                                                                    .or_else(|| fallback_email.clone())
                                                                    .unwrap_or_default();
                                                                auth::authenticate_with_password(
                                                                    &client,
                                                                    &logger,
                                                                    &server_url,
                                                                    &email_value,
                                                                    &secret.secret,
                                                                )
                                                                .await
                                                            };

                                                            match login_result {
                                                                Ok((base_url, result)) => {
                                                                    let mut s = storage.write();
                                                                    let state = auth::persist_login(
                                                                        &mut s,
                                                                        base_url,
                                                                        &profile_label,
                                                                        match secret.kind.as_str() {
                                                                            "api-key" => result.user.email.clone(),
                                                                            _ => secret.email.clone().or_else(|| fallback_email.clone()),
                                                                        },
                                                                        result,
                                                                        None,
                                                                        None,
                                                                        Some(secret.kind.clone()),
                                                                        false,
                                                                        &logger,
                                                                    );
                                                                    drop(s);
                                                                    let state = match state {
                                                                        Ok(state) => state,
                                                                        Err(e) => {
                                                                            error.set(Some(e));
                                                                            loading.set(false);
                                                                            return;
                                                                        }
                                                                    };
                                                                    let user = if let AuthState::Authenticated { ref user } = state {
                                                                        Some(user.clone())
                                                                    } else {
                                                                        None
                                                                    };
                                                                    auth_store.write().state = state;
                                                                    auth_store.write().user = user;
                                                                }
                                                                Err(e) => error.set(Some(e)),
                                                            }
                                                        }
                                                        Err(e) => error.set(Some(e)),
                                                    }
                                                    loading.set(false);
                                                });
                                            },
                                            "Unlock {profile.label} with biometrics"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                div { class: "form-group",
                    label { "Daemon URL" }
                    input {
                        r#type: "text",
                        placeholder: "http://localhost:3030",
                        value: "{url}",
                        oninput: move |e| url.set(e.value()),
                    }
                }

                if *login_mode.read() == LoginMode::Password {
                    div { class: "form-group",
                        label { "Email" }
                        input {
                            r#type: "email",
                            placeholder: "user@example.com",
                            value: "{email}",
                            oninput: move |e| email.set(e.value()),
                        }
                    }

                    div { class: "form-group",
                        label { "Password" }
                        input {
                            r#type: "password",
                            placeholder: "Password",
                            value: "{password}",
                            oninput: move |e| password.set(e.value()),
                        }
                    }
                } else {
                    div { class: "form-group",
                        label { "API Key" }
                        input {
                            r#type: "password",
                            placeholder: "agor_sk_...",
                            value: "{api_key}",
                            oninput: move |e| api_key.set(e.value()),
                        }
                    }
                }

                div { class: "form-group",
                    label { "Profile Name (optional)" }
                    input {
                        r#type: "text",
                        placeholder: "My Server",
                        value: "{profile_name}",
                        oninput: move |e| profile_name.set(e.value()),
                    }
                }

                div { class: "form-row",
                    label { class: "checkbox-label",
                        input {
                            r#type: "checkbox",
                            checked: "{save_secret}",
                            onchange: move |e| save_secret.set(e.checked()),
                        }
                        if *login_mode.read() == LoginMode::Password {
                            " Protect password with biometrics"
                        } else {
                            " Protect API key with biometrics"
                        }
                    }
                }
                if *save_secret.read() && !biometrics_available {
                    p { class: "form-hint", "Biometric storage is only available inside the Android APK." }
                }

                if let Some(err) = error.read().as_ref() {
                    div { class: "error-banner", "{err}" }
                }

                button {
                    class: "login-button",
                    r#type: "button",
                    disabled: *loading.read(),
                    onclick: move |_| {
                        let mode = *login_mode.read();
                        let url_val = url.read().clone();
                        let email_val = email.read().clone();
                        let password_val = password.read().clone();
                        let api_key_val = api_key.read().clone();
                        let name_val = profile_name.read().clone();
                        let save_secret_val = *save_secret.read();

                        loading.set(true);
                        error.set(None);

                        if save_secret_val && !is_biometric_available() {
                            error.set(Some("Biometric storage is not available in this build.".to_string()));
                            loading.set(false);
                            return;
                        }

                        spawn(async move {
                            let logger = AppLogger::new();
                            let client = AgorClient::new(logger.clone());

                            let login_result = match mode {
                                LoginMode::Password => {
                                    auth::authenticate_with_password(
                                        &client,
                                        &logger,
                                        &url_val,
                                        &email_val,
                                        &password_val,
                                    )
                                    .await
                                }
                                LoginMode::ApiKey => {
                                    auth::authenticate_with_api_key(
                                        &client,
                                        &logger,
                                        &url_val,
                                        &api_key_val,
                                    )
                                    .await
                                }
                            };

                            match login_result {
                                Ok((base_url, result)) => {
                                    let biometric_kind = if save_secret_val {
                                        Some(match mode {
                                            LoginMode::Password => "password".to_string(),
                                            LoginMode::ApiKey => "api-key".to_string(),
                                        })
                                    } else {
                                        None
                                    };
                                    let biometric_secret = if save_secret_val {
                                        Some(BiometricSecret {
                                            kind: biometric_kind.clone().unwrap_or_default(),
                                            server_url: base_url.clone(),
                                            email: match mode {
                                                LoginMode::Password => Some(email_val.clone()),
                                                LoginMode::ApiKey => result.user.email.clone(),
                                            },
                                            secret: match mode {
                                                LoginMode::Password => password_val.clone(),
                                                LoginMode::ApiKey => api_key_val.clone(),
                                            },
                                        })
                                    } else {
                                        None
                                    };

                                    let mut s = storage.write();
                                    let state = auth::persist_login(
                                        &mut s,
                                        base_url,
                                        &name_val,
                                        match mode {
                                            LoginMode::Password => Some(email_val.clone()),
                                            LoginMode::ApiKey => result.user.email.clone(),
                                        },
                                        result,
                                        None,
                                        None,
                                        biometric_kind,
                                        !save_secret_val,
                                        &logger,
                                    );
                                    let profile_id = s.preferences.active_profile_id.clone();
                                    drop(s);
                                    let state = match state {
                                        Ok(state) => state,
                                        Err(e) => {
                                            error.set(Some(e));
                                            loading.set(false);
                                            return;
                                        }
                                    };
                                    if let (Some(secret), Some(profile_id)) = (biometric_secret.as_ref(), profile_id.as_ref()) {
                                        if let Err(e) = save_biometric_secret(secret, profile_id) {
                                            error.set(Some(e));
                                            loading.set(false);
                                            return;
                                        }
                                    }
                                    let user = if let AuthState::Authenticated { ref user } = state {
                                        Some(user.clone())
                                    } else {
                                        None
                                    };
                                    auth_store.write().state = state;
                                    auth_store.write().user = user;
                                }
                                Err(e) => {
                                    error.set(Some(e));
                                }
                            }
                            loading.set(false);
                        });
                    },
                    if *loading.read() { "Connecting..." } else { "Connect" }
                }
            }
        }
    }
}

fn default_daemon_url() -> String {
    #[cfg(target_arch = "wasm32")]
    {
        "http://127.0.0.1:3030".to_string()
    }

    #[cfg(not(target_arch = "wasm32"))]
    {
        String::new()
    }
}
