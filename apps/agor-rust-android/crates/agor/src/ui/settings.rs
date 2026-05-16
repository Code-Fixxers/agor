use dioxus::prelude::*;

use crate::models::server_profile::ServerProfile;
use crate::network::agor_client::AgorClient;
use crate::state::auth::{self, AuthStore};
use crate::state::storage::AppStorage;
use agor_shared::logger::AppLogger;
use agor_shared::update::{self, AppMetadata, UpdateState};

#[component]
pub fn SettingsScreen(
    on_back: EventHandler<()>,
    on_open_drawer: EventHandler<()>,
) -> Element {
    let mut auth = use_context::<Signal<AuthStore>>();
    let mut storage = use_context::<Signal<AppStorage>>();
    let meta = use_context::<Signal<AppMetadata>>();
    let mut show_add_server = use_signal(|| false);
    let mut show_debug_log = use_signal(|| false);
    let update_state = use_signal(|| UpdateState::Idle);

    let user = use_memo(move || auth.read().user.clone());
    let profiles = use_memo(move || storage.read().profiles.clone());

    let on_logout = move |_| {
        let logger = AppLogger::new();
        let client = AgorClient::new(logger.clone());
        let mut s = storage.write();
        auth::logout(&client, &mut s, &logger);
        drop(s);
        let mut a = auth.write();
        a.state = crate::state::auth::AuthState::NeedsLogin;
        a.user = None;
    };

    let on_clear_cache = move |_| {
        let mut s = storage.write();
        s.drafts.clear();
        s.save_drafts();
    };

    rsx! {
        div { class: "settings-screen",
            // Top bar
            div { class: "settings-topbar",
                button {
                    class: "icon-btn",
                    onclick: move |_| on_open_drawer.call(()),
                    "☰"
                }
                span { class: "topbar-title", "Settings" }
                button {
                    class: "icon-btn",
                    onclick: move |_| on_back.call(()),
                    "×"
                }
            }

            div { class: "settings-content",
                // Account section
                div { class: "settings-section",
                    h3 { "Account" }
                    if let Some(u) = user.read().as_ref() {
                        div { class: "settings-row",
                            span { class: "user-emoji", "{u.emoji.as_deref().unwrap_or(\"\")}" }
                            div {
                                p { class: "settings-label", "{u.name}" }
                                p { class: "settings-sublabel",
                                    "{u.email.as_deref().unwrap_or(\"No email\")}"
                                }
                            }
                        }
                    }
                    button {
                        class: "btn-danger",
                        onclick: on_logout,
                        "Log Out"
                    }
                }

                // Server Profiles section
                div { class: "settings-section",
                    div { class: "section-header-row",
                        h3 { "Servers" }
                        button {
                            class: "btn-secondary",
                            onclick: move |_| show_add_server.set(true),
                            "+ Add"
                        }
                    }

                    for profile in profiles.read().iter() {
                        div { class: "server-profile-row",
                            div { class: "server-info",
                                span { class: "server-label", "{profile.label}" }
                                span { class: "server-url", "{profile.url}" }
                                if let Some(email) = &profile.email {
                                    span { class: "server-email", "{email}" }
                                }
                            }
                            div { class: "server-actions",
                                if profile.is_default {
                                    span { class: "default-badge", "Default" }
                                }
                                {
                                    let pid = profile.id.clone();
                                    rsx! {
                                        button {
                                            class: "btn-small btn-danger",
                                            onclick: move |_| {
                                                storage.write().remove_profile(&pid);
                                            },
                                            "Remove"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Diagnostics section
                div { class: "settings-section",
                    h3 { "Diagnostics" }
                    button {
                        class: "btn-secondary",
                        onclick: move |_| show_debug_log.set(true),
                        "View Debug Log"
                    }
                    button {
                        class: "btn-secondary",
                        onclick: on_clear_cache,
                        "Clear Session Cache"
                    }
                }

                // App info & updates
                div { class: "settings-section",
                    h3 { "About" }
                    div { class: "settings-row",
                        span { class: "settings-label", "Version" }
                        span { class: "settings-value",
                            "{meta.read().version_name} (build {meta.read().version_code})"
                        }
                    }
                    div { class: "settings-row",
                        span { class: "settings-label", "Built with" }
                        span { class: "settings-value", "Rust + Dioxus" }
                    }

                    {update_section(meta, update_state)}
                }
            }

            if *show_debug_log.read() {
                DebugLogModal {
                    on_close: move |_| show_debug_log.set(false),
                }
            }

            if *show_add_server.read() {
                AddServerModal {
                    on_close: move |_| show_add_server.set(false),
                }
            }
        }
    }
}

fn update_section(meta: Signal<AppMetadata>, mut update_state: Signal<UpdateState>) -> Element {
    let on_check = move |_| {
        let url = meta.read().update_manifest_url.clone();
        let code = meta.read().version_code;
        update_state.set(UpdateState::Checking);
        spawn(async move {
            match update::check_for_update(&url, code).await {
                Ok(Some(manifest)) => update_state.set(UpdateState::Available(manifest)),
                Ok(None) => update_state.set(UpdateState::UpToDate),
                Err(e) => update_state.set(UpdateState::Failed(e)),
            }
        });
    };

    let on_download = move |_| {
        let manifest = match update_state.read().clone() {
            UpdateState::Available(m) => m,
            _ => return,
        };
        update_state.set(UpdateState::Downloading);
        spawn(async move {
            match update::download_apk(&manifest).await {
                Ok(path) => update_state.set(UpdateState::Ready(path)),
                Err(e) => update_state.set(UpdateState::Failed(e)),
            }
        });
    };

    match update_state.read().clone() {
        UpdateState::Idle => rsx! {
            button {
                class: "btn-secondary",
                onclick: on_check,
                "Check for Updates"
            }
        },
        UpdateState::Checking => rsx! {
            p { class: "form-hint", "Checking for updates..." }
        },
        UpdateState::UpToDate => rsx! {
            p { class: "form-hint", "You're up to date!" }
        },
        UpdateState::Available(manifest) => rsx! {
            p { class: "form-hint",
                "Update available: {manifest.version_name} (build {manifest.version_code})"
            }
            button {
                class: "btn-primary",
                onclick: on_download,
                "Download Update"
            }
        },
        UpdateState::Downloading => rsx! {
            p { class: "form-hint", "Downloading update..." }
        },
        UpdateState::Ready(path) => {
            let display = path.display().to_string();
            rsx! {
                p { class: "form-status ok", "Update downloaded: {display}" }
            }
        },
        UpdateState::Failed(msg) => rsx! {
            p { class: "form-status error", "Error: {msg}" }
            button {
                class: "btn-secondary",
                onclick: on_check,
                "Retry"
            }
        },
    }
}

#[component]
fn DebugLogModal(on_close: EventHandler<()>) -> Element {
    let logger = AppLogger::new();
    let log_text = logger.export_text();

    rsx! {
        div { class: "modal-overlay",
            onclick: move |_| on_close.call(()),
            div { class: "modal-content wide",
                onclick: move |e| e.stop_propagation(),
                div { class: "modal-header",
                    span { "Debug Log" }
                    button {
                        class: "modal-close",
                        onclick: move |_| on_close.call(()),
                        "×"
                    }
                }
                pre { class: "debug-log-content",
                    if log_text.is_empty() {
                        "No log entries"
                    } else {
                        "{log_text}"
                    }
                }
            }
        }
    }
}

#[component]
fn AddServerModal(on_close: EventHandler<()>) -> Element {
    let mut storage = use_context::<Signal<AppStorage>>();

    let mut label = use_signal(|| String::new());
    let mut url = use_signal(|| String::new());
    let mut email = use_signal(|| String::new());

    let on_save = move |_| {
        let profile = ServerProfile {
            id: uuid::Uuid::new_v4().to_string(),
            label: if label.read().is_empty() {
                url.read().clone()
            } else {
                label.read().clone()
            },
            url: url.read().clone(),
            email: if email.read().is_empty() {
                None
            } else {
                Some(email.read().clone())
            },
            is_default: false,
        };
        storage.write().add_profile(profile);
        on_close.call(());
    };

    rsx! {
        div { class: "modal-overlay",
            onclick: move |_| on_close.call(()),
            div { class: "modal-content",
                onclick: move |e| e.stop_propagation(),
                div { class: "modal-header",
                    span { "Add Server" }
                    button {
                        class: "modal-close",
                        onclick: move |_| on_close.call(()),
                        "×"
                    }
                }
                div { class: "form-group",
                    label { "Label" }
                    input {
                        r#type: "text",
                        placeholder: "My Server",
                        value: "{label}",
                        oninput: move |e| label.set(e.value()),
                    }
                }
                div { class: "form-group",
                    label { "URL" }
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
                div { class: "modal-actions",
                    button {
                        class: "btn-secondary",
                        onclick: move |_| on_close.call(()),
                        "Cancel"
                    }
                    button {
                        class: "btn-primary",
                        onclick: on_save,
                        "Save"
                    }
                }
            }
        }
    }
}
