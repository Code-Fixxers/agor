use dioxus::prelude::*;

use crate::models::file_item::{FileDetail, VirtualNode};

#[component]
pub fn FileBrowserSheet(
    worktree_id: String,
    on_close: EventHandler<()>,
) -> Element {
    let mut current_path = use_signal(|| Vec::<String>::new());
    let mut file_detail = use_signal(|| Option::<FileDetail>::None);
    let tree = use_signal(|| Vec::<VirtualNode>::new());
    let mut loading = use_signal(|| true);

    // In a full implementation, this would fetch from the daemon's file service
    // via socket: find "file" { worktree_id }
    use_effect(move || {
        loading.set(false);
    });

    let current_nodes = use_memo(move || {
        let t = tree.read();
        let path = current_path.read();

        let mut nodes = t.as_slice();
        for segment in path.iter() {
            if let Some(node) = nodes.iter().find(|n| n.name == *segment && n.is_directory) {
                nodes = &node.children;
            } else {
                return Vec::new();
            }
        }
        nodes.to_vec()
    });

    let breadcrumb = use_memo(move || {
        let path = current_path.read();
        let mut crumbs = vec!["Root".to_string()];
        crumbs.extend(path.iter().cloned());
        crumbs
    });

    let mut on_navigate_dir = move |name: String| {
        current_path.write().push(name);
        file_detail.set(None);
    };

    let on_navigate_up = move |_| {
        current_path.write().pop();
        file_detail.set(None);
    };

    let mut on_navigate_root = move |_| {
        current_path.write().clear();
        file_detail.set(None);
    };

    rsx! {
        div { class: "modal-overlay",
            onclick: move |_| on_close.call(()),
            div { class: "modal-content wide tall",
                onclick: move |e| e.stop_propagation(),

                div { class: "modal-header",
                    span { "Files" }
                    button {
                        class: "modal-close",
                        onclick: move |_| on_close.call(()),
                        "×"
                    }
                }

                // Breadcrumbs
                div { class: "breadcrumbs",
                    for (i, crumb) in breadcrumb.read().iter().enumerate() {
                        if i > 0 {
                            span { class: "breadcrumb-sep", " / " }
                        }
                        {
                            let idx = i;
                            rsx! {
                                button {
                                    class: "breadcrumb-btn",
                                    onclick: move |_| {
                                        if idx == 0 {
                                            on_navigate_root(());
                                        } else {
                                            let mut p = current_path.write();
                                            p.truncate(idx);
                                            file_detail.set(None);
                                        }
                                    },
                                    "{crumb}"
                                }
                            }
                        }
                    }
                }

                if *loading.read() {
                    div { class: "file-loading", "Loading files..." }
                }

                // File detail view
                if let Some(detail) = file_detail.read().as_ref() {
                    div { class: "file-detail",
                        div { class: "file-detail-header",
                            button {
                                class: "btn-secondary",
                                onclick: move |_| file_detail.set(None),
                                "Back"
                            }
                            span { class: "file-name", "{detail.file_name()}" }
                        }

                        if detail.is_image() {
                            if let Some(b64) = &detail.base64 {
                                {
                                    let mt = detail.media_type.as_deref().unwrap_or("image/png");
                                    let data_uri = format!("data:{mt};base64,{b64}");
                                    rsx! {
                                        img {
                                            src: "{data_uri}",
                                            class: "file-image-preview",
                                        }
                                    }
                                }
                            }
                        } else if let Some(content) = &detail.content {
                            pre { class: "file-text-preview", "{content}" }
                        } else {
                            p { "No preview available" }
                        }
                    }
                } else {
                    // Directory listing
                    div { class: "file-list",
                        if !current_path.read().is_empty() {
                            div {
                                class: "file-row directory",
                                onclick: on_navigate_up,
                                span { class: "file-icon", "📁" }
                                span { class: "file-name", ".." }
                            }
                        }

                        if current_nodes.read().is_empty() && !*loading.read() {
                            div { class: "file-empty", "No files found" }
                        }

                        for node in current_nodes.read().iter() {
                            {
                                let name = node.name.clone();
                                let is_dir = node.is_directory;
                                let size = node.size;

                                rsx! {
                                    div {
                                        class: if is_dir { "file-row directory" } else { "file-row file" },
                                        onclick: move |_| {
                                            if is_dir {
                                                on_navigate_dir(name.clone());
                                            }
                                            // File click would load detail via daemon
                                        },
                                        span { class: "file-icon",
                                            if is_dir { "📁" } else { "📄" }
                                        }
                                        span { class: "file-name", "{name}" }
                                        if let Some(s) = size {
                                            span { class: "file-size", "{format_size(s)}" }
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
}

fn format_size(bytes: i64) -> String {
    if bytes < 1024 {
        format!("{bytes} B")
    } else if bytes < 1024 * 1024 {
        format!("{:.1} KB", bytes as f64 / 1024.0)
    } else {
        format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
    }
}
