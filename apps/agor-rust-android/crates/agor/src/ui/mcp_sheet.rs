use dioxus::prelude::*;

use crate::models::mcp_server::{MCPServer, SessionMCPServer};
use crate::network::agor_client::AgorClient;
use agor_shared::logger::AppLogger;

#[component]
pub fn McpSessionSheet(
    session_id: String,
    on_close: EventHandler<()>,
) -> Element {
    let mut active_servers = use_signal(|| Vec::<SessionMCPServer>::new());
    let mut available_servers = use_signal(|| Vec::<MCPServer>::new());
    let mut loading = use_signal(|| true);

    let sid = session_id.clone();
    use_effect(move || {
        let sid = sid.clone();
        spawn(async move {
            let logger = AppLogger::new();
            let client = AgorClient::new(logger.clone());

            let (active, available) = tokio::join!(
                client.list_session_mcp_servers(&sid),
                client.list_mcp_servers(),
            );

            if let Ok(a) = active {
                active_servers.set(a);
            }
            if let Ok(a) = available {
                available_servers.set(a);
            }
            loading.set(false);
        });
    });

    let _active_ids: Vec<String> = active_servers
        .read()
        .iter()
        .map(|s| s.mcp_server_id.clone())
        .collect();

    let inactive_servers = use_memo(move || {
        let active_ids: Vec<String> = active_servers
            .read()
            .iter()
            .map(|s| s.mcp_server_id.clone())
            .collect();
        available_servers
            .read()
            .iter()
            .filter(|s| !active_ids.contains(&s.mcp_server_id))
            .cloned()
            .collect::<Vec<_>>()
    });

    rsx! {
        div { class: "modal-overlay",
            onclick: move |_| on_close.call(()),
            div { class: "modal-content wide",
                onclick: move |e| e.stop_propagation(),

                div { class: "modal-header",
                    span { "MCP Servers" }
                    button {
                        class: "modal-close",
                        onclick: move |_| on_close.call(()),
                        "×"
                    }
                }

                if *loading.read() {
                    div { class: "mcp-loading", "Loading servers..." }
                }

                // Active servers
                div { class: "mcp-section",
                    h4 { "Active" }
                    if active_servers.read().is_empty() {
                        p { class: "mcp-empty", "No active MCP servers" }
                    }

                    for server in active_servers.read().iter() {
                        {
                            let server_id = server.mcp_server_id.clone();
                            let enabled = server.enabled;
                            let name = available_servers
                                .read()
                                .iter()
                                .find(|s| s.mcp_server_id == server_id)
                                .map(|s| s.name.clone())
                                .unwrap_or_else(|| server_id.clone());
                            let sid = session_id.clone();

                            rsx! {
                                div { class: "mcp-server-row active",
                                    div { class: "mcp-server-info",
                                        span { class: "mcp-server-name", "{name}" }
                                        span { class: if enabled { "mcp-status enabled" } else { "mcp-status disabled" },
                                            if enabled { "Enabled" } else { "Disabled" }
                                        }
                                    }
                                    div { class: "mcp-server-actions",
                                        {
                                            let sid_toggle = sid.clone();
                                            let server_id_toggle = server_id.clone();
                                            rsx! {
                                                button {
                                                    class: "btn-small",
                                                    onclick: move |_| {
                                                        let sid = sid_toggle.clone();
                                                        let server_id = server_id_toggle.clone();
                                                        let new_enabled = !enabled;
                                                        spawn(async move {
                                                            let logger = AppLogger::new();
                                                            let client = AgorClient::new(logger);
                                                            let _ = client
                                                                .set_session_mcp_server_enabled(
                                                                    &sid,
                                                                    &server_id,
                                                                    new_enabled,
                                                                )
                                                                .await;
                                                        });
                                                    },
                                                    if enabled { "Disable" } else { "Enable" }
                                                }
                                            }
                                        }
                                        {
                                            let sid_remove = sid.clone();
                                            let server_id_remove = server_id.clone();
                                            rsx! {
                                                button {
                                                    class: "btn-small btn-danger",
                                                    onclick: move |_| {
                                                        let sid = sid_remove.clone();
                                                        let server_id = server_id_remove.clone();
                                                        spawn(async move {
                                                            let logger = AppLogger::new();
                                                            let client = AgorClient::new(logger);
                                                            let _ = client
                                                                .remove_session_mcp_server(
                                                                    &sid, &server_id,
                                                                )
                                                                .await;
                                                        });
                                                    },
                                                    "Remove"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Available servers
                div { class: "mcp-section",
                    h4 { "Available" }
                    if inactive_servers.read().is_empty() {
                        p { class: "mcp-empty",
                            "No additional servers available. Configure MCP servers in the Agor web UI."
                        }
                    }

                    for server in inactive_servers.read().iter() {
                        {
                            let server_id = server.mcp_server_id.clone();
                            let name = server.name.clone();
                            let desc = server.description.clone().unwrap_or_default();
                            let sid = session_id.clone();

                            rsx! {
                                div { class: "mcp-server-row available",
                                    div { class: "mcp-server-info",
                                        span { class: "mcp-server-name", "{name}" }
                                        if !desc.is_empty() {
                                            span { class: "mcp-server-desc", "{desc}" }
                                        }
                                    }
                                    button {
                                        class: "btn-small btn-primary",
                                        onclick: move |_| {
                                            let sid = sid.clone();
                                            let server_id = server_id.clone();
                                            spawn(async move {
                                                let logger = AppLogger::new();
                                                let client = AgorClient::new(logger);
                                                let _ = client
                                                    .add_session_mcp_server(&sid, &server_id)
                                                    .await;
                                            });
                                        },
                                        "Add"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
