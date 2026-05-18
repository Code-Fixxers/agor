use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;

use super::content_block::ContentBlock;
use super::input_request::InputRequestContent;
use super::permission::PermissionRequestContent;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MessageRole {
    User,
    Assistant,
    System,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MessageType {
    User,
    Assistant,
    System,
    FileHistorySnapshot,
    PermissionRequest,
    InputRequest,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MessageTokens {
    pub input: Option<i64>,
    pub output: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MessageMetadata {
    pub model: Option<String>,
    pub tokens: Option<MessageTokens>,
    pub source: Option<String>,
    pub original_id: Option<String>,
    pub parent_id: Option<String>,
    pub is_meta: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ToolUseRef {
    pub id: String,
    pub name: String,
    pub input: serde_json::Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum MessageContent {
    Text(String),
    Blocks(Vec<ContentBlock>),
    Permission(PermissionRequestContent),
    InputRequest(InputRequestContent),
}

impl Serialize for MessageContent {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        match self {
            MessageContent::Text(s) => serializer.serialize_str(s),
            MessageContent::Blocks(blocks) => blocks.serialize(serializer),
            MessageContent::Permission(p) => p.serialize(serializer),
            MessageContent::InputRequest(r) => r.serialize(serializer),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Message {
    pub message_id: String,
    pub session_id: String,
    pub task_id: Option<String>,
    #[serde(rename = "type")]
    pub message_type: MessageType,
    pub role: MessageRole,
    pub index: i64,
    pub timestamp: String,
    pub content_preview: Option<String>,
    pub content: MessageContent,
    pub tool_uses: Option<Vec<ToolUseRef>>,
    pub parent_tool_use_id: Option<String>,
    pub status: Option<String>,
    pub metadata: Option<MessageMetadata>,
}

impl Message {
    pub fn is_permission_request(&self) -> bool {
        matches!(self.message_type, MessageType::PermissionRequest)
    }

    pub fn is_input_request(&self) -> bool {
        matches!(self.message_type, MessageType::InputRequest)
    }
}

impl<'de> Deserialize<'de> for Message {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let v = Value::deserialize(deserializer)?;
        let obj = v
            .as_object()
            .ok_or_else(|| serde::de::Error::custom("expected object"))?;

        let message_type: MessageType = serde_json::from_value(
            obj.get("type")
                .cloned()
                .unwrap_or(Value::String("assistant".into())),
        )
        .unwrap_or(MessageType::Assistant);

        let content = match obj.get("content") {
            Some(Value::String(s)) => MessageContent::Text(s.clone()),
            Some(Value::Array(arr)) => {
                let blocks: Vec<ContentBlock> = arr
                    .iter()
                    .filter_map(|b| serde_json::from_value(b.clone()).ok())
                    .collect();
                MessageContent::Blocks(blocks)
            }
            Some(val) if val.get("request_id").is_some() || val.get("requestId").is_some() => {
                if matches!(message_type, MessageType::InputRequest) {
                    match serde_json::from_value::<InputRequestContent>(val.clone()) {
                        Ok(r) => MessageContent::InputRequest(r),
                        Err(_) => MessageContent::Text(val.to_string()),
                    }
                } else {
                    match serde_json::from_value::<PermissionRequestContent>(val.clone()) {
                        Ok(p) => MessageContent::Permission(p),
                        Err(_) => MessageContent::Text(val.to_string()),
                    }
                }
            }
            Some(val) => MessageContent::Text(val.to_string()),
            None => MessageContent::Text(String::new()),
        };

        Ok(Message {
            message_id: obj
                .get("message_id")
                .or_else(|| obj.get("messageId"))
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            session_id: obj
                .get("session_id")
                .or_else(|| obj.get("sessionId"))
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            task_id: obj
                .get("task_id")
                .or_else(|| obj.get("taskId"))
                .and_then(|v| v.as_str())
                .map(String::from),
            message_type,
            role: serde_json::from_value(
                obj.get("role")
                    .cloned()
                    .unwrap_or(Value::String("assistant".into())),
            )
            .unwrap_or(MessageRole::Assistant),
            index: obj.get("index").and_then(|v| v.as_i64()).unwrap_or(0),
            timestamp: obj
                .get("timestamp")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            content_preview: obj
                .get("content_preview")
                .or_else(|| obj.get("contentPreview"))
                .and_then(|v| v.as_str())
                .map(String::from),
            content,
            tool_uses: obj
                .get("tool_uses")
                .or_else(|| obj.get("toolUses"))
                .and_then(|v| serde_json::from_value(v.clone()).ok()),
            parent_tool_use_id: obj
                .get("parent_tool_use_id")
                .or_else(|| obj.get("parentToolUseId"))
                .and_then(|v| v.as_str())
                .map(String::from),
            status: obj.get("status").and_then(|v| v.as_str()).map(String::from),
            metadata: obj
                .get("metadata")
                .and_then(|v| serde_json::from_value(v.clone()).ok()),
        })
    }
}
