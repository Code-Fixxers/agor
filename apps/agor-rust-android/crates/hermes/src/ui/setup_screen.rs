use dioxus::prelude::*;

use crate::client::HermesClient;
use crate::models::{HermesConfig, DEFAULT_WEB_UI_URL};
use agor_shared::update::{self, AppMetadata, UpdateState};

const DEFAULT_REMOTE_WHISPER_MODEL: &str = "base.en";
const DEFAULT_BASE_EN_MODEL_ARTIFACT_URL: &str =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin";

#[component]
pub fn HermesSetupScreen(
    config: HermesConfig,
    on_save: EventHandler<HermesConfig>,
    on_close: EventHandler<()>,
) -> Element {
    let mut web_ui_url = use_signal(|| config.web_ui_url.clone().unwrap_or_default());
    let mut url = use_signal(|| config.base_url.clone().unwrap_or_default());
    let mut token = use_signal(|| config.token.clone().unwrap_or_default());
    let mut model = use_signal(|| config.model.clone().unwrap_or_default());
    let mut whisper_url = use_signal(|| config.whisper_url.clone().unwrap_or_default());
    let mut whisper_token = use_signal(|| config.whisper_token.clone().unwrap_or_default());
    let mut whisper_model = use_signal(|| {
        config
            .whisper_model
            .clone()
            .unwrap_or_else(|| DEFAULT_REMOTE_WHISPER_MODEL.to_string())
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
    let meta = use_context::<Signal<AppMetadata>>();
    let update_state = use_signal(|| UpdateState::Idle);

    let has_url = !url.read().trim().is_empty();
    let has_token = !token.read().trim().is_empty();
    let can_test = has_url && has_token && !*probing.read();
    let can_save = has_url && has_token;

    let partial_msg = if has_url && !has_token {
        Some("API key is required")
    } else if !has_url && has_token {
        Some("Base URL is required")
    } else {
        None
    };

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
                    status.set(Some(format!("OK — models: {model_list}")));
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
        let new_config = HermesConfig {
            web_ui_url: {
                let w = web_ui_url.read().trim().to_string();
                if w.is_empty() {
                    None
                } else {
                    Some(w)
                }
            },
            base_url: Some(HermesClient::normalize_url(&url.read())),
            token: Some(token.read().trim().to_string()),
            model: {
                let m = model.read().trim().to_string();
                if m.is_empty() {
                    None
                } else {
                    Some(m)
                }
            },
            whisper_url: {
                let w = whisper_url.read().trim().to_string();
                if w.is_empty() {
                    None
                } else {
                    Some(w)
                }
            },
            whisper_token: {
                let w = whisper_token.read().trim().to_string();
                if w.is_empty() {
                    None
                } else {
                    Some(w)
                }
            },
            whisper_model: {
                let w = whisper_model.read().trim().to_string();
                if w.is_empty() {
                    None
                } else {
                    Some(w)
                }
            },
            whisper_model_artifact_url: {
                let w = whisper_model_artifact_url.read().trim().to_string();
                if w.is_empty() {
                    None
                } else {
                    Some(w)
                }
            },
            whisper_model_path: {
                let w = whisper_model_path.read().trim().to_string();
                if w.is_empty() {
                    None
                } else {
                    Some(w)
                }
            },
        };
        on_save.call(new_config);
    };

    rsx! {
        div { class: "hermes-setup-screen",
            div { class: "setup-header",
                h2 { "Hermes Setup" }
                button {
                    class: "icon-btn",
                    onclick: move |_| on_close.call(()),
                    "×"
                }
            }

            div { class: "setup-form",
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
                    label { "Base URL" }
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
                    label { "API Key" }
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
                    label { "Model (optional)" }
                    input {
                        r#type: "text",
                        placeholder: "hermes-model",
                        value: "{model}",
                        oninput: move |e| model.set(e.value()),
                    }
                }

                div { class: "form-group",
                    label { "Remote Whisper URL (optional)" }
                    input {
                        r#type: "text",
                        placeholder: "http://host:8080",
                        value: "{whisper_url}",
                        oninput: move |e| whisper_url.set(e.value()),
                    }
                }

                div { class: "form-group",
                    label { "Remote Whisper Token (optional)" }
                    input {
                        r#type: "password",
                        placeholder: "Token",
                        value: "{whisper_token}",
                        oninput: move |e| whisper_token.set(e.value()),
                    }
                }

                div { class: "form-group",
                    label { "Whisper Model" }
                    input {
                        r#type: "text",
                        placeholder: "{DEFAULT_REMOTE_WHISPER_MODEL}",
                        value: "{whisper_model}",
                        oninput: move |e| whisper_model.set(e.value()),
                    }
                }

                div { class: "form-group",
                    label { "Local Model Artifact URL (optional)" }
                    input {
                        r#type: "text",
                        placeholder: "{DEFAULT_BASE_EN_MODEL_ARTIFACT_URL}",
                        value: "{whisper_model_artifact_url}",
                        oninput: move |e| whisper_model_artifact_url.set(e.value()),
                    }
                }

                div { class: "form-group",
                    label { "Local Model Path (optional)" }
                    input {
                        r#type: "text",
                        placeholder: "Downloaded by Android bridge",
                        value: "{whisper_model_path}",
                        oninput: move |e| whisper_model_path.set(e.value()),
                    }
                }

                if let Some(msg) = partial_msg {
                    p { class: "form-hint error-text", "{msg}" }
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
                        disabled: !can_save,
                        onclick: on_save_click,
                        "Save"
                    }
                }

                // About & update
                div { class: "setup-about",
                    p { class: "form-hint",
                        "Version {meta.read().version_name} (build {meta.read().version_code})"
                    }
                    {setup_update_section(meta, update_state)}
                }
            }
        }
    }
}

fn setup_update_section(
    meta: Signal<AppMetadata>,
    mut update_state: Signal<UpdateState>,
) -> Element {
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
            p { class: "form-hint", "Checking..." }
        },
        UpdateState::UpToDate => rsx! {
            p { class: "form-hint", "Up to date" }
        },
        UpdateState::Available(manifest) => rsx! {
            p { class: "form-hint",
                "Update: {manifest.version_name} (build {manifest.version_code})"
            }
            button {
                class: "btn-primary",
                onclick: on_download,
                "Download"
            }
        },
        UpdateState::Downloading => rsx! {
            p { class: "form-hint", "Downloading..." }
        },
        UpdateState::Ready(path) => {
            let display = path.display().to_string();
            rsx! {
                p { class: "form-status ok", "Downloaded: {display}" }
            }
        }
        UpdateState::Failed(msg) => rsx! {
            p { class: "form-status error", "{msg}" }
            button {
                class: "btn-secondary",
                onclick: on_check,
                "Retry"
            }
        },
    }
}
