use dioxus::prelude::*;

use crate::state::auth::{AuthState, AuthStore};
use crate::state::navigation::NavStore;
use crate::state::chat::ChatStore;
use crate::state::storage::AppStorage;
use crate::ui::app_shell::AppShell;
use crate::ui::login::LoginScreen;

#[component]
pub fn App() -> Element {
    let _storage = use_context_provider(|| Signal::new(AppStorage::load()));
    let auth = use_context_provider(|| Signal::new(AuthStore::new()));
    let _nav = use_context_provider(|| Signal::new(NavStore::new()));
    let _chat = use_context_provider(|| Signal::new(ChatStore::new()));

    let auth_state = use_memo(move || auth.read().state.clone());

    rsx! {
        document::Link { rel: "stylesheet", href: asset!("/assets/main.css") }
        match auth_state() {
            AuthState::Unknown | AuthState::NeedsLogin => {
                rsx! { LoginScreen {} }
            }
            AuthState::Authenticated { .. } => {
                rsx! { AppShell {} }
            }
        }
    }
}
