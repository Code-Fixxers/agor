use dioxus::prelude::*;

use crate::network::socket_service::ConnectionState;

#[component]
pub fn ConnectionIndicator(state: ConnectionState) -> Element {
    let (label, class) = match state {
        ConnectionState::Connected => ("Connected", "conn-connected"),
        ConnectionState::Connecting => ("Connecting...", "conn-connecting"),
        ConnectionState::Reconnecting => ("Reconnecting...", "conn-reconnecting"),
        ConnectionState::Disconnected => ("Disconnected", "conn-disconnected"),
    };

    rsx! {
        div { class: "connection-indicator {class}",
            span { class: "conn-dot" }
            span { class: "conn-label", "{label}" }
        }
    }
}
