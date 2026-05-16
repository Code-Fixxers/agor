use dioxus::prelude::*;
use std::collections::HashMap;

use crate::models::input_request::{InputRequestContent, InputRequestKind, InputRequestStatus};
use crate::models::message::Message;
use crate::network::agor_client::AgorClient;
use crate::state::auth::AuthStore;
use crate::state::chat;
use agor_shared::logger::AppLogger;

#[component]
pub fn InputRequestCardView(message: Message, request: InputRequestContent) -> Element {
    let auth = use_context::<Signal<AuthStore>>();
    let is_pending = matches!(request.status, InputRequestStatus::Pending);
    let mut answers = use_signal(|| HashMap::<String, String>::new());
    let _selected = use_signal(|| HashMap::<String, Vec<String>>::new());

    let status_label = match &request.status {
        InputRequestStatus::Pending => "Pending",
        InputRequestStatus::Answered => "Answered",
        InputRequestStatus::Cancelled => "Cancelled",
        InputRequestStatus::TimedOut => "Timed Out",
    };

    let req_id = request.request_id.clone();
    let task_id = request.task_id.clone();
    let session_id = message.session_id.clone();

    let on_submit = move |_| {
        let req_id = req_id.clone();
        let task_id = task_id.clone();
        let session_id = session_id.clone();
        let user_id = auth
            .read()
            .user
            .as_ref()
            .map(|u| u.user_id.clone())
            .unwrap_or_default();
        let final_answers = answers.read().clone();

        spawn(async move {
            let logger = AppLogger::new();
            let client = AgorClient::new(logger.clone());
            let _ = chat::answer_input_request(
                &client,
                &session_id,
                &req_id,
                task_id.as_deref(),
                &final_answers,
                &user_id,
                &logger,
            )
            .await;
        });
    };

    rsx! {
        div { class: "input-request-card",
            for question in request.questions.iter() {
                div { class: "input-question",
                    if let Some(header) = &question.header {
                        span { class: "question-header", "{header}" }
                    }
                    p { class: "question-text", "{question.question}" }

                    match &question.kind {
                        InputRequestKind::FreeText => rsx! {
                            if is_pending {
                                textarea {
                                    class: "input-textarea",
                                    placeholder: "Type your answer...",
                                    oninput: {
                                        let q = question.question.clone();
                                        move |e: FormEvent| {
                                            answers.write().insert(q.clone(), e.value());
                                        }
                                    },
                                }
                            } else if let Some(ans) = request.answers.as_ref().and_then(|a| a.get(&question.question)) {
                                div { class: "answered-text", "{ans}" }
                            }
                        },
                        InputRequestKind::SingleChoice | InputRequestKind::MultiChoice => rsx! {
                            if let Some(options) = &question.options {
                                div { class: "input-options",
                                    for option in options.iter() {
                                        {
                                            let label = option.label.clone();
                                            let q = question.question.clone();
                                            let is_multi = question.multi_select.unwrap_or(false);
                                            let is_selected = answers.read().get(&q).map_or(false, |a| a.contains(&label));

                                            rsx! {
                                                button {
                                                    class: if is_selected { "option-btn selected" } else { "option-btn" },
                                                    disabled: !is_pending,
                                                    onclick: move |_| {
                                                        let mut a = answers.write();
                                                        if is_multi {
                                                            let current = a.entry(q.clone()).or_default();
                                                            if current.contains(&label) {
                                                                *current = current.replace(&format!(",{label}"), "").replace(&label, "");
                                                            } else {
                                                                if current.is_empty() {
                                                                    *current = label.clone();
                                                                } else {
                                                                    current.push_str(&format!(",{label}"));
                                                                }
                                                            }
                                                        } else {
                                                            a.insert(q.clone(), label.clone());
                                                        }
                                                    },
                                                    div { class: "option-label", "{option.label}" }
                                                    div { class: "option-desc", "{option.description}" }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    }
                }
            }

            div { class: "input-request-footer",
                if is_pending {
                    button {
                        class: "btn-primary",
                        onclick: on_submit,
                        "Submit"
                    }
                } else {
                    span { class: "input-status", "{status_label}" }
                }
            }
        }
    }
}
