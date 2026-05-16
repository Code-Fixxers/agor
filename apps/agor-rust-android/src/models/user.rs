use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UserRole {
    Anonymous,
    Guest,
    Member,
    Admin,
    Superadmin,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct User {
    pub user_id: String,
    pub name: String,
    pub email: Option<String>,
    pub emoji: Option<String>,
    pub role: UserRole,
    pub unix_username: Option<String>,
    pub must_change_password: Option<bool>,
}
