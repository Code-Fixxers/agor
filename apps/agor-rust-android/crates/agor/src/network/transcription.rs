use serde_json::Value;
use std::collections::BTreeSet;
use thiserror::Error;

pub const DEFAULT_REMOTE_WHISPER_URL: &str = "http://100.101.157.56:8091";
pub const DEFAULT_REMOTE_WHISPER_MODEL: &str = "base.en";
pub const DEFAULT_BASE_EN_MODEL_ARTIFACT_URL: &str =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TranscriptionConfig {
    pub base_url: String,
    pub model: String,
    pub local_model_path: Option<String>,
    pub local_model_url: Option<String>,
}

impl Default for TranscriptionConfig {
    fn default() -> Self {
        Self {
            base_url: DEFAULT_REMOTE_WHISPER_URL.to_string(),
            model: DEFAULT_REMOTE_WHISPER_MODEL.to_string(),
            local_model_path: None,
            local_model_url: None,
        }
    }
}

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum TranscriptionError {
    #[error("Transcript was empty")]
    EmptyTranscript,
    #[error("Voice recording is only available in the browser/mobile WebView right now")]
    UnsupportedPlatform,
    #[error("{0}")]
    Capture(String),
}

pub fn default_transcription_config() -> TranscriptionConfig {
    #[cfg(target_arch = "wasm32")]
    {
        let mut config = TranscriptionConfig::default();
        apply_transcription_query_config(&mut config);
        return config;
    }

    #[cfg(not(target_arch = "wasm32"))]
    {
        TranscriptionConfig::default()
    }
}

pub fn transcription_endpoint(base_url: &str) -> String {
    let base = base_url.trim().trim_end_matches('/');
    if base.ends_with("/v1/audio/transcriptions") || base.ends_with("/inference") {
        base.to_string()
    } else {
        format!("{base}/v1/audio/transcriptions")
    }
}

fn whisper_model_discovery_endpoints(base_url: &str) -> Vec<String> {
    let mut base = base_url.trim().trim_end_matches('/').to_string();
    for suffix in [
        "/v1/audio/transcriptions",
        "/inference",
        "/v1/models",
        "/models",
    ] {
        if let Some(stripped) = base.strip_suffix(suffix) {
            base = stripped.trim_end_matches('/').to_string();
            break;
        }
    }

    if base.is_empty() {
        return vec![];
    }

    vec![format!("{base}/v1/models"), format!("{base}/models")]
}

pub fn parse_whisper_models_response(body: &str) -> Vec<String> {
    let mut models = BTreeSet::new();
    if let Ok(value) = serde_json::from_str::<Value>(body.trim()) {
        collect_whisper_models(&value, &mut models);
    }

    if models.is_empty() {
        vec!["default".to_string()]
    } else {
        models.into_iter().collect()
    }
}

fn collect_whisper_models(value: &Value, models: &mut BTreeSet<String>) {
    match value {
        Value::String(model) => {
            let model = model.trim();
            if model.to_ascii_lowercase().starts_with("whisper") {
                models.insert(model.to_string());
            }
        }
        Value::Array(items) => {
            for item in items {
                collect_whisper_models(item, models);
            }
        }
        Value::Object(object) => {
            for key in ["id", "name", "model"] {
                if let Some(value) = object.get(key) {
                    collect_whisper_models(value, models);
                }
            }
            for key in ["data", "models"] {
                if let Some(value) = object.get(key) {
                    collect_whisper_models(value, models);
                }
            }
        }
        _ => {}
    }
}

pub async fn discover_whisper_models(base_url: &str) -> Result<Vec<String>, TranscriptionError> {
    let endpoints = whisper_model_discovery_endpoints(base_url);
    if endpoints.is_empty() {
        return Ok(vec!["default".to_string()]);
    }

    let client = reqwest::Client::new();
    let mut last_error = None;
    for endpoint in endpoints {
        match client
            .get(&endpoint)
            .header("Accept", "application/json")
            .send()
            .await
        {
            Ok(response) => {
                let status = response.status();
                let body = response.text().await.unwrap_or_default();
                if status.is_success() {
                    return Ok(parse_whisper_models_response(&body));
                }
                last_error = Some(format!("Whisper model discovery {status}: {}", body.trim()));
            }
            Err(error) => {
                last_error = Some(format!(
                    "Whisper model discovery failed for {endpoint}: {error}"
                ));
            }
        }
    }

    Err(TranscriptionError::Capture(last_error.unwrap_or_else(
        || "Whisper model discovery failed".to_string(),
    )))
}

