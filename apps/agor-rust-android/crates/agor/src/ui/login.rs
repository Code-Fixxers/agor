use dioxus::prelude::*;

use crate::network::agor_client::AgorClient;
use crate::state::auth::{self, AuthState, AuthStore};
use crate::state::storage::AppStorage;
use agor_shared::logger::AppLogger;

#[component]
pub fn LoginScreen() -> Element {
    let mut auth_store = use_context::<Signal<AuthStore>>();
    let mut storage = use_context::<Signal<AppStorage>>();

    let mut url = use_signal(|| String::new());
    let mut email = use_signal(|| String::new());
    let mut password = use_signal(|| String::new());
    let mut profile_name = use_signal(|| String::new());
    let mut save_password = use_signal(|| false);
    let mut error = use_signal(|| Option::<String>::None);
    let mut loading = use_signal(|| false);

    let profiles = use_memo(move || storage.read().profiles.clone());

    use_effect(move || {
        let s = storage.read();
        if let Some(profile) = s.active_profile().or_else(|| s.default_profile()) {
            url.set(profile.url.clone());
            if let Some(e) = &profile.email {
                email.set(e.clone());
            }
            profile_name.set(profile.label.clone());
        }
    });

    let on_login = move |_: Event<MouseData>| {
        let url_val = url.read().clone();
        let email_val = email.read().clone();
        let password_val = password.read().clone();
        let name_val = profile_name.read().clone();
        let save_pw = *save_password.read();

        loading.set(true);
        error.set(None);

        spawn(async move {
            let logger = AppLogger::new();
            let client = AgorClient::new(logger.clone());

            let mut s = storage.write();
            match auth::login(
                &client,
                &mut s,
                &logger,
                &url_val,
                &email_val,
                &password_val,
                &name_val,
                save_pw,
            )
            .await
            {
                Ok(state) => {
                    let user = if let AuthState::Authenticated { ref user } = state {
                        Some(user.clone())
                    } else {
                        None
                    };
                    drop(s);
                    auth_store.write().state = state;
                    auth_store.write().user = user;
                }
                Err(e) => {
                    error.set(Some(e));
                }
            }
            loading.set(false);
        });
    };

    rsx! {
        div { class: "login-screen",
            div { class: "login-card",
                div { class: "login-header",
                    h1 { "Agor" }
                    p { class: "login-subtitle", "Connect to your Agor daemon" }
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
                                                profile_name.set(p_label.clone());
                                            },
                                            "{profile.label}"
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
                            checked: "{save_password}",
                            onchange: move |e| save_password.set(e.checked()),
                        }
                        " Save password for auto-login"
                    }
                }

                if let Some(err) = error.read().as_ref() {
                    div { class: "error-banner", "{err}" }
                }

                button {
                    class: "login-button",
                    disabled: *loading.read(),
                    onclick: on_login,
                    if *loading.read() { "Connecting..." } else { "Connect" }
                }
            }
        }
    }
}
