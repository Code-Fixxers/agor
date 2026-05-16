use dioxus::prelude::*;

use crate::models::content_block::ContentBlock;

#[component]
pub fn ToolUseBlockView(message_id: String, block: ContentBlock) -> Element {
    let mut expanded = use_signal(|| false);
    let mut show_full = use_signal(|| false);

    let (name, input_summary, full_json) = match &block {
        ContentBlock::ToolUse { name, input, .. } => {
            let summary = ContentBlock::input_summary(input);
            let full = serde_json::to_string_pretty(input).unwrap_or_default();
            (name.clone(), summary, full)
        }
        _ => return rsx! {},
    };

    let preview: String = full_json.chars().take(480).collect();
    let is_truncated = full_json.len() > 480;

    rsx! {
        div { class: "tool-use-block",
            div {
                class: "tool-use-header",
                onclick: move |_| {
                    let current = *expanded.read();
                    expanded.set(!current);
                },
                span { class: "tool-icon", "🔧" }
                span { class: "tool-name", "{name}" }
                span { class: "tool-summary", "{input_summary}" }
                span { class: "expand-icon",
                    if *expanded.read() { "▼" } else { "▶" }
                }
            }

            if *expanded.read() {
                div { class: "tool-use-body",
                    pre { class: "tool-json", "{preview}" }
                    if is_truncated {
                        button {
                            class: "show-full-btn",
                            onclick: move |_| show_full.set(true),
                            "Show full ({full_json.len()} chars)"
                        }
                    }
                }
            }

            if *show_full.read() {
                div { class: "modal-overlay",
                    onclick: move |_| show_full.set(false),
                    div { class: "modal-content",
                        onclick: move |e| e.stop_propagation(),
                        div { class: "modal-header",
                            span { "Tool Input: {name}" }
                            button {
                                class: "modal-close",
                                onclick: move |_| show_full.set(false),
                                "×"
                            }
                        }
                        pre { class: "tool-json-full", "{full_json}" }
                    }
                }
            }
        }
    }
}