pub fn parse_transcription_response(body: &str) -> Result<String, TranscriptionError> {
    let trimmed = body.trim();
    if trimmed.is_empty() {
        return Err(TranscriptionError::EmptyTranscript);
    }

    let text = match serde_json::from_str::<Value>(trimmed) {
        Ok(value) => extract_json_text(value).unwrap_or_default(),
        Err(_) => trimmed.to_string(),
    };

    clean_transcript(&text)
}

pub fn merge_transcript_into_draft(draft: &str, transcript: &str) -> String {
    let draft = draft.trim_end();
    let transcript = transcript.trim();
    if draft.is_empty() {
        transcript.to_string()
    } else if transcript.is_empty() {
        draft.to_string()
    } else {
        format!("{draft} {transcript}")
    }
}

fn extract_json_text(value: Value) -> Option<String> {
    match value {
        Value::String(text) => Some(text),
        Value::Object(obj) => {
            for key in ["text", "transcription", "result"] {
                if let Some(text) = obj.get(key).and_then(Value::as_str) {
                    if !text.trim().is_empty() {
                        return Some(text.to_string());
                    }
                }
            }

            let segments = obj.get("segments")?.as_array()?;
            let text = segments
                .iter()
                .filter_map(|segment| segment.get("text").and_then(Value::as_str))
                .map(str::trim)
                .filter(|text| !text.is_empty())
                .collect::<Vec<_>>()
                .join(" ");
            if text.is_empty() {
                None
            } else {
                Some(text)
            }
        }
        _ => None,
    }
}

fn clean_transcript(raw: &str) -> Result<String, TranscriptionError> {
    let mut out = String::with_capacity(raw.len());
    let mut chars = raw.chars().peekable();
    while let Some(ch) = chars.next() {
        if ch == '<' && chars.peek() == Some(&'|') {
            while let Some(next) = chars.next() {
                if next == '>' {
                    break;
                }
            }
            out.push(' ');
            continue;
        }

        out.push(ch);
    }

    let cleaned = out.split_whitespace().collect::<Vec<_>>().join(" ");
    if cleaned.is_empty() {
        Err(TranscriptionError::EmptyTranscript)
    } else {
        Ok(cleaned)
    }
}

#[cfg(target_arch = "wasm32")]
pub async fn start_voice_recording() -> Result<(), TranscriptionError> {
    wasm_start_voice_recording()
        .await
        .map(|_| ())
        .map_err(js_error)
}

#[cfg(not(target_arch = "wasm32"))]
pub async fn start_voice_recording() -> Result<(), TranscriptionError> {
    Err(TranscriptionError::UnsupportedPlatform)
}

#[cfg(target_arch = "wasm32")]
pub async fn stop_voice_recording_and_transcribe(
    config: &TranscriptionConfig,
) -> Result<String, TranscriptionError> {
    let endpoint = transcription_endpoint(&config.base_url);
    let body = wasm_stop_voice_recording_and_transcribe(
        &endpoint,
        &config.model,
        config.local_model_path.as_deref().unwrap_or_default(),
        config.local_model_url.as_deref().unwrap_or_default(),
    )
    .await
    .map_err(js_error)?
    .as_string()
    .unwrap_or_default();

    parse_transcription_response(&body)
}

#[cfg(not(target_arch = "wasm32"))]
pub async fn stop_voice_recording_and_transcribe(
    _config: &TranscriptionConfig,
) -> Result<String, TranscriptionError> {
    Err(TranscriptionError::UnsupportedPlatform)
}

#[cfg(target_arch = "wasm32")]
pub fn cancel_voice_recording() {
    wasm_cancel_voice_recording();
}

#[cfg(not(target_arch = "wasm32"))]
pub fn cancel_voice_recording() {}

#[cfg(target_arch = "wasm32")]
fn js_error(value: wasm_bindgen::JsValue) -> TranscriptionError {
    let message = value
        .as_string()
        .unwrap_or_else(|| "Voice capture failed".to_string());
    TranscriptionError::Capture(message)
}

