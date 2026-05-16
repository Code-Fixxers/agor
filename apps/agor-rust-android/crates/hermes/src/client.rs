use reqwest::Client;
use serde::{Deserialize, Serialize};
use tokio::sync::broadcast;

use crate::models::{HermesConfig, HermesMessage, HermesResponseEvent, DEFAULT_MODEL};

#[derive(thiserror::Error, Debug)]
pub enum HermesError {
    #[error("Not configured: {0}")]
    NotConfigured(String),
    #[error("Auth error: {0}")]
    Auth(String),
    #[error("HTTP {0}: {1}")]
    Http(u16, String),
    #[error("Network error: {0}")]
    Network(String),
    #[error("Parse error: {0}")]
    Parse(String),
}

#[derive(Serialize)]
struct ChatCompletionRequest {
    model: String,
    messages: Vec<HermesMessage>,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    stream: bool,
}

#[derive(Deserialize)]
struct ChatCompletionResponse {
    choices: Option<Vec<ChatChoice>>,
}

#[derive(Deserialize)]
struct ChatChoice {
    message: Option<HermesMessage>,
}

#[derive(Deserialize)]
struct ChatCompletionChunk {
    choices: Option<Vec<DeltaChoice>>,
}

#[derive(Deserialize)]
struct DeltaChoice {
    delta: Option<ChunkDelta>,
    finish_reason: Option<String>,
}

#[derive(Deserialize)]
struct ChunkDelta {
    role: Option<String>,
    content: Option<String>,
}

#[derive(Deserialize)]
struct ModelsResponse {
    data: Option<Vec<ModelEntry>>,
}

#[derive(Deserialize)]
struct ModelEntry {
    id: String,
}

#[derive(Clone)]
pub struct HermesClient {
    http: Client,
}

impl HermesClient {
    pub fn new() -> Self {
        let http = Client::builder()
            .timeout(std::time::Duration::from_secs(180))
            .connect_timeout(std::time::Duration::from_secs(15))
            .build()
            .expect("failed to build HTTP client");
        Self { http }
    }

    fn require_config(config: &HermesConfig) -> Result<(&str, &str), HermesError> {
        let url = config.base_url.as_deref()
            .filter(|u| !u.is_empty())
            .ok_or_else(|| HermesError::NotConfigured("base URL not set".into()))?;
        let token = config.token.as_deref()
            .filter(|t| !t.is_empty())
            .ok_or_else(|| HermesError::NotConfigured("API token not set".into()))?;
        Ok((url, token))
    }

    fn model(config: &HermesConfig) -> String {
        config.model.as_deref()
            .filter(|m| !m.is_empty())
            .unwrap_or(DEFAULT_MODEL)
            .to_string()
    }

    fn auth_headers(token: &str) -> Vec<(String, String)> {
        vec![
            ("Authorization".into(), format!("Bearer {token}")),
            ("x-litellm-api-key".into(), token.into()),
        ]
    }

    pub async fn probe(&self, url: &str, token: &str) -> Result<Vec<String>, HermesError> {
        let endpoint = format!("{}/v1/models", url.trim_end_matches('/'));
        let resp = self.http
            .get(&endpoint)
            .bearer_auth(token)
            .header("x-litellm-api-key", token)
            .send()
            .await
            .map_err(|e| HermesError::Network(e.to_string()))?;

        let status = resp.status().as_u16();
        if status == 401 || status == 403 {
            return Err(HermesError::Auth("Invalid or expired token".into()));
        }
        if !resp.status().is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(HermesError::Http(status, body));
        }

        let models: ModelsResponse = resp.json().await
            .map_err(|e| HermesError::Parse(e.to_string()))?;

        Ok(models.data.unwrap_or_default().into_iter().map(|m| m.id).collect())
    }

    pub async fn chat(
        &self,
        config: &HermesConfig,
        messages: &[HermesMessage],
    ) -> Result<String, HermesError> {
        let (url, token) = Self::require_config(config)?;
        let model = Self::model(config);
        let endpoint = format!("{}/v1/chat/completions", url.trim_end_matches('/'));

        let req = ChatCompletionRequest {
            model,
            messages: messages.to_vec(),
            stream: false,
        };

        let resp = self.http
            .post(&endpoint)
            .bearer_auth(token)
            .header("x-litellm-api-key", token)
            .json(&req)
            .send()
            .await
            .map_err(|e| HermesError::Network(e.to_string()))?;

        let status = resp.status().as_u16();
        if status == 401 || status == 403 {
            return Err(HermesError::Auth("Token expired or invalid".into()));
        }
        if !resp.status().is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(HermesError::Http(status, body));
        }

        let body: ChatCompletionResponse = resp.json().await
            .map_err(|e| HermesError::Parse(e.to_string()))?;

        body.choices
            .and_then(|c| c.into_iter().next())
            .and_then(|c| c.message)
            .map(|m| m.content)
            .ok_or_else(|| HermesError::Parse("No response content".into()))
    }

    pub async fn chat_stream(
        &self,
        config: &HermesConfig,
        messages: &[HermesMessage],
        event_tx: &broadcast::Sender<HermesResponseEvent>,
    ) -> Result<(), HermesError> {
        let (url, token) = Self::require_config(config)?;
        let model = Self::model(config);
        let endpoint = format!("{}/v1/chat/completions", url.trim_end_matches('/'));

        let req = ChatCompletionRequest {
            model,
            messages: messages.to_vec(),
            stream: true,
        };

        let resp = self.http
            .post(&endpoint)
            .bearer_auth(token)
            .header("x-litellm-api-key", token)
            .json(&req)
            .send()
            .await
            .map_err(|e| HermesError::Network(e.to_string()))?;

        let status = resp.status().as_u16();
        if status == 401 || status == 403 {
            return Err(HermesError::Auth("Token expired or invalid".into()));
        }
        if !resp.status().is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(HermesError::Http(status, body));
        }

        let text = resp.text().await
            .map_err(|e| HermesError::Network(e.to_string()))?;

        let mut accumulated = String::new();
        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with(':') {
                continue;
            }
            if let Some(data) = line.strip_prefix("data: ") {
                if data == "[DONE]" {
                    break;
                }
                if let Ok(chunk) = serde_json::from_str::<ChatCompletionChunk>(data) {
                    if let Some(choices) = chunk.choices {
                        for choice in choices {
                            if let Some(delta) = choice.delta {
                                if let Some(content) = delta.content {
                                    accumulated.push_str(&content);
                                    let _ = event_tx.send(HermesResponseEvent::TextDelta(content));
                                }
                            }
                        }
                    }
                }
            }
        }

        let _ = event_tx.send(HermesResponseEvent::Completed {
            text: accumulated,
            response_id: None,
        });

        Ok(())
    }

    pub fn normalize_url(raw: &str) -> String {
        let mut url = raw.trim().to_string();
        url = url.trim_end_matches('/').to_string();
        if url.ends_with("/v1") {
            url.truncate(url.len() - 3);
        }
        url
    }
}
