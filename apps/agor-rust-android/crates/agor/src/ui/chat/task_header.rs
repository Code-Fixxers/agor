use dioxus::prelude::*;

use crate::models::task::AgorTask;

#[component]
pub fn TaskHeaderView(task: AgorTask, expanded: bool, message_count: usize) -> Element {
    let title = task
        .title
        .as_deref()
        .or(task.prompt.as_deref())
        .map(|t| {
            if t.len() > 80 {
                format!("{}...", &t[..80])
            } else {
                t.to_string()
            }
        })
        .unwrap_or_else(|| format!("Task {}", &task.task_id[..8]));

    let status_label = format!("{:?}", task.status);

    rsx! {
        div { class: "task-header",
            div { class: "task-header-left",
                span { class: "expand-icon",
                    if expanded { "▼" } else { "▶" }
                }
                span { class: "task-title", "{title}" }
            }
            div { class: "task-header-right",
                span { class: "task-status", "{status_label}" }
                if message_count > 0 {
                    span { class: "task-count", "{message_count} msgs" }
                }
            }
        }
    }
}
