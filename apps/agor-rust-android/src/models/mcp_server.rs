use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MCPServer {
    pub mcp_server_id: String,
    pub name: String,
    pub description: Option<String>,
    pub transport: Option<String>,
    pub url: Option<String>,
    pub command: Option<String>,
    pub args: Option<Vec<String>>,
    pub oauth_authenticated: Option<bool>,
    pub requires_oauth: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SessionMCPServer {
    pub session_id: String,
    pub mcp_server_id: String,
    pub enabled: bool,
    pub added_at: Option<String>,
}
