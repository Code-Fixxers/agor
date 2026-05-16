use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PermissionStatus {
    Pending,
    Approved,
    Denied,
    Cancelled,
    TimedOut,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PermissionRequestContent {
    pub request_id: String,
    pub legacy_permission_id: Option<String>,
    pub task_id: Option<String>,
    pub tool_name: String,
    pub tool_input: serde_json::Map<String, Value>,
    pub description: Option<String>,
    pub status: PermissionStatus,
    pub decided_at: Option<String>,
    pub decided_by: Option<String>,
    pub requested_at: Option<String>,
    pub expires_at: Option<String>,
    pub decision_note: Option<String>,
}

impl PermissionRequestContent {
    pub fn permission_id(&self) -> &str {
        self.legacy_permission_id
            .as_deref()
            .unwrap_or(&self.request_id)
    }

    pub fn input_preview(&self) -> String {
        let preferred_keys = ["command", "file_path", "path", "pattern", "url", "query"];
        for key in &preferred_keys {
            if let Some(val) = self.tool_input.get(*key) {
                if let Some(s) = val.as_str() {
                    let truncated: String = s.chars().take(120).collect();
                    return truncated;
                }
            }
        }
        let json = serde_json::to_string(&self.tool_input).unwrap_or_default();
        json.chars().take(120).collect()
    }
}
