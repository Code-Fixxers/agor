#![allow(dead_code)]

mod app;
mod models;
mod network;
mod state;
mod ui;
mod util;


fn main() {
    tracing_subscriber::fmt()
        .with_env_filter("agor_android=debug,info")
        .init();

    dioxus::launch(app::App);
}
