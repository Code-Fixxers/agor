use dioxus::prelude::*;
use serde_json::Value;
use std::collections::HashSet;
use tokio::sync::broadcast;

use crate::models::{HermesConfig, DEFAULT_WEB_UI_URL};
use crate::webui::{
    ApprovalResponse, ChatStartRequest, ClarifyResponse, HermesWebUiClient, NewSessionRequest,
    WebUiAttachment, WebUiMessage, WebUiModelsResponse, WebUiProfilesResponse, WebUiSession,
    WebUiStreamEvent, WebUiWorkspaceList,
};
use crate::webui_state::{HermesBusyInputMode, QueuedWebUiPrompt, WebUiChatState};

#[derive(Debug, Clone, PartialEq)]
struct PickerOption {
    value: String,
    label: String,
    detail: Option<String>,
    provider: Option<String>,
}

#[component]
pub fn HermesWebUiNativeScreen(
    config: HermesConfig,
    on_open_settings: EventHandler<()>,
    on_open_web_fallback: EventHandler<()>,
) -> Element {
    let web_url = config
        .web_ui_url
        .as_deref()
        .map(str::trim)
        .filter(|url| !url.is_empty())
        .unwrap_or(DEFAULT_WEB_UI_URL)
        .to_string();
    let client = use_signal(move || HermesWebUiClient::new(&web_url));
    let mut state = use_signal(WebUiChatState::default);
    let mut draft = use_signal(String::new);
    let mut filter = use_signal(String::new);
    let mut model = use_signal(|| config.model.clone().unwrap_or_default());
    let mut model_provider = use_signal(|| Option::<String>::None);
    let mut workspace = use_signal(|| String::new());
    let mut profile = use_signal(|| "default".to_string());
    let workspace_options = use_signal(Vec::<PickerOption>::new);
    let model_options = use_signal(Vec::<PickerOption>::new);
    let profile_options = use_signal(Vec::<PickerOption>::new);
    let mut reasoning_effort = use_signal(String::new);
    let mut workspace_drawer_open = use_signal(|| false);
    let mut pending_attachments = use_signal(Vec::<WebUiAttachment>::new);
    let mut loading = use_signal(|| false);
    let mut started = use_signal(|| false);
    let mut clarify_answer = use_signal(String::new);

    use_effect(move || {
        if *started.peek() {
            return;
        }
        started.set(true);
        loading.set(true);
        let client = client.read().clone();
        spawn(async move {
            if let Err(err) = load_webui_home(
                client.clone(),
                state,
                workspace,
                model,
                model_provider,
                profile,
                workspace_options,
                model_options,
                profile_options,
                reasoning_effort,
            )
            .await
            {
                state.write().last_error = Some(err);
            }
            loading.set(false);
        });
    });

    let visible_sessions = use_memo(move || {
        let needle = filter.read().trim().to_lowercase();
        state
            .read()
            .sessions
            .iter()
            .filter(|session| {
                needle.is_empty()
                    || session.title.to_lowercase().contains(&needle)
                    || session
                        .workspace
                        .as_deref()
                        .unwrap_or_default()
                        .to_lowercase()
                        .contains(&needle)
            })
            .cloned()
            .collect::<Vec<_>>()
    });

    let on_refresh = move |_| {
        loading.set(true);
        let client = client.read().clone();
        spawn(async move {
            if let Err(err) = load_webui_home(
                client.clone(),
                state,
                workspace,
                model,
                model_provider,
                profile,
                workspace_options,
                model_options,
                profile_options,
                reasoning_effort,
            )
            .await
            {
                state.write().last_error = Some(err);
            }
            loading.set(false);
        });
    };

    let on_new = move |_| {
        let client = client.read().clone();
        loading.set(true);
        spawn(async move {
            match client
                .create_session(&NewSessionRequest {
                    workspace: clean_signal_string(&workspace),
                    model: clean_signal_string(&model),
                    model_provider: model_provider.read().clone(),
                    profile: clean_signal_string(&profile),
                })
                .await
            {
                Ok(session) => {
                    apply_session_controls(&session, workspace, model, model_provider, profile);
                    state.write().apply_session_detail(session);
                }
                Err(err) => state.write().last_error = Some(err.to_string()),
            }
            loading.set(false);
        });
    };

    let on_delete = move |_| {
        let sid = state.read().selected_session_id.clone();
        let Some(sid) = sid else {
            return;
        };
        let client = client.read().clone();
        loading.set(true);
        spawn(async move {
            match client.delete_session(&sid).await {
                Ok(()) => {
                    state.write().selected_session_id = None;
                    state.write().selected_session = None;
                    match client.list_sessions().await {
                        Ok(sessions) => state.write().apply_session_list(sessions),
                        Err(err) => state.write().last_error = Some(err.to_string()),
                    }
                }
                Err(err) => state.write().last_error = Some(err.to_string()),
            }
            loading.set(false);
        });
    };

    let on_cancel = move |_| {
        let stream_id = state.read().active_stream_id.clone();
        let Some(stream_id) = stream_id else {
            return;
        };
        let client = client.read().clone();
        spawn(async move {
            match client.cancel_stream(&stream_id).await {
                Ok(()) => state.write().clear_runtime(),
                Err(err) => state.write().last_error = Some(err.to_string()),
            }
        });
    };

    let on_attach = move |event: FormEvent| {
        let Some(file_engine) = event.files() else {
            return;
        };
        let client = client.read().clone();
        loading.set(true);
        spawn(async move {
            let session_id = match ensure_session(
                client.clone(),
                state,
                clean_signal_string(&workspace),
                clean_signal_string(&model),
                model_provider.read().clone(),
                clean_signal_string(&profile),
            )
            .await
            {
                Ok(session_id) => session_id,
                Err(err) => {
                    state.write().last_error = Some(err);
                    loading.set(false);
                    return;
                }
            };

            for name in file_engine.files() {
                let Some(bytes) = file_engine.read_file(&name).await else {
                    state.write().last_error = Some(format!("Unable to read attachment {name}"));
                    continue;
                };
                match client.upload_file(&session_id, &name, bytes).await {
                    Ok(uploaded) => pending_attachments.write().push(uploaded),
                    Err(err) => state.write().last_error = Some(err.to_string()),
                }
            }
            loading.set(false);
        });
    };

    let pending_approval = state.read().pending_approval.clone();
    let pending_clarify = state.read().pending_clarify.clone();
    let composer_blocked = pending_approval.is_some() || pending_clarify.is_some();
    let messages = state.read().current_messages_with_live_turn();
    let queued_count = state.read().queued_prompts.len();
    let attachment_count = pending_attachments.read().len();
    let selected_workspace_label = compact_label(&workspace.read(), 28);
    let selected_model_label = selected_option_label(&model_options.read(), &model.read())
        .unwrap_or_else(|| compact_label(&model.read(), 28));
    let selected_profile_label = selected_option_label(&profile_options.read(), &profile.read())
        .unwrap_or_else(|| compact_label(&profile.read(), 18));
    let reasoning_title = format!("Reasoning: {}", reasoning_label(&reasoning_effort.read()));
    let layout_class = if *workspace_drawer_open.read() {
        "hermes-native-layout with-workspace-drawer"
    } else {
        "hermes-native-layout"
    };

    rsx! {
        div { class: "hermes-screen hermes-native-webui",
            div { class: "hermes-topbar",
                span { class: "topbar-title", "Hermes" }
                div { class: "topbar-actions",
                    button { class: "icon-btn", title: "Refresh", onclick: on_refresh, "↻" }
                    button { class: "icon-btn", title: "New conversation", onclick: on_new, "+" }
                    button { class: "icon-btn", title: "Delete selected session", onclick: on_delete, "⌫" }
                    button { class: "icon-btn", title: "Open upstream Web UI", onclick: move |_| on_open_web_fallback.call(()), "↗" }
                    button { class: "icon-btn", title: "Settings", onclick: move |_| on_open_settings.call(()), "⚙" }
                }
            }

            div { class: "{layout_class}",
                aside { class: "hermes-native-sidebar",
                    input {
                        class: "hermes-session-filter",
                        placeholder: "Filter conversations...",
                        value: "{filter}",
                        oninput: move |e| filter.set(e.value()),
                    }
                    div { class: "hermes-session-list",
                        if *loading.read() {
                            div { class: "chat-loading", "Loading Hermes..." }
                        }
                        for session in visible_sessions.read().iter() {
                            {
                                let sid = session.session_id.clone();
                                let selected = state.read().selected_session_id.as_deref() == Some(sid.as_str());
                                let title = if session.title.trim().is_empty() { "Untitled" } else { session.title.as_str() };
                                let meta = session.workspace.clone().unwrap_or_default();
                                rsx! {
                                    button {
                                        key: "{sid}",
                                        class: if selected { "sidebar-session-row selected" } else { "sidebar-session-row" },
                                        onclick: move |_| {
                                            let sid = sid.clone();
                                            let client = client.read().clone();
                                            loading.set(true);
                                            spawn(async move {
                                                match client.get_session(&sid).await {
                                                    Ok(session) => {
                                                        apply_session_controls(&session, workspace, model, model_provider, profile);
                                                        state.write().apply_session_detail(session);
                                                    }
                                                    Err(err) => state.write().last_error = Some(err.to_string()),
                                                }
                                                loading.set(false);
                                            });
                                        },
                                        span { class: "session-info",
                                            span { class: "session-title", "{title}" }
                                            span { class: "session-meta",
                                                if session.is_streaming { span { class: "status-dot running" } }
                                                "{meta}"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                section { class: "hermes-native-main",
                    div { class: "hermes-control-row",
                        button {
                            class: "hermes-control-chip",
                            title: "Open workspace drawer",
                            onclick: move |_| {
                                let is_open = *workspace_drawer_open.read();
                                workspace_drawer_open.set(!is_open);
                            },
                            span { class: "control-chip-kicker", "Workspace" }
                            span { class: "control-chip-value", "{selected_workspace_label}" }
                        }
                        select {
                            class: "hermes-control-select",
                            title: "Conversation model",
                            value: "{model}",
                            onchange: move |e| {
                                let value = e.value();
                                let provider = model_options
                                    .read()
                                    .iter()
                                    .find(|option| option.value == value)
                                    .and_then(|option| option.provider.clone());
                                model.set(value);
                                model_provider.set(provider);
                            },
                            if model_options.read().is_empty() {
                                option { value: "{model}", "{selected_model_label}" }
                            } else {
                                for option in model_options.read().iter() {
                                    option {
                                        key: "{option.value}",
                                        value: "{option.value}",
                                        "{option.label}"
                                    }
                                }
                            }
                        }
                        select {
                            class: "hermes-control-select",
                            title: "Agent profile",
                            value: "{profile}",
                            onchange: move |e| profile.set(e.value()),
                            if profile_options.read().is_empty() {
                                option { value: "{profile}", "{selected_profile_label}" }
                            } else {
                                for option in profile_options.read().iter() {
                                    option {
                                        key: "{option.value}",
                                        value: "{option.value}",
                                        "{option.label}"
                                    }
                                }
                            }
                        }
                        select {
                            class: "hermes-control-select",
                            title: "{reasoning_title}",
                            value: "{reasoning_effort}",
                            onchange: move |e| {
                                let effort = e.value();
                                reasoning_effort.set(effort.clone());
                                let client = client.read().clone();
                                spawn(async move {
                                    match client.set_reasoning_effort(&effort).await {
                                        Ok(status) => {
                                            reasoning_effort.set(status.reasoning_effort.unwrap_or(effort));
                                        }
                                        Err(err) => state.write().last_error = Some(err.to_string()),
                                    }
                                });
                            },
                            option { value: "none", "Reasoning: None" }
                            option { value: "minimal", "Reasoning: Minimal" }
                            option { value: "low", "Reasoning: Low" }
                            option { value: "medium", "Reasoning: Medium" }
                            option { value: "high", "Reasoning: High" }
                            option { value: "xhigh", "Reasoning: Extra High" }
                        }
                        select {
                            class: "hermes-control-select",
                            title: "Busy message behavior",
                            value: "{busy_mode_value(state.read().busy_input_mode)}",
                            onchange: move |e| {
                                state.write().busy_input_mode = match e.value().as_str() {
                                    "interrupt" => HermesBusyInputMode::Interrupt,
                                    "steer" => HermesBusyInputMode::Steer,
                                    _ => HermesBusyInputMode::Queue,
                                };
                            },
                            option { value: "queue", "Queue" }
                            option { value: "interrupt", "Interrupt" }
                            option { value: "steer", "Steer" }
                        }
                    }

                    div { class: "hermes-messages",
                        if state.read().selected_session.is_none() && !*loading.read() {
                            div { class: "hermes-empty",
                                p { "Select or create a Hermes conversation" }
                            }
                        }
                        for (idx, message) in messages.iter().enumerate() {
                            WebUiMessageView { key: "{idx}", message: message.clone() }
                        }
                        if let Some(status) = state.read().compression_status.as_ref() {
                            div { class: "hermes-status-card", "{status}" }
                        }
                        if queued_count > 0 {
                            div { class: "hermes-status-card", "{queued_count} queued message(s)" }
                        }
                        if attachment_count > 0 {
                            div { class: "hermes-status-card", "{attachment_count} attachment(s) ready" }
                        }
                        if let Some(err) = state.read().last_error.as_ref() {
                            div { class: "hermes-error-bubble",
                                p { class: "error-text", "{err}" }
                            }
                        }
                    }

                    if let Some(payload) = pending_approval {
                        ApprovalCard {
                            payload,
                            on_choice: move |choice: String| {
                                let sid = state.read().selected_session_id.clone();
                                let approval_id = state
                                    .read()
                                    .pending_approval
                                    .as_ref()
                                    .and_then(|p| p.get("approval_id"))
                                    .and_then(Value::as_str)
                                    .map(ToString::to_string);
                                let Some(sid) = sid else { return; };
                                let client = client.read().clone();
                                spawn(async move {
                                    match client.respond_approval(&ApprovalResponse {
                                        session_id: sid,
                                        choice,
                                        approval_id,
                                    }).await {
                                        Ok(()) => state.write().pending_approval = None,
                                        Err(err) => state.write().last_error = Some(err.to_string()),
                                    }
                                });
                            },
                        }
                    }

                    if let Some(payload) = pending_clarify {
                        ClarifyCard {
                            payload,
                            answer: clarify_answer.read().clone(),
                            on_input: move |text: String| clarify_answer.set(text),
                            on_send: move |_| {
                                let sid = state.read().selected_session_id.clone();
                                let answer = clarify_answer.read().trim().to_string();
                                if answer.is_empty() {
                                    return;
                                }
                                let Some(sid) = sid else { return; };
                                let client = client.read().clone();
                                clarify_answer.set(String::new());
                                spawn(async move {
                                    match client.respond_clarify(&ClarifyResponse {
                                        session_id: sid,
                                        answer,
                                    }).await {
                                        Ok(()) => state.write().pending_clarify = None,
                                        Err(err) => state.write().last_error = Some(err.to_string()),
                                    }
                                });
                            },
                        }
                    }

                    div { class: "hermes-input-bar",
                        label {
                            class: "icon-btn attach-btn",
                            title: "Attach files",
                            "📎"
                            input {
                                class: "visually-hidden",
                                r#type: "file",
                                multiple: true,
                                onchange: on_attach,
                            }
                        }
                        textarea {
                            class: "hermes-textarea",
                            placeholder: "Message Hermes...",
                            value: "{draft}",
                            disabled: composer_blocked,
                            rows: "1",
                            oninput: move |e| draft.set(e.value()),
                            onkeydown: move |e: KeyboardEvent| {
                                if e.key() == Key::Enter && !e.modifiers().shift() {
                                    e.prevent_default();
                                    if !draft.read().trim().is_empty() {
                                        let prompt = draft.read().trim().to_string();
                                        draft.set(String::new());
                                        start_or_route_prompt(
                                            client.read().clone(),
                                            state,
                                            prompt,
                                            pending_attachments.write().drain(..).collect(),
                                            clean_signal_string(&model),
                                            clean_signal_string(&workspace),
                                            model_provider.read().clone(),
                                            clean_signal_string(&profile),
                                        );
                                    }
                                }
                            },
                        }
                        if state.read().is_busy() {
                            button { class: "send-btn active", onclick: on_cancel, "Stop" }
                        } else {
                            button {
                                class: if draft.read().trim().is_empty() { "send-btn" } else { "send-btn active" },
                                disabled: draft.read().trim().is_empty(),
                                onclick: move |_| {
                                    let prompt = draft.read().trim().to_string();
                                    if prompt.is_empty() {
                                        return;
                                    }
                                    draft.set(String::new());
                                    start_or_route_prompt(
                                        client.read().clone(),
                                        state,
                                        prompt,
                                        pending_attachments.write().drain(..).collect(),
                                        clean_signal_string(&model),
                                        clean_signal_string(&workspace),
                                        model_provider.read().clone(),
                                        clean_signal_string(&profile),
                                    );
                                },
                                "↑"
                            }
                        }
                    }
                }

                if *workspace_drawer_open.read() {
                    aside { class: "hermes-workspace-drawer",
                        div { class: "drawer-header",
                            div {
                                strong { "Workspace" }
                                span { class: "drawer-subtitle", "Used for new Hermes turns and sessions" }
                            }
                            button {
                                class: "icon-btn",
                                title: "Close workspace drawer",
                                onclick: move |_| workspace_drawer_open.set(false),
                                "×"
                            }
                        }
                        input {
                            class: "hermes-control-input",
                            placeholder: "/home/you/project",
                            value: "{workspace}",
                            oninput: move |e| workspace.set(e.value()),
                        }
                        div { class: "workspace-option-list",
                            if workspace_options.read().is_empty() {
                                div { class: "workspace-empty", "No saved workspaces returned by Hermes. Enter a path above." }
                            } else {
                                for option in workspace_options.read().iter() {
                                    {
                                        let option_value = option.value.clone();
                                        let selected = workspace.read().as_str() == option.value.as_str();
                                        rsx! {
                                            button {
                                                key: "{option.value}",
                                                class: if selected { "workspace-option selected" } else { "workspace-option" },
                                                onclick: move |_| {
                                                    workspace.set(option_value.clone());
                                                    workspace_drawer_open.set(false);
                                                },
                                                span { class: "workspace-option-label", "{option.label}" }
                                                if let Some(detail) = option.detail.as_ref() {
                                                    span { class: "workspace-option-detail", "{detail}" }
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
    }
}

#[component]
fn WebUiMessageView(message: WebUiMessage) -> Element {
    let is_user = message.role == "user";
    let class = if is_user {
        "hermes-bubble user"
    } else if message.error {
        "hermes-bubble assistant error"
    } else {
        "hermes-bubble assistant"
    };
    let content = message.content_text();
    let html = if !is_user {
        let parser = pulldown_cmark::Parser::new(&content);
        let mut html = String::new();
        pulldown_cmark::html::push_html(&mut html, parser);
        Some(html)
    } else {
        None
    };

    let tools = if !message.tool_calls.is_empty() {
        message.tool_calls.clone()
    } else {
        message.partial_tool_calls.clone()
    };

    rsx! {
        div { class,
            div { class: "bubble-role", if is_user { "You" } else { "Hermes" } }
            if let Some(reasoning) = message.reasoning.as_ref() {
                details { class: "thinking-card",
                    summary { "Thinking" }
                    pre { "{reasoning}" }
                }
            }
            if let Some(html) = html {
                div { class: "bubble-content markdown-body", dangerous_inner_html: "{html}" }
            } else {
                div { class: "bubble-content", "{content}" }
            }
            for tool in tools.iter() {
                div { class: if tool.done { "tool-card done" } else { "tool-card running" },
                    div { class: "tool-title", "{tool.name}" }
                    if !tool.preview.is_empty() {
                        div { class: "tool-preview", "{tool.preview}" }
                    }
                    if !tool.args.is_null() {
                        pre { class: "tool-args", "{tool.args}" }
                    }
                }
            }
            if let Some(details) = message.provider_details.as_ref() {
                details { class: "provider-details",
                    summary { "{message.provider_details_label.as_deref().unwrap_or(\"Details\")}" }
                    pre { "{details}" }
                }
            }
        }
    }
}

#[component]
fn ApprovalCard(payload: Value, on_choice: EventHandler<String>) -> Element {
    let description = payload
        .get("description")
        .or_else(|| payload.get("command"))
        .and_then(Value::as_str)
        .unwrap_or("Tool approval needed")
        .to_string();
    rsx! {
        div { class: "runtime-card approval-card",
            strong { "Approval required" }
            p { "{description}" }
            div { class: "runtime-actions",
                button { class: "btn-secondary", onclick: move |_| on_choice.call("once".to_string()), "Allow once" }
                button { class: "btn-secondary", onclick: move |_| on_choice.call("session".to_string()), "Allow session" }
                button { class: "btn-secondary", onclick: move |_| on_choice.call("always".to_string()), "Always allow" }
                button { class: "btn-danger", onclick: move |_| on_choice.call("deny".to_string()), "Deny" }
            }
        }
    }
}

#[component]
fn ClarifyCard(
    payload: Value,
    answer: String,
    on_input: EventHandler<String>,
    on_send: EventHandler<()>,
) -> Element {
    let question = payload
        .get("question")
        .or_else(|| payload.get("message"))
        .and_then(Value::as_str)
        .unwrap_or("Clarification needed")
        .to_string();
    rsx! {
        div { class: "runtime-card clarify-card",
            strong { "Clarification needed" }
            p { "{question}" }
            input {
                class: "hermes-control-input",
                placeholder: "Type your response...",
                value: "{answer}",
                oninput: move |e| on_input.call(e.value()),
            }
            button {
                class: "btn-primary",
                disabled: answer.trim().is_empty(),
                onclick: move |_| on_send.call(()),
                "Send"
            }
        }
    }
}

async fn load_webui_home(
    client: HermesWebUiClient,
    mut state: Signal<WebUiChatState>,
    mut workspace: Signal<String>,
    mut model: Signal<String>,
    mut model_provider: Signal<Option<String>>,
    mut profile: Signal<String>,
    mut workspace_options: Signal<Vec<PickerOption>>,
    mut model_options: Signal<Vec<PickerOption>>,
    mut profile_options: Signal<Vec<PickerOption>>,
    mut reasoning_effort: Signal<String>,
) -> Result<(), String> {
    let sessions = client.list_sessions().await.map_err(|e| e.to_string())?;
    let selected = state
        .read()
        .selected_session_id
        .clone()
        .or_else(|| sessions.first().map(|session| session.session_id.clone()));
    state.write().apply_session_list(sessions);

    if let Ok(workspaces) = client.list_workspaces().await {
        let options = workspace_options_from_response(&workspaces);
        if workspace.read().trim().is_empty() {
            if let Some(last) = workspaces.last {
                workspace.set(last);
            }
        }
        workspace_options.set(options);
    }

    if let Ok(models) = client.list_models().await {
        let default_model = models.default_model.clone();
        let active_provider = models.active_provider.clone();
        let options = model_options_from_response(&models);
        if model.read().trim().is_empty() {
            if let Some(default_model) = default_model {
                model.set(default_model);
            }
        }
        if model_provider.read().is_none() {
            model_provider.set(active_provider);
        }
        model_options.set(options);
    }

    if let Ok(profiles) = client.list_profiles().await {
        profile_options.set(profile_options_from_response(&profiles));
        if profile.read().trim().is_empty() || profile.read().as_str() == "default" {
            if let Some(active) = profiles.active {
                profile.set(active);
            }
        }
    }

    if let Ok(reasoning) = client.reasoning_status().await {
        reasoning_effort.set(
            reasoning
                .reasoning_effort
                .filter(|value| !value.trim().is_empty())
                .unwrap_or_else(|| "none".to_string()),
        );
    } else if reasoning_effort.read().trim().is_empty() {
        reasoning_effort.set("none".to_string());
    }

    if let Some(sid) = selected {
        let session = client.get_session(&sid).await.map_err(|e| e.to_string())?;
        apply_session_controls(&session, workspace, model, model_provider, profile);
        state.write().apply_session_detail(session);
    }

    Ok(())
}

fn start_or_route_prompt(
    client: HermesWebUiClient,
    mut state: Signal<WebUiChatState>,
    prompt: String,
    attachments: Vec<WebUiAttachment>,
    model: Option<String>,
    workspace: Option<String>,
    model_provider: Option<String>,
    profile: Option<String>,
) {
    let is_busy = { state.read().is_busy() };
    if is_busy {
        let busy_mode = { state.read().busy_input_mode };
        match busy_mode {
            HermesBusyInputMode::Queue => {
                state.write().queued_prompts.push(QueuedWebUiPrompt {
                    text: prompt,
                    attachments,
                    model,
                    model_provider,
                    profile,
                });
            }
            HermesBusyInputMode::Steer => {
                let sid = { state.read().selected_session_id.clone() };
                if let Some(sid) = sid {
                    spawn(async move {
                        match client.steer(&sid, &prompt).await {
                            Ok(payload)
                                if payload.get("accepted").and_then(Value::as_bool)
                                    == Some(true) => {}
                            Ok(_) => state.write().queued_prompts.push(QueuedWebUiPrompt {
                                text: prompt,
                                attachments,
                                model,
                                model_provider,
                                profile,
                            }),
                            Err(err) => state.write().last_error = Some(err.to_string()),
                        }
                    });
                }
            }
            HermesBusyInputMode::Interrupt => {
                let stream_id = { state.read().active_stream_id.clone() };
                spawn(async move {
                    if let Some(stream_id) = stream_id {
                        let _ = client.cancel_stream(&stream_id).await;
                        state.write().clear_runtime();
                    }
                    start_prompt(
                        client,
                        state,
                        prompt,
                        attachments,
                        model,
                        workspace,
                        model_provider,
                        profile,
                    )
                    .await;
                });
            }
        }
        return;
    }

    spawn(async move {
        start_prompt(
            client,
            state,
            prompt,
            attachments,
            model,
            workspace,
            model_provider,
            profile,
        )
        .await;
    });
}

async fn start_prompt(
    client: HermesWebUiClient,
    mut state: Signal<WebUiChatState>,
    prompt: String,
    attachments: Vec<WebUiAttachment>,
    model: Option<String>,
    workspace: Option<String>,
    model_provider: Option<String>,
    profile: Option<String>,
) {
    let session_id = match ensure_session(
        client.clone(),
        state,
        workspace.clone(),
        model.clone(),
        model_provider.clone(),
        profile.clone(),
    )
    .await
    {
        Ok(session_id) => session_id,
        Err(err) => {
            state.write().last_error = Some(err);
            return;
        }
    };

    let start = match client
        .start_chat(&ChatStartRequest {
            session_id: session_id.clone(),
            message: prompt.clone(),
            model,
            workspace,
            model_provider,
            profile,
            attachments,
        })
        .await
    {
        Ok(start) => start,
        Err(err) => {
            state.write().last_error = Some(err.to_string());
            return;
        }
    };

    if let Some(title) = start.title.as_deref() {
        state.write().apply_stream_event(WebUiStreamEvent::Title {
            session_id: Some(session_id.clone()),
            title: title.to_string(),
        });
    }
    state
        .write()
        .begin_optimistic_turn(&session_id, &prompt, &start.stream_id);

    let (tx, _) = broadcast::channel(256);
    let mut rx = tx.subscribe();
    let stream_client = client.clone();
    let stream_id = start.stream_id.clone();
    spawn(async move {
        if let Err(err) = stream_client.stream_chat(&stream_id, &tx).await {
            let _ = tx.send(WebUiStreamEvent::Error(Value::String(err.to_string())));
        }
    });

    while let Ok(event) = rx.recv().await {
        let terminal = matches!(
            event,
            WebUiStreamEvent::Done { .. }
                | WebUiStreamEvent::AppError(_)
                | WebUiStreamEvent::Error(_)
                | WebUiStreamEvent::Cancel(_)
        );
        state.write().apply_stream_event(event);
        if terminal {
            break;
        }
    }

    let selected_session_id = { state.read().selected_session_id.clone() };
    if let Some(session_id) = selected_session_id {
        if let Ok(session) = client.get_session(&session_id).await {
            state.write().apply_session_detail(session);
        }
    }

    drain_queued_prompt(client, state);
}

fn drain_queued_prompt(client: HermesWebUiClient, mut state: Signal<WebUiChatState>) {
    if state.read().is_busy() {
        return;
    }
    let next = {
        let mut writable = state.write();
        if writable.queued_prompts.is_empty() {
            None
        } else {
            Some(writable.queued_prompts.remove(0))
        }
    };
    if let Some(next) = next {
        spawn(async move {
            start_prompt(
                client,
                state,
                next.text,
                next.attachments,
                next.model,
                None,
                next.model_provider,
                next.profile,
            )
            .await;
        });
    }
}

async fn ensure_session(
    client: HermesWebUiClient,
    mut state: Signal<WebUiChatState>,
    workspace: Option<String>,
    model: Option<String>,
    model_provider: Option<String>,
    profile: Option<String>,
) -> Result<String, String> {
    if let Some(session_id) = { state.read().selected_session_id.clone() } {
        return Ok(session_id);
    }

    let session = client
        .create_session(&NewSessionRequest {
            workspace,
            model,
            model_provider,
            profile,
        })
        .await
        .map_err(|err| err.to_string())?;
    let id = session.summary.session_id.clone();
    state.write().apply_session_detail(session);
    Ok(id)
}

fn clean_signal_string(signal: &Signal<String>) -> Option<String> {
    let value = signal.read().trim().to_string();
    if value.is_empty() {
        None
    } else {
        Some(value)
    }
}

fn apply_session_controls(
    session: &WebUiSession,
    mut workspace: Signal<String>,
    mut model: Signal<String>,
    mut model_provider: Signal<Option<String>>,
    mut profile: Signal<String>,
) {
    if let Some(value) = session.summary.workspace.as_ref() {
        workspace.set(value.clone());
    }
    if let Some(value) = session.summary.model.as_ref() {
        model.set(value.clone());
    }
    model_provider.set(session.summary.model_provider.clone());
    if let Some(value) = session.summary.profile.as_ref() {
        profile.set(value.clone());
    }
}

fn workspace_options_from_response(response: &WebUiWorkspaceList) -> Vec<PickerOption> {
    let mut seen = HashSet::new();
    let mut options = Vec::new();
    for value in response.workspaces.iter() {
        if let Some(path) = value_string(value, &["path", "workspace", "value", "id"]) {
            if seen.insert(path.clone()) {
                let label = value_string(value, &["name", "label"])
                    .filter(|label| !label.trim().is_empty())
                    .unwrap_or_else(|| compact_label(&path, 36));
                options.push(PickerOption {
                    value: path.clone(),
                    label,
                    detail: Some(path),
                    provider: None,
                });
            }
        } else if let Some(path) = value.as_str() {
            let path = path.to_string();
            if seen.insert(path.clone()) {
                options.push(PickerOption {
                    value: path.clone(),
                    label: compact_label(&path, 36),
                    detail: Some(path),
                    provider: None,
                });
            }
        }
    }
    if let Some(last) = response.last.as_ref() {
        if seen.insert(last.clone()) {
            options.insert(
                0,
                PickerOption {
                    value: last.clone(),
                    label: compact_label(last, 36),
                    detail: Some("Last used".to_string()),
                    provider: None,
                },
            );
        }
    }
    options
}

fn model_options_from_response(response: &WebUiModelsResponse) -> Vec<PickerOption> {
    let mut seen = HashSet::new();
    let mut options = Vec::new();

    for group in response.groups.iter() {
        let provider = value_string(group, &["provider", "id", "name"]);
        if let Some(models) = group.get("models").and_then(Value::as_array) {
            for model in models {
                push_model_option(&mut options, &mut seen, model, provider.clone());
            }
        }
    }
    for model in response.models.iter().chain(response.data.iter()) {
        push_model_option(
            &mut options,
            &mut seen,
            model,
            response.active_provider.clone(),
        );
    }
    if options.is_empty() {
        if let Some(default_model) = response.default_model.as_ref() {
            options.push(PickerOption {
                value: default_model.clone(),
                label: compact_label(default_model, 36),
                detail: response.active_provider.clone(),
                provider: response.active_provider.clone(),
            });
        }
    }
    options
}

fn push_model_option(
    options: &mut Vec<PickerOption>,
    seen: &mut HashSet<String>,
    value: &Value,
    provider: Option<String>,
) {
    let model_id = if let Some(id) = value_string(value, &["id", "model", "value", "name"]) {
        id
    } else if let Some(id) = value.as_str() {
        id.to_string()
    } else {
        return;
    };
    let key = format!("{}::{model_id}", provider.clone().unwrap_or_default());
    if !seen.insert(key) {
        return;
    }
    let label = value_string(value, &["label", "name", "display_name"])
        .filter(|label| !label.trim().is_empty())
        .unwrap_or_else(|| compact_label(&model_id, 40));
    let detail = provider
        .clone()
        .or_else(|| value_string(value, &["provider", "provider_id"]));
    options.push(PickerOption {
        value: model_id,
        label,
        detail,
        provider,
    });
}

fn profile_options_from_response(response: &WebUiProfilesResponse) -> Vec<PickerOption> {
    let mut seen = HashSet::new();
    let mut options = Vec::new();
    for value in response.profiles.iter() {
        let Some(id) = value_string(value, &["id", "name", "value"])
            .or_else(|| value.as_str().map(ToString::to_string))
        else {
            continue;
        };
        if !seen.insert(id.clone()) {
            continue;
        }
        let label = value_string(value, &["label", "display_name"])
            .filter(|label| !label.trim().is_empty())
            .unwrap_or_else(|| id.clone());
        options.push(PickerOption {
            value: id,
            label,
            detail: None,
            provider: None,
        });
    }
    if options.is_empty() {
        options.push(PickerOption {
            value: "default".to_string(),
            label: "default".to_string(),
            detail: None,
            provider: None,
        });
    }
    options
}

fn value_string(value: &Value, keys: &[&str]) -> Option<String> {
    if let Some(text) = value.as_str() {
        return Some(text.to_string());
    }
    for key in keys {
        if let Some(text) = value.get(key).and_then(Value::as_str) {
            return Some(text.to_string());
        }
    }
    None
}

fn selected_option_label(options: &[PickerOption], value: &str) -> Option<String> {
    options
        .iter()
        .find(|option| option.value == value)
        .map(|option| option.label.clone())
}

fn compact_label(value: &str, max_chars: usize) -> String {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return "Default".to_string();
    }
    if trimmed.chars().count() <= max_chars {
        return trimmed.to_string();
    }
    let tail = trimmed
        .chars()
        .rev()
        .take(max_chars.saturating_sub(1))
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect::<String>();
    format!("…{tail}")
}

fn reasoning_label(value: &str) -> String {
    match value {
        "" | "none" => "None".to_string(),
        "xhigh" => "Extra High".to_string(),
        other => {
            let mut chars = other.chars();
            match chars.next() {
                Some(first) => format!("{}{}", first.to_uppercase(), chars.as_str()),
                None => "None".to_string(),
            }
        }
    }
}

fn busy_mode_value(mode: HermesBusyInputMode) -> &'static str {
    match mode {
        HermesBusyInputMode::Queue => "queue",
        HermesBusyInputMode::Interrupt => "interrupt",
        HermesBusyInputMode::Steer => "steer",
    }
}
