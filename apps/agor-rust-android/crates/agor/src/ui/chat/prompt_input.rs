use dioxus::prelude::*;

use crate::models::SessionStatus;
use crate::network::agor_client::AgorClient;
use crate::network::transcription::{
    cancel_voice_recording, merge_transcript_into_draft, start_voice_recording,
    stop_voice_recording_and_transcribe,
};
use crate::state::chat::{self, ChatStore};
use crate::state::storage::AppStorage;
use agor_shared::logger::AppLogger;

#[derive(Debug, Clone, PartialEq, Eq)]
enum VoiceInputState {
    Idle,
    Recording,
    Transcribing,
    Error(String),
}

#[component]
pub fn PromptInputBar(enabled: bool, session_status: SessionStatus) -> Element {
    let mut chat = use_context::<Signal<ChatStore>>();
    let mut storage = use_context::<Signal<AppStorage>>();
    let mut sending = use_signal(|| false);
    let mut voice_state = use_signal(|| VoiceInputState::Idle);

    let draft = use_memo(move || chat.read().draft.clone());

    let placeholder = match session_status {
        SessionStatus::Running => "Agent is working...",
        SessionStatus::AwaitingPermission => "Agent needs permission...",
        SessionStatus::AwaitingInput => "Agent needs input...",
        SessionStatus::Stopping => "Stopping...",
        _ => "Prompt this Agor session...",
    };

    let can_send = enabled && !draft.read().trim().is_empty() && !*sending.read();
    let can_use_voice = enabled
        && !*sending.read()
        && !matches!(*voice_state.read(), VoiceInputState::Transcribing);
    let is_recording = matches!(*voice_state.read(), VoiceInputState::Recording);

    let on_input = move |e: FormEvent| {
        let text = e.value();
        chat.write().draft = text.clone();
        if let Some(session) = &chat.read().session {
            storage.write().set_draft(&session.session_id, &text);
        }
    };

    let on_voice = move |_| {
        if !can_use_voice {
            return;
        }

        let current_voice_state = voice_state.read().clone();
        match current_voice_state {
            VoiceInputState::Idle | VoiceInputState::Error(_) => {
                voice_state.set(VoiceInputState::Recording);
                spawn(async move {
                    if let Err(err) = start_voice_recording().await {
                        cancel_voice_recording();
                        voice_state.set(VoiceInputState::Error(err.to_string()));
                    }
                });
            }
            VoiceInputState::Recording => {
                voice_state.set(VoiceInputState::Transcribing);
                spawn(async move {
                    let transcription = storage.read().transcription_config();
                    match stop_voice_recording_and_transcribe(&transcription).await {
                        Ok(transcript) => {
                            let mut c = chat.write();
                            let merged = merge_transcript_into_draft(&c.draft, &transcript);
                            c.draft = merged.clone();
                            let session_id =
                                c.session.as_ref().map(|session| session.session_id.clone());
                            drop(c);

                            if let Some(session_id) = session_id {
                                storage.write().set_draft(&session_id, &merged);
                            }

                            voice_state.set(VoiceInputState::Idle);
                        }
                        Err(err) => {
                            cancel_voice_recording();
                            voice_state.set(VoiceInputState::Error(err.to_string()));
                        }
                    }
                });
            }
            VoiceInputState::Transcribing => {}
        }
    };

    let mut on_send = move |_| {
        if !can_send {
            return;
        }

        sending.set(true);

        spawn(async move {
            let logger = AppLogger::new();
            let storage_snapshot = storage.read().clone();
            let client = AgorClient::new_with_storage(logger.clone(), &storage_snapshot);

            let send_target = {
                let c = chat.read();
                c.session
                    .as_ref()
                    .map(|session| (session.session_id.clone(), c.draft.clone()))
            };

            if let Some((session_id, prompt)) = send_target {
                if chat::send_prompt(&client, &session_id, &prompt, &logger)
                    .await
                    .is_ok()
                {
                    chat.write().draft.clear();
                    storage.write().set_draft(&session_id, "");
                }
            }

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
                button { class: "icon-btn intent-btn", "⚡" }

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
                    class: if is_recording { "icon-btn voice-btn active" } else { "icon-btn voice-btn" },
                    disabled: !can_use_voice,
                    title: if is_recording { "Stop recording" } else { "Record voice" },
                    onclick: on_voice,
                    if is_recording { "■" } else { "🎙" }
                }

                button {
                    class: if can_send { "send-btn active" } else { "send-btn" },
                    disabled: !can_send,
                    onclick: move |_| on_send(()),
                    "↑"
                }
            }

            match voice_state.read().clone() {
                VoiceInputState::Idle => rsx! {},
                VoiceInputState::Recording => rsx! {
                    div { class: "prompt-voice-status recording", "Recording..." }
                },
                VoiceInputState::Transcribing => rsx! {
                    div { class: "prompt-voice-status", "Transcribing..." }
                },
                VoiceInputState::Error(message) => rsx! {
                    div { class: "prompt-voice-status error", "{message}" }
                },
            }
        }
    }
}
