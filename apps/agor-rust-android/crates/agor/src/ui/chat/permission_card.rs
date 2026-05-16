use dioxus::prelude::*;

use crate::models::message::Message;
use crate::models::permission::{PermissionRequestContent, PermissionStatus};
use crate::network::agor_client::AgorClient;
use crate::state::auth::AuthStore;
use crate::state::chat;
use agor_shared::logger::AppLogger;

#[component]
pub fn PermissionCardView(message: Message, request: PermissionRequestContent) -> Element {
    let auth = use_context::<Signal<AuthStore>>();
    let is_pending = matches!(request.status, PermissionStatus::Pending);

    let preview = request.input_preview();
    let status_label = match &request.status {
        PermissionStatus::Pending => "Pending",
        PermissionStatus::Approved => "Approved",
        PermissionStatus::Denied => "Denied",
        PermissionStatus::Cancelled => "Cancelled",
        PermissionStatus::TimedOut => "Timed Out",
    };

    let status_class = match &request.status {
        PermissionStatus::Pending => "pending",
        PermissionStatus::Approved => "approved",
        PermissionStatus::Denied => "denied",
        _ => "resolved",
    };

    let req_id = request.request_id.clone();
    let task_id = request.task_id.clone();
    let session_id = message.session_id.clone();

    let on_approve = {
        let req_id = req_id.clone();
        let task_id = task_id.clone();
        let session_id = session_id.clone();
        move |_| {
            let req_id = req_id.clone();
            let task_id = task_id.clone();
            let session_id = session_id.clone();
            let user_id = auth
                .read()
                .user
                .as_ref()
                .map(|u| u.user_id.clone())
                .unwrap_or_default();
            spawn(async move {
                let logger = AppLogger::new();
                let client = AgorClient::new(logger.clone());
                let _ = chat::decide_permission(
                    &client,
                    &session_id,
                    &req_id,
                    task_id.as_deref(),
                    true,
                    &user_id,
                    &logger,
                )
                .await;
            });
        }
    };

    let on_deny = move |_| {
        let req_id = req_id.clone();
        let task_id = task_id.clone();
        let session_id = session_id.clone();
        let user_id = auth
            .read()
            .user
            .as_ref()
            .map(|u| u.user_id.clone())
            .unwrap_or_default();
        spawn(async move {
            let logger = AppLogger::new();
            let client = AgorClient::new(logger.clone());
            let _ = chat::decide_permission(
                &client,
                &session_id,
                &req_id,
                task_id.as_deref(),
                false,
                &user_id,
                &logger,
            )
            .await;
        });
    };

    rsx! {
        div { class: "permission-card {status_class}",
            div { class: "permission-header",
                span { class: "permission-icon", "🔐" }
                span { class: "permission-title", "Permission Requested" }
                span { class: "permission-tool", "{request.tool_name}" }
            }

            if !preview.is_empty() {
                div { class: "permission-preview",
                    code { "{preview}" }
                }
            }

            div { class: "permission-footer",
                if is_pending {
                    div { class: "permission-actions",
                        button {
                            class: "btn-deny",
                            onclick: on_deny,
                            "Deny"
                        }
                        button {
                            class: "btn-approve",
                            onclick: on_approve,
                            "Approve"
                        }
                    }
                } else {
                    span { class: "permission-status", "{status_label}" }
                }
            }
        }
    }
}
