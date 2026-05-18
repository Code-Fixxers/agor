use serde::{Deserialize, Deserializer, Serialize, Serializer};
use serde_json::Value;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ImageSource {
    #[serde(rename = "type")]
    pub source_type: String,
    pub media_type: Option<String>,
    pub data: Option<String>,
    pub url: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum ToolResultValue {
    Str(String),
    Blocks(Vec<ToolResultBlock>),
}

impl ToolResultValue {
    pub fn text_preview(&self) -> String {
        match self {
            ToolResultValue::Str(s) => {
                if s.len() > 200 {
                    format!("{}...", &s[..200])
                } else {
                    s.clone()
                }
            }
            ToolResultValue::Blocks(blocks) => blocks
                .iter()
                .filter_map(|b| b.text.as_deref())
                .collect::<Vec<_>>()
                .join("\n")
                .chars()
                .take(200)
                .collect(),
        }
    }
}

impl Serialize for ToolResultValue {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        match self {
            ToolResultValue::Str(s) => serializer.serialize_str(s),
            ToolResultValue::Blocks(blocks) => blocks.serialize(serializer),
        }
    }
}

impl<'de> Deserialize<'de> for ToolResultValue {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let v = Value::deserialize(deserializer)?;
        match v {
            Value::String(s) => Ok(ToolResultValue::Str(s)),
            Value::Array(arr) => {
                let blocks = arr
                    .into_iter()
                    .filter_map(|b| serde_json::from_value(b).ok())
                    .collect();
                Ok(ToolResultValue::Blocks(blocks))
            }
            _ => Ok(ToolResultValue::Str(v.to_string())),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ToolResultBlock {
    #[serde(rename = "type")]
    pub block_type: Option<String>,
    pub text: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum ContentBlock {
    Text {
        text: String,
    },
    ToolUse {
        tool_use_id: String,
        name: String,
        input: serde_json::Map<String, Value>,
    },
    ToolResult {
        tool_use_id: String,
        content: Option<ToolResultValue>,
        is_error: Option<bool>,
    },
    Thinking {
        thinking: Option<String>,
    },
    Image {
        source: ImageSource,
    },
    Unknown {
        block_type: String,
    },
}

impl ContentBlock {
    pub fn id(&self) -> String {
        match self {
            ContentBlock::Text { text } => format!("text-{}", fxhash(text)),
            ContentBlock::ToolUse { tool_use_id, .. } => format!("tool-{tool_use_id}"),
            ContentBlock::ToolResult { tool_use_id, .. } => format!("result-{tool_use_id}"),
            ContentBlock::Thinking { thinking } => {
                format!("thinking-{}", fxhash(thinking.as_deref().unwrap_or("")))
            }
            ContentBlock::Image { source } => {
                format!(
                    "image-{}",
                    fxhash(source.url.as_deref().unwrap_or("base64"))
                )
            }
            ContentBlock::Unknown { block_type } => format!("unknown-{block_type}"),
        }
    }

    pub fn input_summary(input: &serde_json::Map<String, Value>) -> String {
        let preferred_keys = ["command", "file_path", "path", "pattern", "url", "query"];
        for key in &preferred_keys {
            if let Some(val) = input.get(*key) {
                if let Some(s) = val.as_str() {
                    return truncate(s, 120);
                }
            }
        }
        let json = serde_json::to_string(input).unwrap_or_default();
        truncate(&json, 120)
    }
}

fn fxhash(s: &str) -> u64 {
    let mut hash: u64 = 0;
    for byte in s.bytes() {
        hash = hash.wrapping_mul(0x100000001b3).wrapping_add(byte as u64);
    }
    hash
}

fn truncate(s: &str, max: usize) -> String {
    if s.len() <= max {
        s.to_string()
    } else {
        format!("{}...", &s[..max])
    }
}

impl Serialize for ContentBlock {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeMap;
        let mut map = serializer.serialize_map(None)?;
        match self {
            ContentBlock::Text { text } => {
                map.serialize_entry("type", "text")?;
                map.serialize_entry("text", text)?;
            }
            ContentBlock::ToolUse {
                tool_use_id,
                name,
                input,
            } => {
                map.serialize_entry("type", "tool_use")?;
                map.serialize_entry("id", tool_use_id)?;
                map.serialize_entry("name", name)?;
                map.serialize_entry("input", input)?;
            }
            ContentBlock::ToolResult {
                tool_use_id,
                content,
                is_error,
            } => {
                map.serialize_entry("type", "tool_result")?;
                map.serialize_entry("tool_use_id", tool_use_id)?;
                if let Some(c) = content {
                    map.serialize_entry("content", c)?;
                }
                if let Some(e) = is_error {
                    map.serialize_entry("is_error", e)?;
                }
            }
            ContentBlock::Thinking { thinking } => {
                map.serialize_entry("type", "thinking")?;
                if let Some(t) = thinking {
                    map.serialize_entry("thinking", t)?;
                }
            }
            ContentBlock::Image { source } => {
                map.serialize_entry("type", "image")?;
                map.serialize_entry("source", source)?;
            }
            ContentBlock::Unknown { block_type } => {
                map.serialize_entry("type", block_type)?;
            }
        }
        map.end()
    }
}

impl<'de> Deserialize<'de> for ContentBlock {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let v = Value::deserialize(deserializer)?;
        let obj = v
            .as_object()
            .ok_or_else(|| serde::de::Error::custom("expected object"))?;

        let block_type = obj
            .get("type")
            .and_then(|t| t.as_str())
            .unwrap_or("unknown");

        match block_type {
            "text" => Ok(ContentBlock::Text {
                text: obj
                    .get("text")
                    .and_then(|t| t.as_str())
                    .unwrap_or("")
                    .to_string(),
            }),
            "tool_use" => Ok(ContentBlock::ToolUse {
                tool_use_id: obj
                    .get("id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                name: obj
                    .get("name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                input: obj
                    .get("input")
                    .and_then(|v| v.as_object())
                    .cloned()
                    .unwrap_or_default(),
            }),
            "tool_result" => Ok(ContentBlock::ToolResult {
                tool_use_id: obj
                    .get("tool_use_id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                content: obj
                    .get("content")
                    .and_then(|v| serde_json::from_value(v.clone()).ok()),
                is_error: obj.get("is_error").and_then(|v| v.as_bool()),
            }),
            "thinking" => Ok(ContentBlock::Thinking {
                thinking: obj
                    .get("thinking")
                    .and_then(|v| v.as_str())
                    .map(String::from),
            }),
            "image" => Ok(ContentBlock::Image {
                source: obj
                    .get("source")
                    .and_then(|v| serde_json::from_value(v.clone()).ok())
                    .unwrap_or(ImageSource {
                        source_type: "base64".into(),
                        media_type: None,
                        data: None,
                        url: None,
                    }),
            }),
            other => Ok(ContentBlock::Unknown {
                block_type: other.to_string(),
            }),
        }
    }
}
