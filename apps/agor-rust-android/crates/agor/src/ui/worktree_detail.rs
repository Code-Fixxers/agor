use dioxus::prelude::*;

use crate::models::{Board, Repo, Session, Worktree};
use crate::network::agor_client::AgorClient;
use crate::state::navigation::NavStore;
use crate::state::storage::AppStorage;
use crate::ui::common::agent_icon::agent_icon_class;
use crate::ui::common::status_badge::status_class;
use agor_shared::logger::AppLogger;

#[derive(Clone, PartialEq)]
struct WorktreeDetailData {
    worktree: Worktree,
    board: Option<Board>,
    repo: Option<Repo>,
    sessions: Vec<Session>,
}

#[component]
pub fn WorktreeDetailScreen(
    worktree_id: String,
    on_open_drawer: EventHandler<()>,
    on_back: EventHandler<()>,
    on_open_session: EventHandler<String>,
) -> Element {
    let mut nav = use_context::<Signal<NavStore>>();
    let storage = use_context::<Signal<AppStorage>>();
    let selected_tool = use_signal(|| "junie".to_string());
    let mut prompt = use_signal(String::new);
    let mut creating = use_signal(|| false);
    let mut error = use_signal(|| Option::<String>::None);

    let detail = use_memo({
        let worktree_id = worktree_id.clone();
        move || find_worktree_detail(&nav.read(), &worktree_id)
    });

    let start_disabled = *creating.read();
    let on_start_session = {
        let worktree_id = worktree_id.clone();
        move |_| {
            if start_disabled {
                return;
            }

            let tool = selected_tool.read().clone();
            let first_prompt = prompt.read().trim().to_string();
            let storage_snapshot = storage.read().clone();
            let target_worktree_id = worktree_id.clone();

            creating.set(true);
            error.set(None);

            spawn(async move {
                let logger = AppLogger::new();
                let client = AgorClient::new_with_storage(logger, &storage_snapshot);

                match client.create_session(&target_worktree_id, &tool).await {
                    Ok(session) => {
                        if !first_prompt.is_empty() {
                            if let Err(err) =
                                client.send_prompt(&session.session_id, &first_prompt).await
                            {
                                error.set(Some(format!(
                                    "Session created, but first prompt failed: {err}"
                                )));
                                creating.set(false);
                                return;
                            }
                        }

                        {
                            let mut n = nav.write();
                            n.sessions.retain(|s| s.session_id != session.session_id);
                            n.sessions.insert(0, session.clone());
                            let sessions = n
                                .sessions_by_worktree
                                .entry(session.worktree_id.clone())
                                .or_default();
                            sessions.retain(|s| s.session_id != session.session_id);
                            sessions.insert(0, session.clone());
                        }

                        prompt.set(String::new());
                        creating.set(false);
                        on_open_session.call(session.session_id);
                    }
                    Err(err) => {
                        error.set(Some(err.to_string()));
                        creating.set(false);
                    }
                }
            });
        }
    };

    rsx! {
        div { class: "worktree-screen",
            div { class: "worktree-topbar",
                button {
                    class: "icon-btn",
                    onclick: move |_| on_open_drawer.call(()),
                    "☰"
                }
                div { class: "topbar-center",
                    span { class: "topbar-title",
                        match detail.read().as_ref() {
                            Some(data) => rsx! { "{data.worktree.name}" },
                            None => rsx! { "Worktree" },
                        }
                    }
                    span { class: "topbar-subtitle", "{short_id(&worktree_id)}" }
                }
                button {
                    class: "icon-btn",
                    onclick: move |_| on_back.call(()),
                    "×"
                }
            }

            div { class: "worktree-content",
                if let Some(data) = detail.read().as_ref() {
                    WorktreeSummary { data: data.clone() }

                    div { class: "worktree-section",
                        div { class: "section-header-row",
                            h3 { "Start Session" }
                        }
                        div { class: "tool-picker", role: "radiogroup", "aria-label": "Agentic tool",
                            ToolButton {
                                value: "junie".to_string(),
                                label: "Junie".to_string(),
                                selected_tool,
                            }
                            ToolButton {
                                value: "codex".to_string(),
                                label: "Codex".to_string(),
                                selected_tool,
                            }
                            ToolButton {
                                value: "claude-code".to_string(),
                                label: "Claude".to_string(),
                                selected_tool,
                            }
                            ToolButton {
                                value: "gemini".to_string(),
                                label: "Gemini".to_string(),
                                selected_tool,
                            }
                        }
                        textarea {
                            class: "worktree-prompt",
                            placeholder: "First prompt for the new session...",
                            rows: "5",
                            value: "{prompt}",
                            oninput: move |e| prompt.set(e.value()),
                        }
                        if let Some(err) = error.read().as_ref() {
                            div { class: "form-status error", "{err}" }
                        }
                        button {
                            class: "btn-primary",
                            disabled: start_disabled,
                            onclick: on_start_session,
                            if *creating.read() { "Starting..." } else { "Start Session" }
                        }
                    }

                    div { class: "worktree-section",
                        div { class: "section-header-row",
                            h3 { "Sessions" }
                            span { class: "settings-sublabel", "{data.sessions.len()}" }
                        }
                        if data.sessions.is_empty() {
                            div { class: "sidebar-empty", "No sessions in this worktree" }
                        } else {
                            div { class: "worktree-session-list",
                                for session in data.sessions.iter() {
                                    WorktreeSessionRow {
                                        session: session.clone(),
                                        on_open_session,
                                    }
                                }
                            }
                        }
                    }
                } else {
                    div { class: "chat-error", "Worktree not found in the loaded board list." }
                }
            }
        }
    }
}

