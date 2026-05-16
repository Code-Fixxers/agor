use std::path::PathBuf;
use std::sync::Mutex;
use tokio::sync::broadcast;
use serde::{Deserialize, Serialize};

use crate::models::{
    HermesAttachment, HermesProgressItem, HermesSession, HermesSessionEvent, HermesTurn,
};

#[derive(Serialize, Deserialize)]
struct SessionIndex {
    sessions: Vec<HermesSession>,
}

pub struct HermesSessionStore {
    root: PathBuf,
    sessions: Mutex<Vec<HermesSession>>,
    loaded_key: Mutex<Option<String>>,
    event_tx: broadcast::Sender<HermesSessionEvent>,
}

impl HermesSessionStore {
    pub fn new() -> Self {
        let root = dirs::data_local_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("agor-android")
            .join("hermes_sessions");
        let _ = std::fs::create_dir_all(&root);
        let (event_tx, _) = broadcast::channel(64);
        Self {
            root,
            sessions: Mutex::new(Vec::new()),
            loaded_key: Mutex::new(None),
            event_tx,
        }
    }

    pub fn subscribe(&self) -> broadcast::Receiver<HermesSessionEvent> {
        self.event_tx.subscribe()
    }

    fn scope_file(&self, scope_key: &str) -> PathBuf {
        use sha2::{Digest, Sha256};
        let hash = hex::encode(Sha256::digest(scope_key.as_bytes()));
        self.root.join(format!("{hash}.json"))
    }

    pub fn load(&self, scope_key: &str) -> Vec<HermesSession> {
        let mut loaded = self.loaded_key.lock().unwrap();
        if loaded.as_deref() == Some(scope_key) {
            return self.sessions.lock().unwrap().clone();
        }

        let path = self.scope_file(scope_key);
        let sessions = if path.exists() {
            match std::fs::read_to_string(&path) {
                Ok(data) => serde_json::from_str::<SessionIndex>(&data)
                    .map(|idx| idx.sessions)
                    .unwrap_or_default(),
                Err(_) => Vec::new(),
            }
        } else {
            Vec::new()
        };

        *self.sessions.lock().unwrap() = sessions.clone();
        *loaded = Some(scope_key.to_string());
        sessions
    }

    fn save(&self) {
        let loaded = self.loaded_key.lock().unwrap();
        if let Some(key) = loaded.as_deref() {
            let sessions = self.sessions.lock().unwrap().clone();
            let index = SessionIndex { sessions };
            let path = self.scope_file(key);
            let tmp = path.with_extension("tmp");
            if let Ok(data) = serde_json::to_string_pretty(&index) {
                let _ = std::fs::write(&tmp, data);
                let _ = std::fs::rename(&tmp, &path);
            }
        }
    }

    pub fn sessions(&self) -> Vec<HermesSession> {
        self.sessions.lock().unwrap().clone()
    }

    pub fn get_session(&self, id: &str) -> Option<HermesSession> {
        self.sessions.lock().unwrap().iter().find(|s| s.id == id).cloned()
    }

    pub fn create_session(&self, title_seed: Option<&str>) -> HermesSession {
        let now = chrono::Utc::now().timestamp_millis();
        let id = uuid::Uuid::new_v4().to_string();
        let title = title_seed
            .map(|s| if s.len() > 48 { format!("{}...", &s[..45]) } else { s.to_string() })
            .unwrap_or_else(|| "Hermes session".to_string());

        let session = HermesSession {
            id: id.clone(),
            conversation_id: id,
            title,
            created_at_millis: now,
            updated_at_millis: now,
            active: false,
            last_response_id: None,
            error_message: None,
            turns: Vec::new(),
        };

        let mut sessions = self.sessions.lock().unwrap();
        sessions.insert(0, session.clone());
        drop(sessions);
        self.save();
        session
    }

    pub fn delete_session(&self, id: &str) {
        let mut sessions = self.sessions.lock().unwrap();
        sessions.retain(|s| s.id != id);
        drop(sessions);
        self.save();
    }

    pub fn begin_turn(
        &self,
        session_id: &str,
        prompt: &str,
        attachments: Vec<HermesAttachment>,
    ) -> Option<String> {
        let now = chrono::Utc::now().timestamp_millis();
        let user_turn_id = uuid::Uuid::new_v4().to_string();
        let assistant_turn_id = uuid::Uuid::new_v4().to_string();

        let user_turn = HermesTurn {
            id: user_turn_id,
            role: "user".into(),
            content: prompt.to_string(),
            created_at_millis: now,
            streaming: false,
            attachments,
            progress: Vec::new(),
        };

        let assistant_turn = HermesTurn {
            id: assistant_turn_id.clone(),
            role: "assistant".into(),
            content: String::new(),
            created_at_millis: now,
            streaming: true,
            attachments: Vec::new(),
            progress: Vec::new(),
        };

        let mut sessions = self.sessions.lock().unwrap();
        if let Some(session) = sessions.iter_mut().find(|s| s.id == session_id) {
            if session.turns.is_empty() {
                session.title = if prompt.len() > 48 {
                    format!("{}...", &prompt[..45])
                } else {
                    prompt.to_string()
                };
            }
            session.active = true;
            session.error_message = None;
            session.updated_at_millis = now;
            session.turns.push(user_turn);
            session.turns.push(assistant_turn);
            drop(sessions);
            self.save();
            Some(assistant_turn_id)
        } else {
            None
        }
    }