#[cfg(target_arch = "wasm32")]
fn apply_transcription_query_config(config: &mut TranscriptionConfig) {
    let Some(search) = web_sys::window().and_then(|window| window.location().search().ok()) else {
        return;
    };
    let query = search.strip_prefix('?').unwrap_or(&search);
    for (key, value) in url::form_urlencoded::parse(query.as_bytes()) {
        if value.is_empty() {
            continue;
        }

        match key.as_ref() {
            "agor_whisper_url" => config.base_url = value.into_owned(),
            "agor_whisper_model" => config.model = value.into_owned(),
            "agor_whisper_model_path" => config.local_model_path = Some(value.into_owned()),
            "agor_whisper_model_url" => config.local_model_url = Some(value.into_owned()),
            _ => {}
        }
    }
}

#[cfg(target_arch = "wasm32")]
use wasm_bindgen::prelude::*;

#[cfg(target_arch = "wasm32")]
#[wasm_bindgen(inline_js = r#"
let agorVoiceRecorder = null;
let agorVoiceChunks = [];
let agorVoiceStream = null;

function stopTracks() {
  if (agorVoiceStream) {
    for (const track of agorVoiceStream.getTracks()) {
      track.stop();
    }
  }
  agorVoiceStream = null;
}

function recorderOptions() {
  if (typeof MediaRecorder === "undefined") {
    return {};
  }
  const preferred = [
    "audio/webm;codecs=opus",
    "audio/webm",
    "audio/mp4",
  ];
  for (const mimeType of preferred) {
    if (MediaRecorder.isTypeSupported(mimeType)) {
      return { mimeType };
    }
  }
  return {};
}

async function postWhisper(url, blob, model) {
  const form = new FormData();
  form.append("file", blob, blob.type && blob.type.includes("mp4") ? "voice.m4a" : "voice.webm");
  form.append("temperature", "0.0");
  form.append("response_format", "json");
  form.append("model", model);

  let response;
  try {
    response = await fetch(url, {
      method: "POST",
      body: form,
      headers: { "Accept": "application/json" },
    });
  } catch (error) {
    throw new Error(`Whisper request failed for ${url}: ${error?.message || String(error)}`);
  }
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`Whisper ${response.status}: ${body.slice(0, 300)}`);
  }
  return body;
}

async function tryLocalBridge(blob, modelPath, modelUrl) {
  if (!modelPath && !modelUrl) {
    return null;
  }

  const bridge = globalThis.AgorWhisper;
  if (!bridge) {
    return null;
  }

  let resolvedModelPath = modelPath || "";
  if (!resolvedModelPath && modelUrl && typeof bridge.ensureModel === "function") {
    resolvedModelPath = await bridge.ensureModel(modelUrl);
  } else if (!resolvedModelPath && modelUrl) {
    resolvedModelPath = modelUrl;
  }

  const transcribe = typeof bridge.transcribe === "function"
    ? bridge.transcribe
    : bridge.transcribeBaseEn;
  if (typeof transcribe !== "function") {
    return null;
  }

  const buffer = await blob.arrayBuffer();
  return await transcribe(new Uint8Array(buffer), resolvedModelPath, modelUrl || "");
}

export async function wasm_start_voice_recording() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    throw new Error("Microphone capture is not available in this browser");
  }
  if (typeof MediaRecorder === "undefined") {
    throw new Error("MediaRecorder is not available in this browser");
  }
  if (agorVoiceRecorder && agorVoiceRecorder.state === "recording") {
    return true;
  }

  agorVoiceStream = await navigator.mediaDevices.getUserMedia({ audio: true });
  agorVoiceChunks = [];
  agorVoiceRecorder = new MediaRecorder(agorVoiceStream, recorderOptions());
  agorVoiceRecorder.addEventListener("dataavailable", (event) => {
    if (event.data && event.data.size > 0) {
      agorVoiceChunks.push(event.data);
    }
  });
  agorVoiceRecorder.start();
  return true;
}

