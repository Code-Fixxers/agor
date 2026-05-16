use dioxus::prelude::*;

use crate::models::content_block::ContentBlock;

#[component]
pub fn ImageBlockView(message_id: String, block: ContentBlock) -> Element {
    let source = match &block {
        ContentBlock::Image { source } => source.clone(),
        _ => return rsx! {},
    };

    let src = if let Some(url) = &source.url {
        url.clone()
    } else if let Some(data) = &source.data {
        let media_type = source.media_type.as_deref().unwrap_or("image/png");
        format!("data:{media_type};base64,{data}")
    } else {
        return rsx! {
            div { class: "image-block-error", "Image data unavailable" }
        };
    };

    rsx! {
        div { class: "image-block",
            img {
                src: "{src}",
                class: "chat-image",
                loading: "lazy",
            }
        }
    }
}
