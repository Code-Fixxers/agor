use dioxus::prelude::*;
use std::collections::HashMap;

use crate::models::SessionStatus;
use crate::network::agor_client::AgorClient;
use crate::network::streaming_service::StreamSnapshot;
use crate::state::auth::AuthStore;
use crate::state::chat::{self, ChatRow, ChatStore};
use crate::state::storage::AppStorage;
use crate::ui::chat::image_block::ImageBlockView;
use crate::ui::chat::input_request_card::InputRequestCardView;
use crate::ui::chat::message_bubble::MessageBubble;
use crate::ui::chat::permission_card::PermissionCardView;
use crate::ui::chat::prompt_input::PromptInputBar;
use crate::ui::chat::task_header::TaskHeaderView;
use crate::ui::chat::thinking_block::ThinkingBlockView;
use crate::ui::chat::tool_result_block::ToolResultBlockView;
use crate::ui::chat::tool_use_block::ToolUseBlockView;
use crate::ui::common::status_badge::status_class;
use crate::util::logger::AppLogger;

#[component]
pub fn ChatScreen(
    session_id: String,
    on_open_drawer: EventHandler<()>,
    on_back: EventHandler<()>,
) -> Element {
    let mut chat = use_context::<Signal<ChatStore>>();
    let _auth = use_context::<Signal<AuthStore>>();
    let storage = use_context::<Signal<AppStorage>>();

    let mut show_files = use_signal(|| false);
    let mut show_mcp = use_signal(|| false);
    let _show_rename = use_signal(|| false);
    let _rename_draft = use_signal(|| String::new());

    let live_streams = use_signal(|| HashMap::<String, StreamSnapshot>::new());

    // Load session on mount or session_id change
    let sid = session_id.clone();
    use_effect(move || {
        let sid = sid.clone();
        spawn(async move {
            let logger = AppLogger::new();
            let client = AgorClient::new(logger.clone());
            let mut c = chat.write();
            c.draft = storage.read().get_draft(&sid);
            drop(c);

            let mut c = chat.write();
            if let Err(e) = chat::load_session(&client, &mut c, &sid, &logger).await {
                c.error = Some(e);
            }
        });
    });

    let rows = use_memo(move || {
        let c = chat.read();
        let streams = live_streams.read();
        c.build_chat_rows(&streams)
    });

    let session_title = use_memo(move || {
        chat.read()
            .session
            .as_ref()
            .map(|s| s.display_title())
            .unwrap_or_else(|| "Loading...".to_string())
    });

    let session_status = use_memo(move || {
        chat.read()
            .session
            .as_ref()
            .map(|s| s.status.clone())
            .unwrap_or(SessionStatus::Idle)
    });

    let is_plan_mode = use_memo(move || {
        chat.read()
            .session
            .as_ref()
            .map(|s| s.is_plan_mode())
            .unwrap_or(false)
    });

    let is_promptable = use_memo(move || {
        chat.read()
            .session
            .as_ref()
            .map(|s| s.is_promptable())
            .unwrap_or(false)
    });

    let status_cls = status_class(&session_status());

    let sid_for_stop = session_id.clone();
    let on_stop = move |_| {
        let sid = sid_for_stop.clone();
        spawn(async move {
            let logger = AppLogger::new();
            let client = AgorClient::new(logger.clone());
            let _ = client.stop_session(&sid).await;
        });
    };

    let sid_for_archive = session_id.clone();
    let _on_archive = move |_: Event<MouseData>| {
        let sid = sid_for_archive.clone();
        spawn(async move {
            let logger = AppLogger::new();
            let client = AgorClient::new(logger.clone());
            let _ = client
                .patch_session(&sid, &serde_json::json!({"archived": true}))
                .await;
        });
    };

    rsx! {
        div { class: "chat-screen",
            // Top bar
            div { class: "chat-topbar",
                button {
                    class: "icon-btn",
                    onclick: move |_| on_open_drawer.call(()),
                    "☰"
                }

                div { class: "topbar-center",
                    span { class: "topbar-title", "{session_title}" }
                    div { class: "topbar-badges",
                        if is_plan_mode() {
                            span { class: "badge plan-badge", "PLAN" }
                        }
                        span { class: "status-badge {status_cls}",
                            "{session_status().display_label()}"
                        }
                    }
                }

                if session_status().is_active() {
                    button {
                        class: "icon-btn stop-btn",
                        onclick: on_stop,
                        "■"
                    }
                }

                div { class: "topbar-menu",
                    button {
                        class: "icon-btn",
                        onclick: move |_| show_files.set(true),
                        "📁"
                    }
                    button {
                        class: "icon-btn",
                        onclick: move |_| show_mcp.set(true),
                        "🔌"
                    }
                }
            }

            if is_plan_mode() {
                div { class: "plan-mode-banner",
                    "Plan Mode — Read-only, no tool execution"
                }
            }

            // Messages area
            div { class: "chat-messages",
                if chat.read().is_loading {
                    div { class: "chat-loading", "Loading messages..." }
                }

                if let Some(err) = chat.read().error.as_ref() {
                    div { class: "chat-error", "{err}" }
                }

                for row in rows.read().iter() {
                    div { key: "{row.key()}", class: "chat-row-wrapper",
                        {render_chat_row(row)}
                    }
                }
            }

            // Prompt input
            PromptInputBar {
                enabled: is_promptable(),
                session_status: session_status(),
            }
        }
    }
}

