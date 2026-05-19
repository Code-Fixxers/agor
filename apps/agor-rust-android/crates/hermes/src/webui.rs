use futures_util::StreamExt;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::sync::broadcast;

use crate::client::HermesError;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WebUiSessionsResponse {
    #[serde(default)]
    pub sessions: Vec<WebUiSessionSummary>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WebUiSessionResponse {
    pub session: WebUiSession,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WebUiSessionSummary {
    pub session_id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub workspace: Option<String>,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub model_provider: Option<String>,
    #[serde(default)]
    pub message_count: usize,
    #[serde(default)]
    pub created_at: Option<f64>,
    #[serde(default)]
    pub updated_at: Option<f64>,
    #[serde(default)]
    pub last_message_at: Option<f64>,
    #[serde(default)]
    pub pinned: bool,
    #[serde(default)]
    pub archived: bool,
    #[serde(default)]
    pub profile: Option<String>,
    #[serde(default)]
    pub active_stream_id: Option<String>,
    #[serde(default)]
    pub is_streaming: bool,
    #[serde(default)]
    pub read_only: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WebUiSession {
    #[serde(flatten)]
    pub summary: WebUiSessionSummary,
    #[serde(default)]
    pub messages: Vec<WebUiMessage>,
    #[serde(default)]
    pub tool_calls: Vec<WebUiToolCall>,
    #[serde(default)]
    pub pending_user_message: Option<String>,
    #[serde(default)]
    pub pending_attachments: Vec<WebUiAttachment>,
    #[serde(default)]
    pub context_length: Option<u64>,
    #[serde(default)]
    pub threshold_tokens: Option<u64>,
    #[serde(default)]
    pub input_tokens: Option<u64>,
    #[serde(default)]
    pub output_tokens: Option<u64>,
    #[serde(default)]
    pub estimated_cost: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WebUiMessage {
    #[serde(default)]
    pub role: String,
    #[serde(default)]
    pub content: Value,
    #[serde(default)]
    pub reasoning: Option<String>,
    #[serde(default)]
    pub timestamp: Option<f64>,
    #[serde(default, rename = "_ts")]
    pub ts: Option<f64>,
    #[serde(default)]
    pub attachments: Vec<WebUiAttachment>,
    #[serde(default)]
    pub tool_calls: Vec<WebUiToolCall>,
    #[serde(default, rename = "_partial_tool_calls")]
    pub partial_tool_calls: Vec<WebUiToolCall>,
    #[serde(default, rename = "_error")]
    pub error: bool,
    #[serde(default)]
    pub provider_details: Option<String>,
    #[serde(default)]
    pub provider_details_label: Option<String>,
}

impl WebUiMessage {
    pub fn content_text(&self) -> String {
        match &self.content {
            Value::String(text) => text.clone(),
            Value::Array(parts) => parts
                .iter()
                .filter_map(|part| {
                    if let Some(text) = part.get("text").and_then(Value::as_str) {
                        Some(text.to_string())
                    } else {
                        part.as_str().map(ToString::to_string)
                    }
                })
                .collect::<Vec<_>>()
                .join(""),
            Value::Null => String::new(),
            other => other.to_string(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WebUiToolCall {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub preview: String,
    #[serde(default)]
    pub args: Value,
    #[serde(default)]
    pub snippet: Option<String>,
    #[serde(default)]
    pub done: bool,
    #[serde(default)]
    pub is_error: bool,
    #[serde(default)]
    pub duration: Option<f64>,
    #[serde(default)]
    pub tid: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WebUiAttachment {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub path: String,
    #[serde(default)]
    pub mime: String,
    #[serde(default)]
    pub size: Option<u64>,
    #[serde(default)]
    pub is_image: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WebUiChatStartResponse {
    pub stream_id: String,
    #[serde(default)]
    pub session_id: Option<String>,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub effective_model: Option<String>,
    #[serde(default)]
    pub effective_model_provider: Option<String>,
    #[serde(default)]
    pub pending_started_at: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WebUiStreamStatus {
    #[serde(default)]
    pub active: bool,
    #[serde(default)]
    pub stream_id: String,
    #[serde(default)]
    pub replay_available: bool,
    #[serde(default)]
    pub journal: Option<Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WebUiWorkspaceList {
    #[serde(default)]
    pub workspaces: Vec<Value>,
    #[serde(default)]
    pub last: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WebUiModelsResponse {
    #[serde(default)]
    pub models: Vec<Value>,
    #[serde(default)]
    pub data: Vec<Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WebUiProfilesResponse {
    #[serde(default)]
    pub profiles: Vec<Value>,
    #[serde(default)]
    pub active: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct NewSessionRequest {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub workspace: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model_provider: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub profile: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ChatStartRequest {
    pub session_id: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub workspace: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model_provider: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub profile: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty", default)]
    pub attachments: Vec<WebUiAttachment>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ApprovalResponse {
    pub session_id: String,
    pub choice: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub approval_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ClarifyResponse {
    pub session_id: String,
    pub answer: String,
}

#[derive(Clone)]
pub struct HermesWebUiClient {
    http: Client,
    base_url: String,
}

impl HermesWebUiClient {
    pub fn new(base_url: &str) -> Self {
        Self {
            http: build_http_client(),
            base_url: normalize_webui_url(base_url),
        }
    }

    pub fn base_url(&self) -> &str {
        &self.base_url
    }

    pub async fn health(&self) -> Result<(), HermesError> {
        let _: Value = self.get_json("/health").await?;
        Ok(())
    }

    pub async fn list_sessions(&self) -> Result<Vec<WebUiSessionSummary>, HermesError> {
        let response: WebUiSessionsResponse = self.get_json("/api/sessions").await?;
        Ok(response.sessions)
    }

    pub async fn get_session(&self, session_id: &str) -> Result<WebUiSession, HermesError> {
        let response: WebUiSessionResponse = self
            .get_json(&format!(
                "/api/session?session_id={}&msg_limit=200",
                encode_query(session_id)
            ))
            .await?;
        Ok(response.session)
    }

    pub async fn create_session(
        &self,
        request: &NewSessionRequest,
    ) -> Result<WebUiSession, HermesError> {
        let response: WebUiSessionResponse = self.post_json("/api/session/new", request).await?;
        Ok(response.session)
    }

    pub async fn rename_session(
        &self,
        session_id: &str,
        title: &str,
    ) -> Result<WebUiSessionSummary, HermesError> {
        #[derive(Serialize)]
        struct Body<'a> {
            session_id: &'a str,
            title: &'a str,
        }
        #[derive(Deserialize)]
        struct Response {
            session: WebUiSessionSummary,
        }
        let response: Response = self
            .post_json("/api/session/rename", &Body { session_id, title })
            .await?;
        Ok(response.session)
    }

    pub async fn delete_session(&self, session_id: &str) -> Result<(), HermesError> {
        #[derive(Serialize)]
        struct Body<'a> {
            session_id: &'a str,
        }
        let _: Value = self
            .post_json("/api/session/delete", &Body { session_id })
            .await?;
        Ok(())
    }

    pub async fn start_chat(
        &self,
        request: &ChatStartRequest,
    ) -> Result<WebUiChatStartResponse, HermesError> {
        self.post_json("/api/chat/start", request).await
    }

    pub async fn stream_status(&self, stream_id: &str) -> Result<WebUiStreamStatus, HermesError> {
        self.get_json(&format!(
            "/api/chat/stream/status?stream_id={}",
            encode_query(stream_id)
        ))
        .await
    }

    pub async fn cancel_stream(&self, stream_id: &str) -> Result<(), HermesError> {
        let _: Value = self
            .get_json(&format!(
                "/api/chat/cancel?stream_id={}",
                encode_query(stream_id)
            ))
            .await?;
        Ok(())
    }

    pub async fn steer(&self, session_id: &str, text: &str) -> Result<Value, HermesError> {
        #[derive(Serialize)]
        struct Body<'a> {
            session_id: &'a str,
            text: &'a str,
        }
        self.post_json("/api/chat/steer", &Body { session_id, text })
            .await
    }

    pub async fn respond_approval(&self, response: &ApprovalResponse) -> Result<(), HermesError> {
        let _: Value = self.post_json("/api/approval/respond", response).await?;
        Ok(())
    }

    pub async fn respond_clarify(&self, response: &ClarifyResponse) -> Result<(), HermesError> {
        let _: Value = self.post_json("/api/clarify/respond", response).await?;
        Ok(())
    }

    pub async fn list_workspaces(&self) -> Result<WebUiWorkspaceList, HermesError> {
        self.get_json("/api/workspaces").await
    }

    pub async fn list_models(&self) -> Result<WebUiModelsResponse, HermesError> {
        self.get_json("/api/models").await
    }

    pub async fn list_profiles(&self) -> Result<WebUiProfilesResponse, HermesError> {
        self.get_json("/api/profiles").await
    }

    pub async fn upload_file(
        &self,
        session_id: &str,
        filename: &str,
        bytes: Vec<u8>,
    ) -> Result<WebUiAttachment, HermesError> {
        #[derive(Deserialize)]
        struct UploadResponse {
            filename: String,
            path: String,
            #[serde(default)]
            mime: String,
            #[serde(default)]
            size: Option<u64>,
            #[serde(default)]
            is_image: Option<bool>,
        }

        let part = reqwest::multipart::Part::bytes(bytes).file_name(filename.to_string());
        let form = reqwest::multipart::Form::new()
            .text("session_id", session_id.to_string())
            .part("file", part);
        let resp = self
            .http
            .post(self.endpoint("/api/upload"))
            .multipart(form)
            .send()
            .await
            .map_err(|e| HermesError::Network(e.to_string()))?;
        let uploaded: UploadResponse = parse_response(resp).await?;
        Ok(WebUiAttachment {
            name: uploaded.filename,
            path: uploaded.path,
            mime: uploaded.mime,
            size: uploaded.size,
            is_image: uploaded.is_image,
        })
    }

    pub async fn stream_chat(
        &self,
        stream_id: &str,
        event_tx: &broadcast::Sender<WebUiStreamEvent>,
    ) -> Result<(), HermesError> {
        let url = self.endpoint(&format!(
            "/api/chat/stream?stream_id={}",
            encode_query(stream_id)
        ));
        let resp = self
            .http
            .get(url)
            .header("Accept", "text/event-stream")
            .send()
            .await
            .map_err(|e| HermesError::Network(e.to_string()))?;
        let status = resp.status().as_u16();
        if !resp.status().is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(HermesError::Http(status, body));
        }

        let mut stream = resp.bytes_stream();
        let mut buffer = String::new();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|e| HermesError::Network(e.to_string()))?;
            buffer.push_str(&String::from_utf8_lossy(&chunk));
            while let Some(idx) = find_sse_boundary(&buffer) {
                let block = buffer[..idx].to_string();
                let drain_to = if buffer[idx..].starts_with("\r\n\r\n") {
                    idx + 4
                } else {
                    idx + 2
                };
                buffer.drain(..drain_to);
                if let Some(frame) = parse_sse_frame(&block) {
                    match parse_stream_event(&frame) {
                        Ok(event) => {
                            let terminal = matches!(
                                event,
                                WebUiStreamEvent::StreamEnd { .. }
                                    | WebUiStreamEvent::AppError(_)
                                    | WebUiStreamEvent::Error(_)
                                    | WebUiStreamEvent::Cancel(_)
                            );
                            let _ = event_tx.send(event);
                            if terminal {
                                return Ok(());
                            }
                        }
                        Err(err) => {
                            let _ = event_tx.send(WebUiStreamEvent::Error(Value::String(format!(
                                "Failed to parse stream event: {err}"
                            ))));
                        }
                    }
                }
            }
        }

        Ok(())
    }

    async fn get_json<T>(&self, path: &str) -> Result<T, HermesError>
    where
        T: for<'de> Deserialize<'de>,
    {
        let resp = self
            .http
            .get(self.endpoint(path))
            .send()
            .await
            .map_err(|e| HermesError::Network(e.to_string()))?;
        parse_response(resp).await
    }

    async fn post_json<T, B>(&self, path: &str, body: &B) -> Result<T, HermesError>
    where
        T: for<'de> Deserialize<'de>,
        B: Serialize + ?Sized,
    {
        let resp = self
            .http
            .post(self.endpoint(path))
            .json(body)
            .send()
            .await
            .map_err(|e| HermesError::Network(e.to_string()))?;
        parse_response(resp).await
    }

    fn endpoint(&self, path: &str) -> String {
        format!(
            "{}/{}",
            self.base_url.trim_end_matches('/'),
            path.trim_start_matches('/')
        )
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct SseFrame {
    pub id: Option<String>,
    pub event: String,
    pub data: String,
}

#[derive(Debug, Clone, PartialEq)]
pub enum WebUiStreamEvent {
    Token {
        text: String,
    },
    InterimAssistant {
        text: String,
        already_streamed: bool,
    },
    Reasoning {
        text: String,
    },
    Tool(WebUiToolCall),
    ToolComplete(WebUiToolCall),
    Approval(Value),
    Clarify(Value),
    Title {
        session_id: Option<String>,
        title: String,
    },
    TitleStatus(Value),
    Goal(Value),
    GoalContinue(Value),
    Done {
        session: WebUiSession,
        usage: Option<Value>,
    },
    StreamEnd {
        session_id: Option<String>,
    },
    PendingSteerLeftover {
        session_id: Option<String>,
        text: String,
    },
    Compressing(Value),
    Compressed(Value),
    Metering(Value),
    AppError(Value),
    Warning(Value),
    Error(Value),
    Cancel(Value),
    Unknown {
        event: String,
        data: Value,
    },
}

pub fn parse_sse_frame(block: &str) -> Option<SseFrame> {
    let mut event = None;
    let mut id = None;
    let mut data = Vec::new();

    for raw in block.lines() {
        let line = raw.trim_end_matches('\r');
        if line.is_empty() || line.starts_with(':') {
            continue;
        }
        if let Some(value) = line.strip_prefix("event:") {
            event = Some(value.trim_start().to_string());
        } else if let Some(value) = line.strip_prefix("id:") {
            id = Some(value.trim_start().to_string());
        } else if let Some(value) = line.strip_prefix("data:") {
            data.push(value.trim_start().to_string());
        }
    }

    let event = event.unwrap_or_else(|| "message".to_string());
    let data = data.join("\n");
    if data.is_empty() && event == "message" {
        None
    } else {
        Some(SseFrame { id, event, data })
    }
}

pub fn normalize_webui_url(raw: &str) -> String {
    let url = raw.trim().trim_end_matches('/');
    if url.is_empty() {
        "http://127.0.0.1:8788".to_string()
    } else {
        url.to_string()
    }
}

fn build_http_client() -> Client {
    let builder = Client::builder();

    #[cfg(not(target_arch = "wasm32"))]
    let builder = builder
        .timeout(std::time::Duration::from_secs(180))
        .connect_timeout(std::time::Duration::from_secs(15));

    builder
        .build()
        .expect("failed to build Hermes WebUI client")
}

async fn parse_response<T>(resp: reqwest::Response) -> Result<T, HermesError>
where
    T: for<'de> Deserialize<'de>,
{
    let status = resp.status().as_u16();
    if status == 401 || status == 403 {
        return Err(HermesError::Auth("Hermes WebUI auth required".into()));
    }
    if !resp.status().is_success() {
        let body = resp.text().await.unwrap_or_default();
        return Err(HermesError::Http(status, body));
    }

    resp.json()
        .await
        .map_err(|e| HermesError::Parse(e.to_string()))
}

fn find_sse_boundary(buffer: &str) -> Option<usize> {
    buffer.find("\n\n").or_else(|| buffer.find("\r\n\r\n"))
}

fn encode_query(value: &str) -> String {
    value
        .bytes()
        .flat_map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                vec![b as char]
            }
            _ => format!("%{b:02X}").chars().collect(),
        })
        .collect()
}

pub fn parse_stream_event(frame: &SseFrame) -> Result<WebUiStreamEvent, serde_json::Error> {
    let data = if frame.data.trim().is_empty() {
        Value::Null
    } else {
        serde_json::from_str::<Value>(&frame.data)?
    };

    Ok(match frame.event.as_str() {
        "token" => WebUiStreamEvent::Token {
            text: string_field(&data, "text"),
        },
        "interim_assistant" => WebUiStreamEvent::InterimAssistant {
            text: string_field(&data, "text"),
            already_streamed: data
                .get("already_streamed")
                .and_then(Value::as_bool)
                .unwrap_or(false),
        },
        "reasoning" => WebUiStreamEvent::Reasoning {
            text: string_field(&data, "text"),
        },
        "tool" => WebUiStreamEvent::Tool(tool_from_value(data)),
        "tool_complete" => {
            let mut tool = tool_from_value(data);
            tool.done = true;
            WebUiStreamEvent::ToolComplete(tool)
        }
        "approval" => WebUiStreamEvent::Approval(data),
        "clarify" => WebUiStreamEvent::Clarify(data),
        "title" => WebUiStreamEvent::Title {
            session_id: data
                .get("session_id")
                .and_then(Value::as_str)
                .map(ToString::to_string),
            title: string_field(&data, "title"),
        },
        "title_status" => WebUiStreamEvent::TitleStatus(data),
        "goal" => WebUiStreamEvent::Goal(data),
        "goal_continue" => WebUiStreamEvent::GoalContinue(data),
        "done" => {
            #[derive(Deserialize)]
            struct DonePayload {
                session: WebUiSession,
                #[serde(default)]
                usage: Option<Value>,
            }
            let done: DonePayload = serde_json::from_value(data)?;
            WebUiStreamEvent::Done {
                session: done.session,
                usage: done.usage,
            }
        }
        "stream_end" => WebUiStreamEvent::StreamEnd {
            session_id: data
                .get("session_id")
                .and_then(Value::as_str)
                .map(ToString::to_string),
        },
        "pending_steer_leftover" => WebUiStreamEvent::PendingSteerLeftover {
            session_id: data
                .get("session_id")
                .and_then(Value::as_str)
                .map(ToString::to_string),
            text: string_field(&data, "text"),
        },
        "compressing" => WebUiStreamEvent::Compressing(data),
        "compressed" => WebUiStreamEvent::Compressed(data),
        "metering" => WebUiStreamEvent::Metering(data),
        "apperror" => WebUiStreamEvent::AppError(data),
        "warning" => WebUiStreamEvent::Warning(data),
        "error" => WebUiStreamEvent::Error(data),
        "cancel" => WebUiStreamEvent::Cancel(data),
        _ => WebUiStreamEvent::Unknown {
            event: frame.event.clone(),
            data,
        },
    })
}

fn tool_from_value(value: Value) -> WebUiToolCall {
    serde_json::from_value(value).unwrap_or_else(|_| WebUiToolCall {
        name: "tool".to_string(),
        preview: String::new(),
        args: Value::Null,
        snippet: None,
        done: false,
        is_error: false,
        duration: None,
        tid: None,
    })
}

fn string_field(value: &Value, key: &str) -> String {
    value
        .get(key)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_session_list_and_detail_payloads() {
        let list: WebUiSessionsResponse = serde_json::from_str(
            r#"{
                "sessions": [{
                    "session_id": "abc123",
                    "title": "Build chat",
                    "workspace": "/tmp/project",
                    "model": "claude-opus",
                    "message_count": 2,
                    "active_stream_id": "run1",
                    "is_streaming": true
                }]
            }"#,
        )
        .unwrap();
        assert_eq!(list.sessions[0].session_id, "abc123");
        assert_eq!(list.sessions[0].workspace.as_deref(), Some("/tmp/project"));
        assert!(list.sessions[0].is_streaming);

        let detail: WebUiSessionResponse = serde_json::from_str(
            r#"{
                "session": {
                    "session_id": "abc123",
                    "title": "Build chat",
                    "message_count": 2,
                    "messages": [
                        {"role": "user", "content": "hello"},
                        {"role": "assistant", "content": [{"type":"text","text":"hi"}], "reasoning": "thinking"}
                    ],
                    "tool_calls": [{"name": "Read", "preview": "README.md", "done": true}]
                }
            }"#,
        )
        .unwrap();

        assert_eq!(detail.session.messages.len(), 2);
        assert_eq!(detail.session.messages[1].content_text(), "hi");
        assert_eq!(detail.session.tool_calls[0].name, "Read");
    }

    #[test]
    fn parses_named_sse_events() {
        let frame =
            parse_sse_frame("id: 42\nevent: token\ndata: {\"text\":\"hello\"}\n\n").unwrap();
        assert_eq!(frame.id.as_deref(), Some("42"));
        assert_eq!(frame.event, "token");
        assert_eq!(
            parse_stream_event(&frame).unwrap(),
            WebUiStreamEvent::Token {
                text: "hello".to_string()
            }
        );

        let tool = parse_sse_frame(
            "event: tool_complete\ndata: {\"name\":\"Bash\",\"preview\":\"cargo test\",\"args\":{\"cmd\":\"cargo test\"},\"duration\":1.5}\n\n",
        )
        .unwrap();
        match parse_stream_event(&tool).unwrap() {
            WebUiStreamEvent::ToolComplete(tool) => {
                assert_eq!(tool.name, "Bash");
                assert!(tool.done);
                assert_eq!(tool.duration, Some(1.5));
            }
            other => panic!("unexpected event: {other:?}"),
        }
    }

    #[test]
    fn parses_done_event_with_canonical_session() {
        let frame = parse_sse_frame(
            "event: done\ndata: {\"session\":{\"session_id\":\"abc123\",\"title\":\"Done\",\"message_count\":2,\"messages\":[{\"role\":\"assistant\",\"content\":\"finished\"}]},\"usage\":{\"input_tokens\":10}}\n\n",
        )
        .unwrap();

        match parse_stream_event(&frame).unwrap() {
            WebUiStreamEvent::Done { session, usage } => {
                assert_eq!(session.summary.title, "Done");
                assert_eq!(session.messages[0].content_text(), "finished");
                assert_eq!(usage.unwrap()["input_tokens"], 10);
            }
            other => panic!("unexpected event: {other:?}"),
        }
    }
}
