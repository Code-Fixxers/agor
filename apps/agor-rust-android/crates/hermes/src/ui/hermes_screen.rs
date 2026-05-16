use dioxus::prelude::*;

use crate::client::HermesClient;
use crate::models::{HermesConfig, HermesMessage, HermesResponseEvent, HermesSession};
use crate::session_store::HermesSessionStore;
use crate::streaming::HermesStreamTextState;
use crate::ui::components::{ErrorBubble, HermesInputBar, SessionStrip, TurnBubble};

#[component]
pub fn HermesScreen(
    config: HermesConfig,
    on_open_settings: EventHandler<()>,
) -> Element {
    let store = use_context::<Signal<HermesSessionStore>>();
    let mut sessions = use_signal(|| Vec::<HermesSession>::new());
    let mut selected_id = use_signal(|| Option::<String>::None);
    let mut draft = use_signal(|| String::new());
    let mut sending = use_signal(|| false);
    let mut error = use_signal(|| Option::<String>::None);

    use_effect(move || {
        let loaded = store.read().sessions();
        sessions.set(loaded);
    });

    let selected_session = use_memo(move || {
        let sid = selected_id.read().clone();
        sid.and_then(|id| sessions.read().iter().find(|s| s.id == id).cloned())
    });

    let on_new_session = move |_| {
        let session = store.read().create_session(None);
        let sid = session.id.clone();
        sessions.write().insert(0, session);
        selected_id.set(Some(sid));
    };

    let on_delete_session = move |_| {
        let sid = selected_id.read().clone();
        if let Some(sid) = sid {
            store.read().delete_session(&sid);
            sessions.write().retain(|s| s.id != sid);
            let next = sessions.read().first().map(|s| s.id.clone());
            selected_id.set(next);
        }
    };

    let config_for_send = config.clone();
    let mut on_send = move |_| {
        let text = draft.read().clone();
        if text.trim().is_empty() {
            return;
        }

        let existing_sid = selected_id.read().clone();
        let sid = match existing_sid {
            Some(id) => id,
            None => {
                let session = store.read().create_session(Some(&text));
                let id = session.id.clone();
                sessions.write().insert(0, session);
                selected_id.set(Some(id.clone()));
                id
            }
        };

        draft.set(String::new());
        sending.set(true);
        error.set(None);

        let config = config_for_send.clone();
        spawn(async move {
            let client = HermesClient::new();

            let turn_id = match store.read().begin_turn(&sid, &text, Vec::new()) {
                Some(id) => id,
                None => {
                    sending.set(false);
                    return;
                }
            };

            let s = store.read();
            let session = match s.get_session(&sid) {
                Some(s) => s,
                None => {
                    sending.set(false);
                    return;
                }
            };

            let messages: Vec<HermesMessage> = session
                .turns
                .iter()
                .filter(|t| !t.streaming && (t.role == "user" || t.role == "assistant"))
                .map(|t| HermesMessage {
                    role: t.role.clone(),
                    content: t.content.clone(),
                })
                .collect();
            drop(s);

            let (tx, _) = tokio::sync::broadcast::channel(64);
            let mut rx = tx.subscribe();

            let client_clone = client.clone();
            let config_clone = config.clone();
            let tx_clone = tx.clone();
            let stream_task = tokio::spawn(async move {
                client_clone.chat_stream(&config_clone, &messages, &tx_clone).await
            });

            let mut state = HermesStreamTextState::new();
            while let Ok(event) = rx.recv().await {
                match event {
                    HermesResponseEvent::ReasoningDelta(delta) => {
                        if let Some(update) = state.on_reasoning_delta(&delta) {
                            store.read().append_assistant_delta(
                                &sid, &turn_id, &update.text,
                                update.replace_existing, update.emit_text_event,
                            );
                        }
                    }
                    HermesResponseEvent::TextDelta(delta) => {
                        if let Some(update) = state.on_text_delta(&delta) {
                            store.read().append_assistant_delta(
                                &sid, &turn_id, &update.text,
                                update.replace_existing, update.emit_text_event,
                            );
                        }
                    }
                    HermesResponseEvent::Progress(label) => {
                        store.read().append_progress(&sid, &turn_id, &label);
                    }
                    HermesResponseEvent::Completed { text, response_id } => {
                        store.read().complete_assistant(
                            &sid, &turn_id, response_id.as_deref(), Some(&text),
                        );
                    }
                    HermesResponseEvent::Failed(msg) => {
                        store.read().fail_assistant(&sid, &turn_id, &msg);
                        error.set(Some(msg));
                    }
                }
                sessions.set(store.read().sessions());
            }

            if let Err(e) = stream_task.await {
                let msg = format!("Stream error: {e}");
                store.read().fail_assistant(&sid, &turn_id, &msg);
                error.set(Some(msg));
            }

            sessions.set(store.read().sessions());
            sending.set(false);
        });
    };

    rsx! {
        div { class: "hermes-screen",
            div { class: "hermes-topbar",
                span { class: "topbar-title", "Hermes" }
                div { class: "topbar-actions",
                    button {
                        class: "icon-btn",
                        onclick: on_new_session,
                        "+"
                    }
                    button {
                        class: "icon-btn",
                        onclick: on_delete_session,
                        "🗑"
                    }
                    button {
                        class: "icon-btn",
                        onclick: move |_| on_open_settings.call(()),
                        "⚙"
                    }
                }
            }

            if sessions.read().len() > 1 {
                SessionStrip {
                    sessions: sessions.read().clone(),
                    selected_id: selected_id.read().clone(),
                    on_select: move |id: String| selected_id.set(Some(id)),
                }
            }

            div { class: "hermes-messages",
                if let Some(session) = selected_session.read().as_ref() {
                    for turn in session.turns.iter() {
                        TurnBubble { key: "{turn.id}", turn: turn.clone() }
                    }
                    if session.turns.is_empty() {
                        div { class: "hermes-empty",
                            p { "Start a conversation with Hermes" }
                        }
                    }
                } else {
                    div { class: "hermes-empty",
                        p { "Select or create a session to begin" }
                    }
                }

                if let Some(err) = error.read().as_ref() {
                    ErrorBubble {
                        message: err.clone(),
                        on_retry: move |_| error.set(None),
                        on_settings: move |_| on_open_settings.call(()),
                        on_dismiss: move |_| error.set(None),
                    }
                }
            }

            HermesInputBar {
                draft: draft.read().clone(),
                enabled: !*sending.read(),
                voice_enabled: false,
                on_input: move |text: String| draft.set(text),
                on_send: move |_| on_send(()),
                on_attach: move |_| {},
                on_voice_toggle: move |_| {},
            }
        }
    }
}
