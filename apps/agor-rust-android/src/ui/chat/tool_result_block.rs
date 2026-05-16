use dioxus::prelude::*;

use crate::models::content_block::ContentBlock;

#[component]
pub fn ToolResultBlockView(message_id: String, block: ContentBlock) -> Element {
    let mut expanded = use_signal(|| false);

    let (_tool_use_id, preview, is_error) = match &block {
        ContentBlock::ToolResult {
            tool_use_id,
            content,
            is_error,
        } => {
            let preview = content
                .as_ref()
                .map(|c| c.text_preview())
                .unwrap_or_else(|| "(empty result)".to_string());
            (
                tool_use_id.clone(),
                preview,
                is_error.unwrap_or(false),
            )
        }
        _ => return rsx! {},
    };

    let error_class = if is_error { "error" } else { "" };

    rsx! {
        div { class: "tool-result-block {error_class}",
            div {
                class: "tool-result-header",
                onclick: move |_| {
                    let current = *expanded.read();
                    expanded.set(!current);
                },
                span { class: "result-icon",
                    if is_error { "❌" } else { "✓" }
                }
                span { class: "result-label",
                    if is_error { "Error" } else { "Result" }
                }
                span { class: "expand-icon",
                    if *expanded.read() { "▼" } else { "▶" }
                }
            }

            if *expanded.read() {
                div { class: "tool-result-body",
                    pre { class: "result-text", "{preview}" }
                }
            }
        }
    }
}
