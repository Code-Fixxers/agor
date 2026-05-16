use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;
use std::collections::HashMap;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum InputRequestKind {
    FreeText,
    SingleChoice,
    MultiChoice,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum InputRequestStatus {
    Pending,
    Answered,
    Cancelled,
    TimedOut,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct InputRequestOption {
    pub label: String,
    pub description: String,
    pub markdown: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct InputRequestQuestion {
    pub question: String,
    pub header: Option<String>,
    pub kind: InputRequestKind,
    pub options: Option<Vec<InputRequestOption>>,
    pub multi_select: Option<bool>,
}

impl Serialize for InputRequestQuestion {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeMap;
        let mut map = serializer.serialize_map(None)?;
        map.serialize_entry("question", &self.question)?;
        if let Some(h) = &self.header {
            map.serialize_entry("header", h)?;
        }
        map.serialize_entry("kind", &self.kind)?;
        if let Some(o) = &self.options {
            map.serialize_entry("options", o)?;
        }
        if let Some(m) = &self.multi_select {
            map.serialize_entry("multiSelect", m)?;
        }
        map.end()
    }
}

impl<'de> Deserialize<'de> for InputRequestQuestion {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let v = Value::deserialize(deserializer)?;
        let obj = v.as_object().ok_or_else(|| serde::de::Error::custom("expected object"))?;

        let multi_select = obj
            .get("multiSelect")
            .or_else(|| obj.get("multi_select"))
            .and_then(|v| v.as_bool());

        let kind = if let Some(k) = obj.get("kind") {
            serde_json::from_value(k.clone()).unwrap_or(InputRequestKind::FreeText)
        } else if multi_select.unwrap_or(false) {
            InputRequestKind::MultiChoice
        } else if obj.get("options").map_or(false, |o| o.is_array()) {
            InputRequestKind::SingleChoice
        } else {
            InputRequestKind::FreeText
        };

        Ok(InputRequestQuestion {
            question: obj
                .get("question")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            header: obj.get("header").and_then(|v| v.as_str()).map(String::from),
            kind,
            options: obj
                .get("options")
                .and_then(|v| serde_json::from_value(v.clone()).ok()),
            multi_select,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct InputRequestContent {
    pub request_id: String,
    pub legacy_input_request_id: Option<String>,
    pub task_id: Option<String>,
    pub questions: Vec<InputRequestQuestion>,
    pub status: InputRequestStatus,
    pub answers: Option<HashMap<String, String>>,
    pub answered_at: Option<String>,
    pub requested_at: Option<String>,
    pub context: Option<String>,
}

impl InputRequestContent {
    pub fn input_request_id(&self) -> &str {
        self.legacy_input_request_id
            .as_deref()
            .unwrap_or(&self.request_id)
    }
}