#[component]
fn WorktreeSummary(data: WorktreeDetailData) -> Element {
    let branch = data
        .worktree
        .branch
        .clone()
        .unwrap_or_else(|| "No branch".to_string());
    let status = data
        .worktree
        .status
        .clone()
        .unwrap_or_else(|| "unknown".to_string());
    let path = data
        .worktree
        .path
        .clone()
        .unwrap_or_else(|| "No path".to_string());
    let repo_name = data
        .repo
        .as_ref()
        .map(|repo| repo.name.clone())
        .unwrap_or_else(|| "Unknown repo".to_string());
    let board_name = data
        .board
        .as_ref()
        .map(|board| board.name.clone())
        .unwrap_or_else(|| "No board".to_string());
    let access = data
        .worktree
        .others_can
        .clone()
        .unwrap_or_else(|| "default".to_string());

    rsx! {
        div { class: "worktree-section worktree-summary",
            div { class: "worktree-hero",
                div { class: "worktree-hero-main",
                    h2 { "{data.worktree.name}" }
                    span { class: "worktree-path", "{path}" }
                }
                span { class: "status-badge active", "{status}" }
            }
            div { class: "worktree-meta-grid",
                MetaItem { label: "Repo".to_string(), value: repo_name }
                MetaItem { label: "Board".to_string(), value: board_name }
                MetaItem { label: "Branch".to_string(), value: branch }
                MetaItem { label: "Access".to_string(), value: access }
            }
        }
    }
}

#[component]
fn MetaItem(label: String, value: String) -> Element {
    rsx! {
        div { class: "worktree-meta-item",
            span { class: "settings-sublabel", "{label}" }
            span { class: "settings-label", "{value}" }
        }
    }
}

#[component]
fn ToolButton(value: String, label: String, mut selected_tool: Signal<String>) -> Element {
    let selected = selected_tool.read().as_str() == value.as_str();
    let button_class = if selected {
        "tool-button active"
    } else {
        "tool-button"
    };
    let next_value = value.clone();

    rsx! {
        button {
            class: button_class,
            r#type: "button",
            role: "radio",
            "aria-checked": selected,
            onclick: move |_| selected_tool.set(next_value.clone()),
            "{label}"
        }
    }
}

#[component]
fn WorktreeSessionRow(session: Session, on_open_session: EventHandler<String>) -> Element {
    let session_id = session.session_id.clone();
    let title = session.display_title();
    let status_cls = status_class(&session.status);
    let tool_cls = agent_icon_class(&session.agentic_tool);
    let tool = session.agentic_tool.display_name().to_string();

    rsx! {
        button {
            class: "worktree-session-row",
            onclick: move |_| on_open_session.call(session_id.clone()),
            span { class: "agent-icon {tool_cls}" }
            span { class: "session-info",
                span { class: "session-title", "{title}" }
                span { class: "session-meta",
                    span { class: "status-badge {status_cls}", "{session.status.display_label()}" }
                    span { class: "session-tool", "{tool}" }
                }
            }
        }
    }
}

fn find_worktree_detail(nav: &NavStore, worktree_id: &str) -> Option<WorktreeDetailData> {
    for board in &nav.boards {
        let Some(worktrees) = nav.worktrees_by_board.get(&board.board_id) else {
            continue;
        };
        for worktree in worktrees {
            if worktree.worktree_id != worktree_id {
                continue;
            }

            let repo = nav.repos_by_id.get(&worktree.repo_id).cloned();
            let sessions = nav
                .sessions_by_worktree
                .get(worktree_id)
                .cloned()
                .unwrap_or_default()
                .into_iter()
                .filter(|session| !session.archived.unwrap_or(false))
                .collect();

            return Some(WorktreeDetailData {
                worktree: worktree.clone(),
                board: Some(board.clone()),
                repo,
                sessions,
            });
        }
    }

    None
}

fn short_id(id: &str) -> String {
    id.chars().take(8).collect()
}
