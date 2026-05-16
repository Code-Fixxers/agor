use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SessionStatus {
    Idle,
    Running,
    Stopping,
    AwaitingPermission,
    AwaitingInput,
    TimedOut,
    Completed,
    Failed,
}

impl SessionStatus {
    pub fn needs_attention(&self) -> bool {
        matches!(self, Self::AwaitingPermission | Self::AwaitingInput)
    }

    pub fn is_active(&self) -> bool {
        matches!(self, Self::Running | Self::Stopping | Self::AwaitingPermission | Self::AwaitingInput)
    }

    pub fn display_label(&self) -> &str {
        match self {
            Self::Idle => "Idle",
            Self::Running => "Running",
            Self::Stopping => "Stopping",
            Self::AwaitingPermission => "Needs Permission",
            Self::AwaitingInput => "Needs Input",
            Self::TimedOut => "Timed Out",
            Self::Completed => "Completed",
            Self::Failed => "Failed",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum AgenticTool {
    ClaudeCode,
    Codex,
    Gemini,
    Opencode,
}

impl AgenticTool {
    pub fn display_name(&self) -> &str {
        match self {
            Self::ClaudeCode => "Claude Code",
            Self::Codex => "Codex",
            Self::Gemini => "Gemini",
            Self::Opencode => "OpenCode",
        }
    }

    pub fn permission_modes(&self) -> Vec<PermissionMode> {
        match self {
            Self::ClaudeCode => vec![
                PermissionMode::Default,
                PermissionMode::AcceptEdits,
                PermissionMode::Bypass,
                PermissionMode::Plan,
            ],
            Self::Codex => vec![
                PermissionMode::Ask,
                PermissionMode::Auto,
                PermissionMode::OnFailure,
                PermissionMode::AllowAll,
            ],
            Self::Gemini => vec![
                PermissionMode::Default,
                PermissionMode::AutoEdit,
                PermissionMode::Yolo,
            ],
            Self::Opencode => vec![PermissionMode::Default],
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PermissionMode {
    Default,
    AcceptEdits,
    Bypass,
    Plan,
    DontAsk,
    AutoEdit,
    Yolo,
    Ask,
    Auto,
    OnFailure,
    AllowAll,
}

impl PermissionMode {
    pub fn display_label(&self) -> &str {
        match self {
            Self::Default => "Default",
            Self::AcceptEdits => "Accept Edits",
            Self::Bypass => "Bypass Permissions",
            Self::Plan => "Plan Mode",
            Self::DontAsk => "Don't Ask",
            Self::AutoEdit => "Auto Edit",
            Self::Yolo => "YOLO",
            Self::Ask => "Ask",
            Self::Auto => "Auto",
            Self::OnFailure => "On Failure",
            Self::AllowAll => "Allow All",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GitState {
    #[serde(rename = "ref")]
    pub git_ref: Option<String>,
    pub base_sha: Option<String>,
    pub current_sha: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SessionGenealogy {
    pub forked_from_session_id: Option<String>,
    pub fork_point_task_id: Option<String>,
    pub fork_point_message_index: Option<i32>,
    pub parent_session_id: Option<String>,
    pub spawn_point_task_id: Option<String>,
    pub spawn_point_message_index: Option<i32>,
    #[serde(default)]
    pub children: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PermissionConfig {
    pub mode: Option<PermissionMode>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ModelConfig {
    pub mode: Option<String>,
    pub model: Option<String>,
    pub provider: Option<String>,
    pub notes: Option<String>,
    pub updated_at: Option<String>,
    pub thinking_mode: Option<String>,
    pub manual_thinking_tokens: Option<i32>,
    pub effort: Option<String>,
}

impl ModelConfig {
    pub fn display_summary(&self) -> String {
        let mut parts = Vec::new();
        if let Some(m) = &self.model {
            parts.push(m.clone());
        }
        if let Some(p) = &self.provider {
            parts.push(format!("via {p}"));
        }
        if let Some(e) = &self.effort {
            parts.push(format!("effort: {e}"));
        }
        if parts.is_empty() {
            "Default".to_string()
        } else {
            parts.join(" ")
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Session {
    pub session_id: String,
    pub agentic_tool: AgenticTool,
    pub agentic_tool_version: Option<String>,
    pub sdk_session_id: Option<String>,
    pub status: SessionStatus,
    pub created_at: String,
    pub last_updated: String,
    pub created_by: String,
    pub unix_username: Option<String>,
    pub worktree_id: String,
    pub worktree_board_id: Option<String>,
    pub url: Option<String>,
    pub git_state: Option<GitState>,
    pub genealogy: Option<SessionGenealogy>,
    pub tasks: Option<Vec<String>>,
    pub message_count: Option<i32>,
    pub title: Option<String>,
    pub description: Option<String>,
    pub permission_config: Option<PermissionConfig>,
    pub model_config: Option<ModelConfig>,
    pub current_context_usage: Option<i64>,
    pub context_window_limit: Option<i64>,
    pub scheduled_from_worktree: Option<bool>,
    pub ready_for_prompt: Option<bool>,
    pub archived: Option<bool>,
    pub archived_reason: Option<String>,
}

impl Session {
    pub fn display_title(&self) -> String {
        self.title
            .as_deref()
            .filter(|t| !t.is_empty())
            .unwrap_or("Untitled Session")
            .to_string()
    }

    pub fn has_explicit_title(&self) -> bool {
        self.title.as_deref().map_or(false, |t| !t.is_empty())
    }

    pub fn is_plan_mode(&self) -> bool {
        self.permission_config
            .as_ref()
            .and_then(|c| c.mode.as_ref())
            .map_or(false, |m| matches!(m, PermissionMode::Plan))
    }

    pub fn is_promptable(&self) -> bool {
        matches!(self.status, SessionStatus::Idle)
            || self.ready_for_prompt.unwrap_or(false)
    }

    pub fn can_queue_prompt(&self) -> bool {
        self.is_promptable() || self.status.is_active()
    }

    pub fn is_scheduled(&self) -> bool {
        self.scheduled_from_worktree.unwrap_or(false)
    }
}