    pub fn append_assistant_delta(
        &self,
        session_id: &str,
        turn_id: &str,
        delta: &str,
        replace_existing: bool,
        emit_text_event: bool,
    ) {
        let mut sessions = self.sessions.lock().unwrap();
        if let Some(session) = sessions.iter_mut().find(|s| s.id == session_id) {
            if let Some(turn) = session.turns.iter_mut().find(|t| t.id == turn_id) {
                if replace_existing {
                    turn.content = delta.to_string();
                } else {
                    turn.content.push_str(delta);
                }
                turn.streaming = true;
                session.updated_at_millis = chrono::Utc::now().timestamp_millis();
            }
        }
        drop(sessions);

        if emit_text_event {
            let _ = self.event_tx.send(HermesSessionEvent::TextDelta {
                session_id: session_id.to_string(),
                turn_id: turn_id.to_string(),
                text: delta.to_string(),
            });
        }
    }

    pub fn append_progress(&self, session_id: &str, turn_id: &str, label: &str) {
        let now = chrono::Utc::now().timestamp_millis();
        let mut sessions = self.sessions.lock().unwrap();
        if let Some(session) = sessions.iter_mut().find(|s| s.id == session_id) {
            if let Some(turn) = session.turns.iter_mut().find(|t| t.id == turn_id) {
                if turn.progress.last().map_or(true, |p| p.label != label) {
                    turn.progress.push(HermesProgressItem {
                        label: label.to_string(),
                        at: now,
                    });
                }
            }
        }
        drop(sessions);

        let _ = self.event_tx.send(HermesSessionEvent::Progress {
            session_id: session_id.to_string(),
            turn_id: turn_id.to_string(),
            label: label.to_string(),
        });
    }

    pub fn complete_assistant(
        &self,
        session_id: &str,
        turn_id: &str,
        response_id: Option<&str>,
        final_text: Option<&str>,
    ) {
        let now = chrono::Utc::now().timestamp_millis();
        let mut sessions = self.sessions.lock().unwrap();
        let completed_text = if let Some(session) = sessions.iter_mut().find(|s| s.id == session_id) {
            if let Some(turn) = session.turns.iter_mut().find(|t| t.id == turn_id) {
                if let Some(ft) = final_text {
                    turn.content = ft.to_string();
                }
                turn.streaming = false;
            }
            session.active = false;
            session.last_response_id = response_id.map(|s| s.to_string());
            session.updated_at_millis = now;
            session.turns.iter().find(|t| t.id == turn_id).map(|t| t.content.clone())
        } else {
            None
        };
        drop(sessions);
        self.save();

        let _ = self.event_tx.send(HermesSessionEvent::Completed {
            session_id: session_id.to_string(),
            turn_id: turn_id.to_string(),
            text: completed_text.unwrap_or_default(),
        });
    }

    pub fn fail_assistant(&self, session_id: &str, turn_id: &str, message: &str) {
        let mut sessions = self.sessions.lock().unwrap();
        if let Some(session) = sessions.iter_mut().find(|s| s.id == session_id) {
            if let Some(turn) = session.turns.iter_mut().find(|t| t.id == turn_id) {
                turn.streaming = false;
            }
            session.active = false;
            session.error_message = Some(message.to_string());
        }
        drop(sessions);
        self.save();

        let _ = self.event_tx.send(HermesSessionEvent::Failed {
            session_id: session_id.to_string(),
            turn_id: turn_id.to_string(),
            message: message.to_string(),
        });
    }

    pub fn cancel_session(&self, session_id: &str) {
        let mut sessions = self.sessions.lock().unwrap();
        if let Some(session) = sessions.iter_mut().find(|s| s.id == session_id) {
            session.active = false;
            session.error_message = Some("Hermes run cancelled".into());
            for turn in &mut session.turns {
                turn.streaming = false;
            }
        }
        drop(sessions);
        self.save();
    }
}

mod hex {
    pub fn encode(bytes: impl AsRef<[u8]>) -> String {
        bytes.as_ref().iter().map(|b| format!("{b:02x}")).collect()
    }
}
