use dioxus::prelude::*;

use crate::state::auth::AuthStore;
use crate::state::navigation::{NavStore, SidebarRow};
use crate::state::storage::AppStorage;
use crate::ui::common::agent_icon::agent_icon_class;
use crate::ui::common::status_badge::status_class;

#[component]
pub fn Sidebar(
    on_select_session: EventHandler<String>,
    on_open_settings: EventHandler<()>,
) -> Element {
    let nav = use_context::<Signal<NavStore>>();
    let storage = use_context::<Signal<AppStorage>>();
    let auth = use_context::<Signal<AuthStore>>();

    let mut search_query = use_signal(|| String::new());

    let rows = use_memo(move || {
        let n = nav.read();
        if !search_query.read().is_empty() {
            let q = search_query.read().to_lowercase();
            n.sessions
                .iter()
                .filter(|s| s.display_title().to_lowercase().contains(&q))
                .take(20)
                .map(|s| SidebarRow::SessionRow {
                    session: s.clone(),
                    depth: 0,
                    is_favorite: n.favorites.contains(&s.session_id),
                })
                .collect::<Vec<_>>()
        } else {
            n.build_sidebar_rows()
        }
    });

    let user_info = use_memo(move || {
        let a = auth.read();
        match &a.user {
            Some(u) => format!(
                "{} {}",
                u.emoji.as_deref().unwrap_or(""),
                u.name
            ),
            None => "Not connected".to_string(),
        }
    });

    let profiles = use_memo(move || storage.read().profiles.clone());

    rsx! {
        div { class: "sidebar",
            div { class: "sidebar-header",
                span { class: "user-info", "{user_info}" }
                button {
                    class: "icon-btn",
                    onclick: move |_| on_open_settings.call(()),
                    "⚙"
                }
            }

            if profiles.read().len() > 1 {
                div { class: "profile-chips-bar",
                    for profile in profiles.read().iter() {
                        button {
                            class: "profile-chip-small",
                            "{profile.label}"
                        }
                    }
                }
            }

            div { class: "sidebar-search",
                input {
                    r#type: "text",
                    placeholder: "Search sessions...",
                    value: "{search_query}",
                    oninput: move |e| search_query.set(e.value()),
                }
            }

            div { class: "sidebar-list",
                for row in rows.read().iter() {
                    {match row {
                        SidebarRow::SectionHeader { label } => rsx! {
                            div { class: "sidebar-section-header", "{label}" }
                        },
                        SidebarRow::BoardHeader { board, expanded: _ } => {
                            let name = board.name.clone();
                            let emoji = board.emoji.clone().unwrap_or_default();
                            rsx! {
                                div { class: "sidebar-board-row",
                                    span { class: "board-emoji", "{emoji}" }
                                    span { class: "board-name", "{name}" }
                                }
                            }
                        }
                        SidebarRow::WorktreeRow { worktree, repo_name, expanded: _ } => {
                            let wt_name = worktree.name.clone();
                            let branch = worktree.branch.clone().unwrap_or_default();
                            let repo = repo_name.clone();
                            rsx! {
                                div { class: "sidebar-worktree-row",
                                    div { class: "worktree-info",
                                        span { class: "worktree-name", "{wt_name}" }
                                        if !repo.is_empty() {
                                            span { class: "worktree-repo", "{repo}" }
                                        }
                                        if !branch.is_empty() {
                                            span { class: "worktree-branch", "{branch}" }
                                        }
                                    }
                                }
                            }
                        }
                        SidebarRow::SessionRow { session, depth, is_favorite } => {
                            let session_id = session.session_id.clone();
                            let title = session.display_title();
                            let status = session.status.clone();
                            let tool = session.agentic_tool.display_name().to_string();
                            let depth = *depth;
                            let fav = *is_favorite;
                            let status_cls = status_class(&status);
                            let tool_cls = agent_icon_class(&session.agentic_tool);

                            rsx! {
                                div {
                                    class: format!("sidebar-session-row depth-{depth}"),
                                    onclick: move |_| on_select_session.call(session_id.clone()),

                                    span { class: "agent-icon {tool_cls}" }

                                    div { class: "session-info",
                                        span { class: "session-title", "{title}" }
                                        div { class: "session-meta",
                                            span { class: "status-badge {status_cls}",
                                                "{status.display_label()}"
                                            }
                                            span { class: "session-tool", "{tool}" }
                                        }
                                    }

                                    if fav {
                                        span { class: "favorite-star", "★" }
                                    }
                                }
                            }
                        }
                        SidebarRow::OlderTasksRow { count } => rsx! {
                            div { class: "sidebar-older-tasks",
                                "Show {count} older tasks"
                            }
                        },
                    }}
                }

                if nav.read().is_loading {
                    div { class: "sidebar-loading", "Loading..." }
                }

                if rows.read().is_empty() && !nav.read().is_loading {
                    div { class: "sidebar-empty", "No sessions found" }
                }
            }
        }
    }
}
