use serde_json::Value;

use crate::webui::{
    WebUiAttachment, WebUiMessage, WebUiSession, WebUiSessionSummary, WebUiStreamEvent,
    WebUiToolCall,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HermesBusyInputMode {
    Queue,
    Interrupt,
    Steer,
}

#[derive(Debug, Clone, PartialEq)]
pub struct QueuedWebUiPrompt {
    pub text: String,
    pub attachments: Vec<WebUiAttachment>,
    pub model: Option<String>,
    pub model_provider: Option<String>,
    pub profile: Option<String>,
}

#[derive(Debug, Clone)]
pub struct WebUiChatState {
    pub sessions: Vec<WebUiSessionSummary>,
    pub selected_session_id: Option<String>,
    pub selected_session: Option<WebUiSession>,
    pub active_stream_id: Option<String>,
    pub live_user_text: Option<String>,
    pub live_assistant_text: String,
    pub live_reasoning_text: String,
    pub live_tool_calls: Vec<WebUiToolCall>,
    pub pending_approval: Option<Value>,
    pub pending_clarify: Option<Value>,
    pub compression_status: Option<String>,
    pub last_usage: Option<Value>,
    pub last_error: Option<String>,
    pub queued_prompts: Vec<QueuedWebUiPrompt>,
    pub busy_input_mode: HermesBusyInputMode,
}

impl Default for WebUiChatState {
    fn default() -> Self {
        Self {
            sessions: Vec::new(),
            selected_session_id: None,
            selected_session: None,
            active_stream_id: None,
            live_user_text: None,
            live_assistant_text: String::new(),
            live_reasoning_text: String::new(),
            live_tool_calls: Vec::new(),
            pending_approval: None,
            pending_clarify: None,
            compression_status: None,
            last_usage: None,
            last_error: None,
            queued_prompts: Vec::new(),
            busy_input_mode: HermesBusyInputMode::Queue,
        }
    }
}

impl WebUiChatState {
    pub fn is_busy(&self) -> bool {
        self.active_stream_id.is_some()
    }

    pub fn apply_session_list(&mut self, sessions: Vec<WebUiSessionSummary>) {
        self.sessions = sessions;
        if self.selected_session_id.is_none() {
            self.selected_session_id = self.sessions.first().map(|s| s.session_id.clone());
        }
    }

    pub fn apply_session_detail(&mut self, session: WebUiSession) {
        let session_id = session.summary.session_id.clone();
        self.selected_session_id = Some(session_id.clone());
        self.upsert_summary(session.summary.clone());
        self.active_stream_id = session.summary.active_stream_id.clone();
        self.selected_session = Some(session);
        self.live_user_text = None;
        self.live_assistant_text.clear();
        self.live_reasoning_text.clear();
        self.live_tool_calls.clear();
        self.pending_approval = None;
        self.pending_clarify = None;
        self.compression_status = None;
        self.last_error = None;
    }

    pub fn begin_optimistic_turn(&mut self, session_id: &str, prompt: &str, stream_id: &str) {
        self.selected_session_id = Some(session_id.to_string());
        self.active_stream_id = Some(stream_id.to_string());
        self.live_user_text = Some(prompt.to_string());
        self.live_assistant_text.clear();
        self.live_reasoning_text.clear();
        self.live_tool_calls.clear();
        self.pending_approval = None;
        self.pending_clarify = None;
        self.compression_status = None;
        self.last_error = None;

        if let Some(session) = self.selected_session.as_mut() {
            session.summary.active_stream_id = Some(stream_id.to_string());
            session.summary.is_streaming = true;
            if session.messages.is_empty() {
                session.summary.title = prompt.chars().take(64).collect();
            }
        }

        if let Some(summary) = self
            .sessions
            .iter_mut()
            .find(|summary| summary.session_id == session_id)
        {
            summary.active_stream_id = Some(stream_id.to_string());
            summary.is_streaming = true;
            if summary.title.is_empty() || summary.title == "Untitled" {
                summary.title = prompt.chars().take(64).collect();
            }
        }
    }

    pub fn apply_stream_event(&mut self, event: WebUiStreamEvent) {
        match event {
            WebUiStreamEvent::Token { text } => {
                self.live_assistant_text.push_str(&text);
            }
            WebUiStreamEvent::InterimAssistant { text, .. } => {
                if !text.trim().is_empty() {
                    if !self.live_assistant_text.is_empty() {
                        self.live_assistant_text.push_str("\n\n");
                    }
                    self.live_assistant_text.push_str(&text);
                }
            }
            WebUiStreamEvent::Reasoning { text } => {
                self.live_reasoning_text.push_str(&text);
            }
            WebUiStreamEvent::Tool(tool) => {
                self.live_tool_calls.push(tool);
            }
            WebUiStreamEvent::ToolComplete(tool) => {
                self.complete_tool(tool);
            }
            WebUiStreamEvent::Approval(payload) => {
                self.pending_approval = Some(payload);
            }
            WebUiStreamEvent::Clarify(payload) => {
                self.pending_clarify = Some(payload);
            }
            WebUiStreamEvent::Title { session_id, title } => {
                let sid = session_id.or_else(|| self.selected_session_id.clone());
                if let Some(sid) = sid {
                    self.set_title(&sid, &title);
                }
            }
            WebUiStreamEvent::Goal(payload) => {
                self.compression_status = event_message(&payload).or_else(|| {
                    payload
                        .get("state")
                        .and_then(Value::as_str)
                        .map(ToString::to_string)
                });
            }
            WebUiStreamEvent::GoalContinue(payload) => {
                if let Some(text) = payload
                    .get("continuation_prompt")
                    .or_else(|| payload.get("text"))
                    .and_then(Value::as_str)
                    .filter(|text| !text.trim().is_empty())
                {
                    self.queued_prompts.push(QueuedWebUiPrompt {
                        text: text.to_string(),
                        attachments: Vec::new(),
                        model: self
                            .selected_session
                            .as_ref()
                            .and_then(|s| s.summary.model.clone()),
                        model_provider: self
                            .selected_session
                            .as_ref()
                            .and_then(|s| s.summary.model_provider.clone()),
                        profile: self
                            .selected_session
                            .as_ref()
                            .and_then(|s| s.summary.profile.clone()),
                    });
                }
            }
            WebUiStreamEvent::Done { session, usage } => {
                self.last_usage = usage;
                self.apply_session_detail(session);
                self.active_stream_id = None;
            }
            WebUiStreamEvent::StreamEnd { .. } => {}
            WebUiStreamEvent::PendingSteerLeftover { text, .. } => {
                if !text.trim().is_empty() {
                    self.queued_prompts.push(QueuedWebUiPrompt {
                        text,
                        attachments: Vec::new(),
                        model: None,
                        model_provider: None,
                        profile: None,
                    });
                }
            }
            WebUiStreamEvent::Compressing(payload) => {
                self.compression_status =
                    event_message(&payload).or_else(|| Some("Compressing context...".to_string()));
            }
            WebUiStreamEvent::Compressed(payload) => {
                self.compression_status =
                    event_message(&payload).or_else(|| Some("Context compressed".to_string()));
            }
            WebUiStreamEvent::Metering(payload) => {
                self.last_usage = payload.get("usage").cloned().or(Some(payload));
            }
            WebUiStreamEvent::AppError(payload)
            | WebUiStreamEvent::Error(payload)
            | WebUiStreamEvent::Cancel(payload) => {
                self.last_error = event_message(&payload).or_else(|| Some("Stream ended".into()));
                self.clear_runtime();
            }
            WebUiStreamEvent::Warning(payload) => {
                self.last_error = event_message(&payload);
            }
            WebUiStreamEvent::TitleStatus(_) | WebUiStreamEvent::Unknown { .. } => {}
        }
    }

    pub fn clear_runtime(&mut self) {
        self.active_stream_id = None;
        self.live_user_text = None;
        self.live_assistant_text.clear();
        self.live_reasoning_text.clear();
        self.live_tool_calls.clear();
        self.pending_approval = None;
        self.pending_clarify = None;
        if let Some(session) = self.selected_session.as_mut() {
            session.summary.active_stream_id = None;
            session.summary.is_streaming = false;
        }
        if let Some(sid) = self.selected_session_id.as_ref() {
            if let Some(summary) = self
                .sessions
                .iter_mut()
                .find(|summary| &summary.session_id == sid)
            {
                summary.active_stream_id = None;
                summary.is_streaming = false;
            }
        }
    }

    pub fn current_messages_with_live_turn(&self) -> Vec<WebUiMessage> {
        let mut messages = self
            .selected_session
            .as_ref()
            .map(|s| s.messages.clone())
            .unwrap_or_default();

        if let Some(text) = self.live_user_text.as_ref() {
            messages.push(WebUiMessage {
                role: "user".to_string(),
                content: Value::String(text.clone()),
                reasoning: None,
                timestamp: None,
                ts: None,
                attachments: Vec::new(),
                tool_calls: Vec::new(),
                partial_tool_calls: Vec::new(),
                error: false,
                provider_details: None,
                provider_details_label: None,
            });
        }

        if self.is_busy()
            || !self.live_assistant_text.is_empty()
            || !self.live_reasoning_text.is_empty()
            || !self.live_tool_calls.is_empty()
        {
            messages.push(WebUiMessage {
                role: "assistant".to_string(),
                content: Value::String(self.live_assistant_text.clone()),
                reasoning: if self.live_reasoning_text.is_empty() {
                    None
                } else {
                    Some(self.live_reasoning_text.clone())
                },
                timestamp: None,
                ts: None,
                attachments: Vec::new(),
                tool_calls: self.live_tool_calls.clone(),
                partial_tool_calls: Vec::new(),
                error: false,
                provider_details: None,
                provider_details_label: None,
            });
        }

        messages
    }

    fn upsert_summary(&mut self, summary: WebUiSessionSummary) {
        if let Some(existing) = self
            .sessions
            .iter_mut()
            .find(|item| item.session_id == summary.session_id)
        {
            *existing = summary;
        } else {
            self.sessions.insert(0, summary);
        }
    }

    fn set_title(&mut self, session_id: &str, title: &str) {
        if let Some(session) = self.selected_session.as_mut() {
            if session.summary.session_id == session_id {
                session.summary.title = title.to_string();
            }
        }
        if let Some(summary) = self
            .sessions
            .iter_mut()
            .find(|summary| summary.session_id == session_id)
        {
            summary.title = title.to_string();
        }
    }

    fn complete_tool(&mut self, completed: WebUiToolCall) {
        let mut matched = false;
        for existing in self.live_tool_calls.iter_mut().rev() {
            let name_matches = completed.name.is_empty() || existing.name == completed.name;
            let tid_matches = completed
                .tid
                .as_ref()
                .zip(existing.tid.as_ref())
                .map(|(a, b)| a == b)
                .unwrap_or(false);
            if !existing.done && (name_matches || tid_matches) {
                *existing = completed.clone();
                existing.done = true;
                matched = true;
                break;
            }
        }
        if !matched {
            let mut completed = completed;
            completed.done = true;
            self.live_tool_calls.push(completed);
        }
    }
}

fn event_message(value: &Value) -> Option<String> {
    value
        .get("message")
        .or_else(|| value.get("error"))
        .or_else(|| value.get("description"))
        .and_then(Value::as_str)
        .filter(|text| !text.trim().is_empty())
        .map(ToString::to_string)
}

#[cfg(test)]
mod tests {
    use crate::webui::{
        WebUiMessage, WebUiSession, WebUiSessionSummary, WebUiStreamEvent, WebUiToolCall,
    };
    use crate::webui_state::{HermesBusyInputMode, WebUiChatState};
    use serde_json::json;

    fn summary(id: &str, title: &str) -> WebUiSessionSummary {
        WebUiSessionSummary {
            session_id: id.to_string(),
            title: title.to_string(),
            workspace: Some("/tmp/project".to_string()),
            model: Some("claude-opus".to_string()),
            model_provider: None,
            message_count: 0,
            created_at: Some(1.0),
            updated_at: Some(1.0),
            last_message_at: Some(1.0),
            pinned: false,
            archived: false,
            profile: Some("default".to_string()),
            active_stream_id: None,
            is_streaming: false,
            read_only: false,
        }
    }

    fn session(id: &str, title: &str, messages: Vec<WebUiMessage>) -> WebUiSession {
        WebUiSession {
            summary: summary(id, title),
            messages,
            tool_calls: Vec::new(),
            pending_user_message: None,
            pending_attachments: Vec::new(),
            context_length: None,
            threshold_tokens: None,
            input_tokens: None,
            output_tokens: None,
            estimated_cost: None,
        }
    }

    #[test]
    fn token_reasoning_tool_and_done_events_update_transcript_state() {
        let mut state = WebUiChatState::default();
        state.apply_session_list(vec![summary("s1", "First")]);
        state.apply_session_detail(session("s1", "First", Vec::new()));
        state.begin_optimistic_turn("s1", "hello", "run1");

        state.apply_stream_event(WebUiStreamEvent::Reasoning {
            text: "thinking".to_string(),
        });
        state.apply_stream_event(WebUiStreamEvent::Token {
            text: "hi".to_string(),
        });
        state.apply_stream_event(WebUiStreamEvent::Tool(WebUiToolCall {
            name: "Read".to_string(),
            preview: "README.md".to_string(),
            args: json!({"path":"README.md"}),
            snippet: None,
            done: false,
            is_error: false,
            duration: None,
            tid: Some("t1".to_string()),
        }));

        assert_eq!(state.live_assistant_text, "hi");
        assert_eq!(state.live_reasoning_text, "thinking");
        assert_eq!(state.live_tool_calls.len(), 1);
        assert!(state.is_busy());

        state.apply_stream_event(WebUiStreamEvent::Done {
            session: session(
                "s1",
                "First",
                vec![
                    WebUiMessage {
                        role: "user".to_string(),
                        content: json!("hello"),
                        reasoning: None,
                        timestamp: None,
                        ts: Some(1.0),
                        attachments: Vec::new(),
                        tool_calls: Vec::new(),
                        partial_tool_calls: Vec::new(),
                        error: false,
                        provider_details: None,
                        provider_details_label: None,
                    },
                    WebUiMessage {
                        role: "assistant".to_string(),
                        content: json!("hi"),
                        reasoning: Some("thinking".to_string()),
                        timestamp: None,
                        ts: Some(2.0),
                        attachments: Vec::new(),
                        tool_calls: Vec::new(),
                        partial_tool_calls: Vec::new(),
                        error: false,
                        provider_details: None,
                        provider_details_label: None,
                    },
                ],
            ),
            usage: Some(json!({"input_tokens": 10})),
        });

        assert!(!state.is_busy());
        assert_eq!(state.selected_session.as_ref().unwrap().messages.len(), 2);
        assert_eq!(state.live_assistant_text, "");
        assert_eq!(state.last_usage.unwrap()["input_tokens"], 10);
    }

    #[test]
    fn approval_clarify_and_cancel_events_are_tracked() {
        let mut state = WebUiChatState::default();
        state.apply_session_detail(session("s1", "First", Vec::new()));
        state.begin_optimistic_turn("s1", "run dangerous command", "run1");

        state.apply_stream_event(WebUiStreamEvent::Approval(json!({
            "approval_id": "a1",
            "description": "dangerous command"
        })));
        state.apply_stream_event(WebUiStreamEvent::Clarify(json!({
            "question": "Which file?"
        })));

        assert_eq!(
            state.pending_approval.as_ref().unwrap()["approval_id"],
            "a1"
        );
        assert_eq!(
            state.pending_clarify.as_ref().unwrap()["question"],
            "Which file?"
        );

        state.apply_stream_event(WebUiStreamEvent::Cancel(json!({"message":"cancelled"})));
        assert!(!state.is_busy());
        assert!(state.pending_approval.is_none());
        assert!(state.pending_clarify.is_none());
        assert_eq!(state.last_error.as_deref(), Some("cancelled"));
    }

    #[test]
    fn busy_mode_defaults_to_queue() {
        let state = WebUiChatState::default();
        assert_eq!(state.busy_input_mode, HermesBusyInputMode::Queue);
    }
}
