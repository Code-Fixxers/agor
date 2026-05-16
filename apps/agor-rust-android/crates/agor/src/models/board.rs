use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Board {
    pub board_id: String,
    pub name: String,
    pub description: Option<String>,
    pub emoji: Option<String>,
    pub color: Option<String>,
    pub created_at: Option<String>,
    pub created_by: Option<String>,
    pub archived: Option<bool>,
}
