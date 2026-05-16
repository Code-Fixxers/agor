use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskStatus {
    Queued,
    Running,
    Completed,
    Failed,
    Stopped,
    TimedOut,
    AwaitingPermission,
    AwaitingInput,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AgorTask {
    pub task_id: String,
    pub session_id: String,
    pub status: TaskStatus,
    pub prompt: Option<String>,
    pub title: Option<String>,
    pub created_at: String,
    pub created_by: Option<String>,
    pub completed_at: Option<String>,
    pub queue_position: Option<i32>,
    pub first_message_index: Option<i64>,
    pub last_message_index: Option<i64>,
}
