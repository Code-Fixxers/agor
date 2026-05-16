use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Worktree {
    pub worktree_id: String,
    pub repo_id: String,
    pub board_id: Option<String>,
    pub name: String,
    pub branch: Option<String>,
    pub path: Option<String>,
    pub status: Option<String>,
    pub created_at: Option<String>,
    pub created_by: Option<String>,
    pub archived: Option<bool>,
    pub archived_reason: Option<String>,
    pub others_can: Option<String>,
}
