use dioxus::prelude::*;

use crate::models::SessionStatus;
use crate::network::agor_client::AgorClient;
use crate::state::chat::{self, ChatStore};
use crate::state::storage::AppStorage;
use agor_shared::logger::AppLogger;

#[component]
pub fn PromptInputBar(enabled: bool, session_status: SessionStatus) -> Element {
    let mut chat = use_context::<Signal<ChatStore>>();
    let mut storage = use_context::<Signal<AppStorage>>();
    let mut sending = use_signal(|| false);

    let draft = use_memo(move || chat.read().draft.clone());

    let placeholder = match session_status {
        SessionStatus::Running => "Agent is working...",
        SessionStatus::AwaitingPermission => "Agent needs permission...",
        SessionStatus::AwaitingInput => "Agent needs input...",
        SessionStatus::Stopping => "Stopping...",
        _ => "Send a message...",
    };

    let can_send = enabled && !draft.read().trim().is_empty() && !*sending.read();

    let on_input = move |e: FormEvent| {
        let text = e.value();
        chat.write().draft = text.clone();
        if let Some(session) = &chat.read().session {
            storage.write().set_draft(&session.session_id, &text);
        }
    };

    let mut on_send = move |_| {
        if !can_send {
            return;
        }

        sending.set(true);

        spawn(async move {
            let logger = AppLogger::new();
            let client = AgorClient::new(logger.clone());
            let mut c = chat.write();
            let _ = chat::send_prompt(&client, &mut c, &logger).await;
            if let Some(session) = &c.session {
                storage.write().set_draft(&session.session_id, "");
            }
            drop(c);
            sending.set(false);
        });
    };

    let on_keydown = move |e: KeyboardEvent| {
        if e.key() == Key::Enter && !e.modifiers().shift() {
            e.prevent_default();
            if can_send {
                on_send(());
            }
        }
    };

    rsx! {
        div { class: "prompt-input-bar",
            div { class: "input-row",
                button { class: "icon-btn attach-btn", "📎" }

                textarea {
                    class: "prompt-textarea",
                    placeholder: "{placeholder}",
                    value: "{draft}",
                    disabled: !enabled,
                    rows: "1",
                    oninput: on_input,
                    onkeydown: on_keydown,
                }

                button {
                    class: if can_send { "send-btn active" } else { "send-btn" },
                    disabled: !can_send,
                    onclick: move |_| on_send(()),
                    "↑"
                }
            }
        }
    }
}
