use dioxus::prelude::*;

use crate::ui::chat::chat_screen::ChatScreen;
use crate::ui::settings::SettingsScreen;
use crate::ui::sidebar::Sidebar;

#[derive(Debug, Clone, PartialEq)]
pub enum Route {
    Empty,
    Chat { session_id: String },
    Settings,
}

#[component]
pub fn AppShell() -> Element {
    let mut route = use_signal(|| Route::Empty);
    let mut drawer_open = use_signal(|| false);

    rsx! {
        div { class: "app-shell",
            if *drawer_open.read() {
                div {
                    class: "drawer-overlay",
                    onclick: move |_| drawer_open.set(false),
                }
            }

            div {
                class: if *drawer_open.read() { "sidebar-container open" } else { "sidebar-container" },
                Sidebar {
                    on_select_session: move |id: String| {
                        route.set(Route::Chat { session_id: id });
                        drawer_open.set(false);
                    },
                    on_open_settings: move |_| {
                        route.set(Route::Settings);
                        drawer_open.set(false);
                    },
                }
            }

            div { class: "content-area",
                match route.read().clone() {
                    Route::Empty => rsx! {
                        div { class: "empty-home",
                            div { class: "empty-home-content",
                                span { class: "empty-kicker", "AGOR" }
                                h2 { "What can I help with?" }
                                p { "Select a session or worktree from the list to continue orchestration." }
                                button {
                                    class: "btn-secondary nav-open-btn",
                                    onclick: move |_| {
                                        let current = *drawer_open.read();
                                        drawer_open.set(!current);
                                    },
                                    "Open list"
                                }
                            }
                        }
                    },
                    Route::Chat { session_id } => rsx! {
                        ChatScreen {
                            key: "{session_id}",
                            session_id: session_id.clone(),
                            on_open_drawer: move |_| {
                                let current = *drawer_open.read();
                                drawer_open.set(!current);
                            },
                            on_back: move |_| {
                                route.set(Route::Empty);
                            },
                        }
                    },
                    Route::Settings => rsx! {
                        SettingsScreen {
                            on_back: move |_| {
                                route.set(Route::Empty);
                            },
                            on_open_drawer: move |_| {
                                let current = *drawer_open.read();
                                drawer_open.set(!current);
                            },
                        }
                    },
                }
            }
        }
    }
}
