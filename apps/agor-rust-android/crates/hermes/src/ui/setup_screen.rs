use dioxus::prelude::*;

use crate::client::HermesClient;
use crate::models::HermesConfig;

#[component]
pub fn HermesSetupScreen(
    config: HermesConfig,
    on_save: EventHandler<HermesConfig>,
    on_close: EventHandler<()>,
) -> Element {
    let mut url = use_signal(|| config.base_url.clone().unwrap_or_default());
    let mut token = use_signal(|| config.token.clone().unwrap_or_default());
    let mut model = use_signal(|| config.model.clone().unwrap_or_default());
    let mut whisper_url = use_signal(|| config.whisper_url.clone().unwrap_or_default());
    let mut whisper_token = use_signal(|| config.whisper_token.clone().unwrap_or_default());
    let mut probing = use_signal(|| false);
    let mut status = use_signal(|| Option::<String>::None);
    let mut status_ok = use_signal(|| false);

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
            base_url: Some(HermesClient::normalize_url(&url.read())),
            token: Some(token.read().trim().to_string()),
            model: {
                let m = model.read().trim().to_string();
                if m.is_empty() { None } else { Some(m) }
            },
            whisper_url: {
                let w = whisper_url.read().trim().to_string();
                if w.is_empty() { None } else { Some(w) }
            },
            whisper_token: {
                let w = whisper_token.read().trim().to_string();
                if w.is_empty() { None } else { Some(w) }
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
            }
        }
    }
}
