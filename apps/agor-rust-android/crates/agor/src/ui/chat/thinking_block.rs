use dioxus::prelude::*;

#[component]
pub fn ThinkingBlockView(message_id: String, thinking: String, is_streaming: bool) -> Element {
    let mut expanded = use_signal(|| false);

    let preview: String = thinking.chars().take(200).collect();
    let is_long = thinking.len() > 200;

    rsx! {
        div { class: "thinking-block",
            div {
                class: "thinking-header",
                onclick: move |_| {
                    let current = *expanded.read();
                    expanded.set(!current);
                },
                span { class: "thinking-icon", "💭" }
                span { class: "thinking-label",
                    if is_streaming { "Thinking..." } else { "Thinking" }
                }
                if is_long {
                    span { class: "expand-icon",
                        if *expanded.read() { "▼" } else { "▶" }
                    }
                }
            }

            if *expanded.read() || !is_long {
                div { class: "thinking-body",
                    if *expanded.read() {
                        p { "{thinking}" }
                    } else {
                        p { "{preview}" }
                    }
                }
            }
        }
    }
}
