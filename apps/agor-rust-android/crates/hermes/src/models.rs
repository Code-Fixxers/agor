use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HermesSession {
    pub id: String,
    pub conversation_id: String,
    pub title: String,
    pub created_at_millis: i64,
    pub updated_at_millis: i64,
    #[serde(default)]
    pub active: bool,
    pub last_response_id: Option<String>,
    pub error_message: Option<String>,
    #[serde(default)]
    pub turns: Vec<HermesTurn>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HermesTurn {
    pub id: String,
    pub role: String,
    pub content: String,
    pub created_at_millis: i64,
    #[serde(default)]
    pub streaming: bool,
    #[serde(default)]
    pub attachments: Vec<HermesAttachment>,
    #[serde(default)]
    pub progress: Vec<HermesProgressItem>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HermesAttachment {
    pub id: String,
    pub mime_type: String,
    pub local_path: String,
    pub width: Option<i32>,
    pub height: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HermesProgressItem {
    pub label: String,
    pub at: i64,
}

#[derive(Debug, Clone)]
pub enum HermesSessionEvent {
    TextDelta {
        session_id: String,
        turn_id: String,
        text: String,
    },
    Progress {
        session_id: String,
        turn_id: String,
        label: String,
    },
    Completed {
        session_id: String,
        turn_id: String,
        text: String,
    },
    Failed {
        session_id: String,
        turn_id: String,
        message: String,
    },
}

#[derive(Debug, Clone)]
pub enum HermesResponseEvent {
    ReasoningDelta(String),
    TextDelta(String),
    Progress(String),
    Completed {
        text: String,
        response_id: Option<String>,
    },
    Failed(String),
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HermesMessage {
    pub role: String,
    pub content: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HermesVoicePhase {
    Idle,
    LoadingModels,
    Listening,
    Recording,
    Transcribing,
    Reviewing,
    Sending,
    Speaking,
    Error,
}

#[derive(Debug, Clone)]
pub struct HermesVoiceState {
    pub enabled: bool,
    pub phase: HermesVoicePhase,
    pub active_session_id: Option<String>,
    pub pending_transcript: Option<String>,
    pub audio_level: f32,
    pub threshold: f32,
    pub error_message: Option<String>,
    pub needs_whisper_download: bool,
    pub transcription_endpoint: Option<String>,
}

impl Default for HermesVoiceState {
    fn default() -> Self {
        Self {
            enabled: false,
            phase: HermesVoicePhase::Idle,
            active_session_id: None,
            pending_transcript: None,
            audio_level: 0.0,
            threshold: 0.7,
            error_message: None,
            needs_whisper_download: false,
            transcription_endpoint: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HermesConfig {
    pub base_url: Option<String>,
    pub token: Option<String>,
    pub model: Option<String>,
    pub whisper_url: Option<String>,
    pub whisper_token: Option<String>,
    pub whisper_model: Option<String>,
    pub whisper_model_artifact_url: Option<String>,
    pub whisper_model_path: Option<String>,
}

impl Default for HermesConfig {
    fn default() -> Self {
        Self {
            base_url: None,
            token: None,
            model: None,
            whisper_url: None,
            whisper_token: None,
            whisper_model: None,
            whisper_model_artifact_url: None,
            whisper_model_path: None,
        }
    }
}

pub const DEFAULT_MODEL: &str = "hermes-model";
