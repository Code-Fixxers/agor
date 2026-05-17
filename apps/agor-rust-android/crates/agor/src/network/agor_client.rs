use reqwest::Client;
use serde_json::{json, Value};
use std::sync::{Arc, RwLock};

use crate::models::*;
use crate::state::storage::AppStorage;
use agor_shared::logger::{AppLogger, LogCategory};
use agor_shared::url::agor_base_url_candidates;

#[derive(Debug, Clone)]
pub struct AuthTokens {
    pub access_token: Option<String>,
    pub refresh_token: Option<String>,
    pub server_url: Option<String>,
    pub user_id: Option<String>,
    pub last_email: Option<String>,
}

impl Default for AuthTokens {
    fn default() -> Self {
        Self {
            access_token: None,
            refresh_token: None,
            server_url: None,
            user_id: None,
            last_email: None,
        }
    }
}

#[derive(Debug)]
pub struct LoginResult {
    pub access_token: String,
    pub refresh_token: Option<String>,
    pub user: User,
}

#[derive(Debug)]
pub struct UploadedFile {
    pub filename: String,
    pub path: String,
    pub size: i64,
    pub mime_type: String,
}

#[derive(Debug, thiserror::Error)]
pub enum AgorError {
    #[error("Authentication failed: {0}")]
    Auth(String),
    #[error("HTTP error {status}: {message}")]
    Http { status: u16, message: String },
    #[error("Network error: {0}")]
    Network(String),
    #[error("Parse error: {0}")]
    Parse(String),
}

impl From<reqwest::Error> for AgorError {
    fn from(e: reqwest::Error) -> Self {
        if let Some(status) = e.status() {
            AgorError::Http {
                status: status.as_u16(),
                message: e.to_string(),
            }
        } else if is_transient_network_error(&e) {
            AgorError::Network(e.to_string())
        } else {
            AgorError::Network(e.to_string())
        }
    }
}

fn load_active_tokens() -> AuthTokens {
    let storage = AppStorage::load();
    let Some(profile) = storage
        .active_profile()
        .or_else(|| storage.default_profile())
    else {
        return AuthTokens::default();
    };
    let Some(creds) = storage.credentials.get(&profile.id) else {
        return AuthTokens::default();
    };

    AuthTokens {
        access_token: creds.access_token.clone(),
        refresh_token: creds.refresh_token.clone(),
        server_url: Some(profile.url.clone()),
        user_id: creds.user_id.clone(),
        last_email: creds.user_email.clone(),
    }
}

fn local_login_payload(email: &str, password: &str) -> Value {
    json!({
        "strategy": "local",
        "email": email,
        "password": password,
    })
}

fn api_key_login_payload(api_key: &str) -> Value {
    json!({
        "strategy": "api-key",
        "apiKey": api_key,
    })
}

#[cfg(not(target_arch = "wasm32"))]
fn is_transient_network_error(e: &reqwest::Error) -> bool {
    e.is_connect() || e.is_timeout()
}

#[cfg(target_arch = "wasm32")]
fn is_transient_network_error(_e: &reqwest::Error) -> bool {
    true
}

#[derive(Clone)]
pub struct AgorClient {
    http: Client,
    pub tokens: Arc<RwLock<AuthTokens>>,
    logger: AppLogger,
}

impl AgorClient {
    pub fn new(logger: AppLogger) -> Self {
        let http = build_http_client();
        let tokens = load_active_tokens();

        Self {
            http,
            tokens: Arc::new(RwLock::new(tokens)),
            logger,
        }
    }

    pub fn base_url(&self) -> String {
        self.tokens
            .read()
            .unwrap()
            .server_url
            .clone()
            .unwrap_or_default()
    }

    pub fn set_base_url(&self, url: &str) {
        self.tokens.write().unwrap().server_url = Some(url.to_string());
    }

    pub fn access_token(&self) -> Option<String> {
        self.tokens.read().unwrap().access_token.clone()
    }

    fn auth_header(&self) -> Option<String> {
        self.access_token().map(|t| format!("Bearer {t}"))
    }

    async fn get_json(&self, path: &str) -> Result<Value, AgorError> {
        let url = format!("{}{path}", self.base_url());
        self.logger.debug(LogCategory::Http, format!("GET {url}"));

        let mut req = self.http.get(&url);
        if let Some(auth) = self.auth_header() {
            req = req.header("Authorization", auth);
        }

        let resp = req.send().await?;
        let status = resp.status();

        if status == reqwest::StatusCode::UNAUTHORIZED {
            return Err(AgorError::Auth("Token expired".into()));
        }

        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(AgorError::Http {
                status: status.as_u16(),
                message: body,
            });
        }