fn render_chat_row(row: &ChatRow) -> Element {
    match row {
        ChatRow::TaskHeader {
            task,
            expanded,
            message_count,
        } => rsx! {
            TaskHeaderView {
                task: task.clone(),
                expanded: *expanded,
                message_count: *message_count,
            }
        },
        ChatRow::TextBubble {
            message,
            is_streaming,
            streaming_text,
        } => rsx! {
            MessageBubble {
                message: message.clone(),
                is_streaming: *is_streaming,
                streaming_text: streaming_text.clone(),
            }
        },
        ChatRow::ToolUseRow { message_id, block } => rsx! {
            ToolUseBlockView {
                message_id: message_id.clone(),
                block: block.clone(),
            }
        },
        ChatRow::ToolResultRow { message_id, block } => rsx! {
            ToolResultBlockView {
                message_id: message_id.clone(),
                block: block.clone(),
            }
        },
        ChatRow::ThinkingRow {
            message_id,
            thinking,
            is_streaming,
        } => rsx! {
            ThinkingBlockView {
                message_id: message_id.clone(),
                thinking: thinking.clone(),
                is_streaming: *is_streaming,
            }
        },
        ChatRow::ImageRow { message_id, block } => rsx! {
            ImageBlockView {
                message_id: message_id.clone(),
                block: block.clone(),
            }
        },
        ChatRow::PermissionCardRow { message, request } => rsx! {
            PermissionCardView {
                message: message.clone(),
                request: request.clone(),
            }
        },
        ChatRow::InputRequestRow { message, request } => rsx! {
            InputRequestCardView {
                message: message.clone(),
                request: request.clone(),
            }
        },
        ChatRow::LiveStreamRow {
            session_id: _,
            text,
            thinking,
        } => rsx! {
            div { class: "live-stream-bubble assistant",
                if !thinking.is_empty() {
                    div { class: "thinking-indicator",
                        span { class: "thinking-icon", "💭" }
                        span { class: "thinking-text", "{thinking}" }
                    }
                }
                if !text.is_empty() {
                    div { class: "stream-text", "{text}" }
                }
                if text.is_empty() && thinking.is_empty() {
                    div { class: "typing-indicator", "..." }
                }
            }
        },
        ChatRow::OlderTasksRow { count } => rsx! {
            div { class: "older-tasks-row",
                button { class: "show-older-btn",
                    "Show {count} older tasks"
                }
            }
        },
    }
}
