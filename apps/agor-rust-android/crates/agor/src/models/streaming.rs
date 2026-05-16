use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StreamingStartEvent {
    pub session_id: String,
    pub message_id: Option<String>,
    pub task_id: Option<String>,
    pub index: Option<i64>,
    pub timestamp: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StreamingChunkEvent {
    pub session_id: String,
    pub message_id: Option<String>,
    pub text: String,
    pub index: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StreamingEndEvent {
    pub session_id: String,
    pub message_id: Option<String>,
    #[serde(rename = "final")]
    pub final_text: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StreamingErrorEvent {
    pub session_id: String,
    pub error: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ThinkingStartEvent {
    pub session_id: String,
    pub message_id: Option<String>,
    pub task_id: Option<String>,
    pub timestamp: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ThinkingChunkEvent {
    pub session_id: String,
    pub message_id: Option<String>,
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ThinkingEndEvent {
    pub session_id: String,
    pub message_id: Option<String>,
}