        let body: Value = resp.json().await?;
        Ok(body)
    }

    async fn post_json(&self, path: &str, payload: &Value) -> Result<Value, AgorError> {
        let url = format!("{}{path}", self.base_url());
        self.logger.debug(LogCategory::Http, format!("POST {url}"));

        let mut req = self.http.post(&url).json(payload);
        if let Some(auth) = self.auth_header() {
            req = req.header("Authorization", auth);
        }

        let resp = req.send().await?;
        let status = resp.status();

        if status == reqwest::StatusCode::UNAUTHORIZED {
            return Err(AgorError::Auth("Token expired".into()));
        }

        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(AgorError::Http {
                status: status.as_u16(),
                message: body,
            });
        }

        let body: Value = resp.json().await.unwrap_or(Value::Null);
        Ok(body)
    }

    async fn patch_json(&self, path: &str, payload: &Value) -> Result<Value, AgorError> {
        let url = format!("{}{path}", self.base_url());
        self.logger.debug(LogCategory::Http, format!("PATCH {url}"));

        let mut req = self.http.patch(&url).json(payload);
        if let Some(auth) = self.auth_header() {
            req = req.header("Authorization", auth);
        }

        let resp = req.send().await?;
        let status = resp.status();

        if status == reqwest::StatusCode::UNAUTHORIZED {
            return Err(AgorError::Auth("Token expired".into()));
        }

        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(AgorError::Http {
                status: status.as_u16(),
                message: body,
            });
        }

        let body: Value = resp.json().await.unwrap_or(Value::Null);
        Ok(body)
    }

    async fn delete(&self, path: &str) -> Result<(), AgorError> {
        let url = format!("{}{path}", self.base_url());
        self.logger
            .debug(LogCategory::Http, format!("DELETE {url}"));

        let mut req = self.http.delete(&url);
        if let Some(auth) = self.auth_header() {
            req = req.header("Authorization", auth);
        }

        let resp = req.send().await?;
        if !resp.status().is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(AgorError::Http {
                status: 0,
                message: body,
            });
        }

        Ok(())
    }

    fn unwrap_paginated(val: Value) -> Vec<Value> {
        if let Some(data) = val.get("data").and_then(|d| d.as_array()) {
            data.clone()
        } else if let Some(arr) = val.as_array() {
            arr.clone()
        } else {
            vec![val]
        }
    }

    fn parse_vec<T: serde::de::DeserializeOwned>(items: Vec<Value>) -> Vec<T> {
        items
            .into_iter()
            .filter_map(|v| serde_json::from_value(v).ok())
            .collect()
    }

    // --- Health ---

    pub async fn probe_base_url(&self, raw: &str) -> Result<String, AgorError> {
        for candidate in agor_base_url_candidates(raw) {
            let url = format!("{candidate}/health");
            self.logger
                .debug(LogCategory::Http, format!("Probing {url}"));
            match self.http.get(&url).send().await {
                Ok(resp) if resp.status().is_success() => return Ok(candidate),
                _ => continue,
            }
        }
        Err(AgorError::Network(format!(
            "No reachable Agor daemon at {raw}"
        )))
    }

    // --- Auth ---

    pub async fn login(&self, email: &str, password: &str) -> Result<LoginResult, AgorError> {
        let payload = local_login_payload(email, password);
        let resp = self.post_json("/authentication", &payload).await?;
        self.parse_login_response(resp)
    }

    pub async fn login_with_api_key(&self, api_key: &str) -> Result<LoginResult, AgorError> {
        let payload = api_key_login_payload(api_key);
        let resp = self.post_json("/authentication", &payload).await?;
        self.parse_login_response(resp)
    }

    fn parse_login_response(&self, resp: Value) -> Result<LoginResult, AgorError> {
        let access_token = resp["accessToken"]
            .as_str()
            .ok_or_else(|| AgorError::Auth("No accessToken in response".into()))?
            .to_string();

        let refresh_token = resp["refreshToken"].as_str().map(String::from);

        let user: User = serde_json::from_value(resp["user"].clone())
            .map_err(|e| AgorError::Parse(e.to_string()))?;

        {
            let mut tokens = self.tokens.write().unwrap();
            tokens.access_token = Some(access_token.clone());
            tokens.refresh_token = refresh_token.clone();
            tokens.user_id = Some(user.user_id.clone());
            tokens.last_email = user.email.clone();
        }

        self.logger
            .info(LogCategory::Auth, format!("Logged in as {}", user.name));

        Ok(LoginResult {
            access_token,
            refresh_token,
            user,
        })
    }

    pub async fn refresh_token(&self) -> Result<String, AgorError> {
        let current_token = self
            .access_token()
            .ok_or_else(|| AgorError::Auth("No token to refresh".into()))?;

        let payload = json!({
            "strategy": "jwt",
            "accessToken": current_token,
        });

        let resp = self.post_json("/authentication", &payload).await?;
        let new_token = resp["accessToken"]
            .as_str()
            .ok_or_else(|| AgorError::Auth("No accessToken in refresh response".into()))?
            .to_string();

        self.tokens.write().unwrap().access_token = Some(new_token.clone());
        self.logger.info(LogCategory::Auth, "Token refreshed");

        Ok(new_token)
    }

    pub async fn me(&self) -> Result<User, AgorError> {
        let user_id = self
            .tokens
            .read()
            .unwrap()
            .user_id
            .clone()
            .unwrap_or_default();
        let resp = self.get_json(&format!("/users/{user_id}")).await?;
        serde_json::from_value(resp).map_err(|e| AgorError::Parse(e.to_string()))
    }

    // --- Boards ---

    pub async fn list_boards(&self) -> Result<Vec<Board>, AgorError> {
        let resp = self.get_json("/boards").await?;
        Ok(Self::parse_vec(Self::unwrap_paginated(resp)))
    }

    // --- Repos ---

    pub async fn list_repos(&self) -> Result<Vec<Repo>, AgorError> {
        let resp = self.get_json("/repos").await?;
        Ok(Self::parse_vec(Self::unwrap_paginated(resp)))
    }

    // --- Worktrees ---

    pub async fn list_worktrees(&self, board_id: Option<&str>) -> Result<Vec<Worktree>, AgorError> {
        let path = match board_id {
            Some(id) => format!("/worktrees?board_id={id}"),
            None => "/worktrees".to_string(),
        };
        let resp = self.get_json(&path).await?;
        Ok(Self::parse_vec(Self::unwrap_paginated(resp)))
    }

    // --- Sessions ---

    pub async fn list_sessions(
        &self,
        archived: bool,
        limit: Option<u32>,
    ) -> Result<Vec<Session>, AgorError> {
        let mut path = "/sessions?$sort[last_updated]=-1".to_string();
        if !archived {
            path.push_str("&archived=false");
        }
        if let Some(lim) = limit {
            path.push_str(&format!("&$limit={lim}"));
        }
        let resp = self.get_json(&path).await?;
        Ok(Self::parse_vec(Self::unwrap_paginated(resp)))
    }

    pub async fn get_session(&self, id: &str) -> Result<Session, AgorError> {
        let resp = self.get_json(&format!("/sessions/{id}")).await?;
        serde_json::from_value(resp).map_err(|e| AgorError::Parse(e.to_string()))
    }

    pub async fn create_session(
        &self,
        worktree_id: &str,
        agentic_tool: &str,
    ) -> Result<Session, AgorError> {
        let payload = json!({
            "worktree_id": worktree_id,
            "agentic_tool": agentic_tool,
        });
        let resp = self.post_json("/sessions", &payload).await?;
        serde_json::from_value(resp).map_err(|e| AgorError::Parse(e.to_string()))
    }

    pub async fn patch_session(&self, id: &str, patch: &Value) -> Result<Session, AgorError> {
        let resp = self.patch_json(&format!("/sessions/{id}"), patch).await?;
        serde_json::from_value(resp).map_err(|e| AgorError::Parse(e.to_string()))
    }

    pub async fn stop_session(&self, id: &str) -> Result<(), AgorError> {
        self.post_json(&format!("/sessions/{id}/stop"), &json!({}))
            .await?;
        Ok(())
    }

    // --- Tasks ---

    pub async fn list_tasks(&self, session_id: &str) -> Result<Vec<AgorTask>, AgorError> {
        let resp = self
            .get_json(&format!(
                "/tasks?session_id={session_id}&$sort[created_at]=1"
            ))
            .await?;
        Ok(Self::parse_vec(Self::unwrap_paginated(resp)))
    }

    // --- Messages ---

    pub async fn list_messages(
        &self,
        session_id: &str,
        task_id: Option<&str>,
        limit: Option<u32>,
        skip: Option<u32>,
    ) -> Result<Vec<Message>, AgorError> {
        let mut path = format!("/messages?session_id={session_id}&$sort[index]=1");
        if let Some(tid) = task_id {
            path.push_str(&format!("&task_id={tid}"));
        }
        if let Some(lim) = limit {
            path.push_str(&format!("&$limit={lim}"));
        }
        if let Some(sk) = skip {
            path.push_str(&format!("&$skip={sk}"));
        }
        let resp = self.get_json(&path).await?;
        Ok(Self::parse_vec(Self::unwrap_paginated(resp)))
    }

    // --- Prompts ---

    pub async fn send_prompt(&self, session_id: &str, prompt: &str) -> Result<(), AgorError> {
        let payload = json!({ "prompt": prompt });
        self.post_json(&format!("/sessions/{session_id}/prompt"), &payload)
            .await?;
        Ok(())
    }

    // --- Permissions ---

    pub async fn decide_permission(
        &self,
        session_id: &str,
        request_id: &str,
        task_id: Option<&str>,
        allow: bool,
        scope: &str,
        user_id: &str,
    ) -> Result<(), AgorError> {
        let mut payload = json!({
            "requestId": request_id,
            "allow": allow,
            "scope": scope,
            "decidedBy": user_id,
        });
        if let Some(tid) = task_id {
            payload["taskId"] = json!(tid);
        }
        if allow {
            payload["remember"] = json!(scope != "once");
        }
        self.post_json(
            &format!("/sessions/{session_id}/permission-decision"),
            &payload,
        )
        .await?;
        Ok(())
    }

    // --- Input Requests ---

    pub async fn answer_input_request(
        &self,
        session_id: &str,
        request_id: &str,
        task_id: Option<&str>,
        answers: &std::collections::HashMap<String, String>,
        user_id: &str,
    ) -> Result<(), AgorError> {
        let mut payload = json!({
            "requestId": request_id,
            "answers": answers,
            "respondedBy": user_id,
        });
        if let Some(tid) = task_id {
            payload["taskId"] = json!(tid);
        }
        self.post_json(&format!("/sessions/{session_id}/input-response"), &payload)
            .await?;
        Ok(())
    }

    // --- MCP Servers ---

    pub async fn list_mcp_servers(&self) -> Result<Vec<MCPServer>, AgorError> {
        let resp = self.get_json("/mcp-servers").await?;
        Ok(Self::parse_vec(Self::unwrap_paginated(resp)))
    }

    pub async fn list_session_mcp_servers(
        &self,
        session_id: &str,
    ) -> Result<Vec<SessionMCPServer>, AgorError> {
        let resp = self
            .get_json(&format!("/sessions/{session_id}/mcp-servers"))
            .await?;
        Ok(Self::parse_vec(Self::unwrap_paginated(resp)))
    }

    pub async fn add_session_mcp_server(
        &self,
        session_id: &str,
        mcp_server_id: &str,
    ) -> Result<(), AgorError> {
        let payload = json!({ "mcp_server_id": mcp_server_id });
        self.post_json(&format!("/sessions/{session_id}/mcp-servers"), &payload)
            .await?;
        Ok(())
    }

    pub async fn remove_session_mcp_server(
        &self,
        session_id: &str,
        mcp_server_id: &str,
    ) -> Result<(), AgorError> {
        self.delete(&format!(
            "/sessions/{session_id}/mcp-servers/{mcp_server_id}"
        ))
        .await
    }

    pub async fn set_session_mcp_server_enabled(
        &self,
        session_id: &str,
        mcp_server_id: &str,
        enabled: bool,
    ) -> Result<(), AgorError> {
        let payload = json!({ "enabled": enabled });
        self.patch_json(
            &format!("/sessions/{session_id}/mcp-servers/{mcp_server_id}"),
            &payload,
        )
        .await?;
        Ok(())
    }
}

fn build_http_client() -> Client {
    let builder = Client::builder();

    #[cfg(not(target_arch = "wasm32"))]
    let builder = builder
        .danger_accept_invalid_certs(cfg!(debug_assertions))
        .timeout(std::time::Duration::from_secs(30));

    builder.build().expect("failed to build HTTP client")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn local_login_payload_uses_feathers_local_strategy() {
        assert_eq!(
            local_login_payload("user@example.com", "secret"),
            json!({
                "strategy": "local",
                "email": "user@example.com",
                "password": "secret",
            })
        );
    }

    #[test]
    fn api_key_login_payload_uses_api_key_strategy() {
        assert_eq!(
            api_key_login_payload("agor_sk_test"),
            json!({
                "strategy": "api-key",
                "apiKey": "agor_sk_test",
            })
        );
    }
}
