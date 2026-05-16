use dioxus::prelude::*;

use crate::models::message::{Message, MessageContent, MessageRole};

#[component]
pub fn MessageBubble(
    message: Message,
    is_streaming: bool,
    streaming_text: Option<String>,
) -> Element {
    let role_class = match message.role {
        MessageRole::User => "user",
        MessageRole::Assistant => "assistant",
        MessageRole::System => "system",
    };

    let text = if is_streaming {
        streaming_text.unwrap_or_default()
    } else {
        match &message.content {
            MessageContent::Text(t) => t.clone(),
            MessageContent::Blocks(blocks) => {
                blocks
                    .iter()
                    .filter_map(|b| match b {
                        crate::models::ContentBlock::Text { text } => Some(text.as_str()),
                        _ => None,
                    })
                    .collect::<Vec<_>>()
                    .join("\n")
            }
            _ => String::new(),
        }
    };

    if text.is_empty() {
        return rsx! {};
    }

    let has_code_block = text.contains("```");
    let has_markdown = text.contains("**")
        || text.contains("##")
        || text.contains("- ")
        || has_code_block
        || text.contains("[");

    rsx! {
        div { class: "message-bubble {role_class}",
            if has_markdown && !is_streaming {
                div {
                    class: "markdown-content",
                    dangerous_inner_html: render_markdown(&text),
                }
            } else {
                div { class: "plain-text-content",
                    "{text}"
                }
            }

            if let Some(meta) = &message.metadata {
                if let Some(model) = &meta.model {
                    div { class: "message-meta",
                        span { class: "message-model", "{model}" }
                    }
                }
            }
        }
    }
}

fn render_markdown(text: &str) -> String {
    use pulldown_cmark::{html, Options, Parser};

    let options = Options::all();
    let parser = Parser::new_ext(text, options);
    let mut html_output = String::new();
    html::push_html(&mut html_output, parser);
    html_output
}
