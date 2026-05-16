use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ServerProfile {
    pub id: String,
    pub label: String,
    pub url: String,
    pub email: Option<String>,
    pub is_default: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ProfileCredentials {
    pub access_token: Option<String>,
    pub refresh_token: Option<String>,
    pub user_id: Option<String>,
    pub user_email: Option<String>,
    pub saved_password: Option<String>,
    pub saved_api_key: Option<String>,
}

impl Default for ProfileCredentials {
    fn default() -> Self {
        Self {
            access_token: None,
            refresh_token: None,
            user_id: None,
            user_email: None,
            saved_password: None,
            saved_api_key: None,
        }
    }
}