export async function wasm_stop_voice_recording_and_transcribe(endpoint, model, modelPath, modelUrl) {
  if (!agorVoiceRecorder || agorVoiceRecorder.state !== "recording") {
    throw new Error("No active voice recording");
  }

  const recorder = agorVoiceRecorder;
  const mimeType = recorder.mimeType || "audio/webm";
  const blob = await new Promise((resolve, reject) => {
    recorder.addEventListener("stop", () => {
      resolve(new Blob(agorVoiceChunks, { type: mimeType }));
    }, { once: true });
    recorder.addEventListener("error", (event) => {
      reject(new Error(event.error?.message || "Voice recording failed"));
    }, { once: true });
    recorder.stop();
    stopTracks();
  });
  agorVoiceRecorder = null;

  if (!blob.size) {
    throw new Error("Recorded audio was empty");
  }

  try {
    return await postWhisper(endpoint, blob, model);
  } catch (remoteError) {
    const local = await tryLocalBridge(blob, modelPath, modelUrl);
    if (local !== null && local !== undefined) {
      return typeof local === "string" ? local : JSON.stringify(local);
    }

    if (endpoint.endsWith("/v1/audio/transcriptions")) {
      const fallbackEndpoint = endpoint.replace(/\/v1\/audio\/transcriptions$/, "/inference");
      return await postWhisper(fallbackEndpoint, blob, model);
    }

    throw remoteError;
  }
}

export function wasm_cancel_voice_recording() {
  if (agorVoiceRecorder && agorVoiceRecorder.state === "recording") {
    agorVoiceRecorder.stop();
  }
  agorVoiceRecorder = null;
  agorVoiceChunks = [];
  stopTracks();
}
"#)]
extern "C" {
    #[wasm_bindgen(catch)]
    async fn wasm_start_voice_recording() -> Result<wasm_bindgen::JsValue, wasm_bindgen::JsValue>;

    #[wasm_bindgen(catch)]
    async fn wasm_stop_voice_recording_and_transcribe(
        endpoint: &str,
        model: &str,
        model_path: &str,
        model_url: &str,
    ) -> Result<wasm_bindgen::JsValue, wasm_bindgen::JsValue>;

    fn wasm_cancel_voice_recording();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_openai_compatible_json_text() {
        let parsed = parse_transcription_response(r#"{"text":" Ship it today.\n"}"#).unwrap();
        assert_eq!(parsed, "Ship it today.");
    }

    #[test]
    fn parses_plain_text_response() {
        let parsed = parse_transcription_response("  continue the session  ").unwrap();
        assert_eq!(parsed, "continue the session");
    }

    #[test]
    fn parses_segment_response() {
        let parsed = parse_transcription_response(
            r#"{"segments":[{"text":"start the"},{"text":"test run"}]}"#,
        )
        .unwrap();
        assert_eq!(parsed, "start the test run");
    }

    #[test]
    fn rejects_empty_transcripts() {
        let err = parse_transcription_response(r#"{"text":"   "}"#).unwrap_err();
        assert!(err.to_string().contains("empty"));
    }

    #[test]
    fn appends_transcript_to_existing_draft() {
        let merged = merge_transcript_into_draft("Review this", "and explain the failure.");
        assert_eq!(merged, "Review this and explain the failure.");
    }

    #[test]
    fn keeps_empty_draft_clean() {
        let merged = merge_transcript_into_draft("", "  Open the latest session. ");
        assert_eq!(merged, "Open the latest session.");
    }

    #[test]
    fn builds_openai_compatible_endpoint_from_base_url() {
        assert_eq!(
            transcription_endpoint("http://100.101.157.56:8091/"),
            "http://100.101.157.56:8091/v1/audio/transcriptions"
        );
    }

    #[test]
    fn parses_openai_model_list_for_whisper_models() {
        let models = parse_whisper_models_response(
            r#"{"data":[{"id":"gpt-4.1"},{"id":"whisper-base.en"},{"id":"whisper-tiny"}]}"#,
        );
        assert_eq!(models, vec!["whisper-base.en", "whisper-tiny"]);
    }

    #[test]
    fn parses_simple_model_arrays_and_deduplicates() {
        let models = parse_whisper_models_response(
            r#"["whisper-base.en","llama","whisper-base.en","whisper-large-v3"]"#,
        );
        assert_eq!(models, vec!["whisper-base.en", "whisper-large-v3"]);
    }

    #[test]
    fn falls_back_to_default_when_no_whisper_models_are_returned() {
        let models = parse_whisper_models_response(r#"{"models":["base.en","tiny.en"]}"#);
        assert_eq!(models, vec!["default"]);
    }
}
