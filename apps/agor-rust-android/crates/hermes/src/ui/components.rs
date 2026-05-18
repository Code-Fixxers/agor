use dioxus::prelude::*;

use crate::models::{HermesSession, HermesTurn};

#[component]
pub fn SessionStrip(
    sessions: Vec<HermesSession>,
    selected_id: Option<String>,
    on_select: EventHandler<String>,
) -> Element {
    rsx! {
        div { class: "hermes-session-strip",
            for session in sessions.iter() {
                {
                    let sid = session.id.clone();
                    let title = session.title.clone();
                    let is_selected = selected_id.as_deref() == Some(&session.id);
                    rsx! {
                        button {
                            class: if is_selected { "session-chip selected" } else { "session-chip" },
                            onclick: move |_| on_select.call(sid.clone()),
                            "{title}"
                        }
                    }
                }
            }
        }
    }
}

#[component]
pub fn TurnBubble(turn: HermesTurn) -> Element {
    let is_user = turn.role == "user";
    let bubble_class = if is_user {
        "hermes-bubble user"
    } else {
        "hermes-bubble assistant"
    };

    let html_content = if !is_user && !turn.streaming {
        let parser = pulldown_cmark::Parser::new(&turn.content);
        let mut html = String::new();
        pulldown_cmark::html::push_html(&mut html, parser);
        Some(html)
    } else {
        None
    };

    rsx! {
        div { class: "{bubble_class}",
            div { class: "bubble-role",
                if is_user { "You" } else { "Hermes" }
            }

            if !turn.attachments.is_empty() {
                div { class: "bubble-attachments",
                    for att in turn.attachments.iter() {
                        if att.mime_type.starts_with("image/") {
                            div { class: "attachment-thumb",
                                span { "📷 {att.mime_type}" }
                            }
                        }
                    }
                }
            }

            if let Some(html) = &html_content {
                div {
                    class: "bubble-content markdown-body",
                    dangerous_inner_html: "{html}",
                }
            } else {
                div { class: "bubble-content",
                    if turn.streaming && turn.content.is_empty() {
                        span { class: "typing-indicator", "..." }
                    } else {
                        "{turn.content}"
                    }
                }
            }

            if !turn.progress.is_empty() {
                div { class: "bubble-progress",
                    for item in turn.progress.iter().rev().take(4) {
                        span { class: "progress-label", "{item.label}" }
                    }
                }
            }
        }
    }
}

#[component]
pub fn HermesInputBar(
    draft: String,
    enabled: bool,
    voice_enabled: bool,
    on_input: EventHandler<String>,
    on_send: EventHandler<()>,
    on_attach: EventHandler<()>,
    on_voice_toggle: EventHandler<()>,
) -> Element {
    let can_send = enabled && !draft.trim().is_empty();

    rsx! {
        div { class: "hermes-input-bar",
            button {
                class: "icon-btn attach-btn",
                onclick: move |_| on_attach.call(()),
                "📎"
            }

            textarea {
                class: "hermes-textarea",
                placeholder: "Message Hermes...",
                value: "{draft}",
                disabled: !enabled,
                rows: "1",
                oninput: move |e| on_input.call(e.value()),
                onkeydown: move |e: KeyboardEvent| {
                    if e.key() == Key::Enter && !e.modifiers().shift() {
                        e.prevent_default();
                        if can_send {
                            on_send.call(());
                        }
                    }
                },
            }

            button {
                class: if voice_enabled { "icon-btn voice-btn active" } else { "icon-btn voice-btn" },
                onclick: move |_| on_voice_toggle.call(()),
                "🎤"
            }

            button {
                class: if can_send { "send-btn active" } else { "send-btn" },
                disabled: !can_send,
                onclick: move |_| on_send.call(()),
                "↑"
            }
        }
    }
}

#[component]
pub fn ErrorBubble(
    message: String,
    on_retry: EventHandler<()>,
    on_settings: EventHandler<()>,
    on_dismiss: EventHandler<()>,
) -> Element {
    rsx! {
        div { class: "hermes-error-bubble",
            p { class: "error-text", "{message}" }
            div { class: "error-actions",
                button {
                    class: "btn-secondary",
                    onclick: move |_| on_retry.call(()),
                    "Retry"
                }
                button {
                    class: "btn-secondary",
                    onclick: move |_| on_settings.call(()),
                    "Settings"
                }
                button {
                    class: "btn-secondary",
                    onclick: move |_| on_dismiss.call(()),
                    "Dismiss"
                }
            }
        }
    }
}

#[component]
pub fn VoiceStatusBar(
    phase: String,
    audio_level: f32,
    threshold: f32,
    pending_transcript: Option<String>,
    on_cancel: EventHandler<()>,
    on_send_now: EventHandler<()>,
    on_skip_tts: EventHandler<()>,
) -> Element {
    rsx! {
        div { class: "hermes-voice-bar",
            span { class: "voice-phase", "{phase}" }

            if phase == "Recording" {
                div { class: "voice-level-bar",
                    div {
                        class: "voice-level-fill",
                        style: "width: {(audio_level / threshold * 100.0).min(100.0)}%",
                    }
                }
            }

            if let Some(transcript) = &pending_transcript {
                div { class: "voice-review",
                    p { class: "transcript-text", "{transcript}" }
                    div { class: "review-actions",
                        button {
                            class: "btn-secondary",
                            onclick: move |_| on_cancel.call(()),
                            "Cancel"
                        }
                        button {
                            class: "btn-primary",
                            onclick: move |_| on_send_now.call(()),
                            "Send"
                        }
                    }
                }
            }

            if phase == "Speaking" {
                button {
                    class: "btn-secondary",
                    onclick: move |_| on_skip_tts.call(()),
                    "Skip"
                }
            }
        }
    }
}
