use std::collections::HashMap;
use std::sync::{Arc, RwLock};
use tokio::sync::broadcast;

use crate::models::{SessionStatus, StreamingChunkEvent, StreamingEndEvent, StreamingStartEvent};
use crate::network::socket_service::{SocketEvent, SocketService};
use agor_shared::logger::{AppLogger, LogCategory};

#[derive(Debug, Clone, PartialEq)]
pub struct StreamSnapshot {
    pub text: String,
    pub thinking: String,
    pub finished: bool,
    pub session_id: String,
    pub task_id: Option<String>,
}

impl Default for StreamSnapshot {
    fn default() -> Self {
        Self {
            text: String::new(),
            thinking: String::new(),
            finished: false,
            session_id: String::new(),
            task_id: None,
        }
    }
}

#[derive(Clone)]
pub struct StreamingService {
    streams: Arc<RwLock<HashMap<String, StreamSnapshot>>>,
    logger: AppLogger,
    change_tx: broadcast::Sender<()>,
}

impl StreamingService {
    pub fn new(socket: &SocketService, logger: AppLogger) -> Self {
        let (change_tx, _) = broadcast::channel(64);
        let service = Self {
            streams: Arc::new(RwLock::new(HashMap::new())),
            logger,
            change_tx,
        };

        let svc = service.clone();
        let mut rx = socket.subscribe();
        tokio::spawn(async move {
            loop {
                match rx.recv().await {
                    Ok(event) => svc.handle_event(event),
                    Err(broadcast::error::RecvError::Lagged(n)) => {
                        svc.logger.debug(
                            LogCategory::Chat,
                            format!("Streaming listener lagged {n} events"),
                        );
                    }
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
        });

        service
    }

    pub fn subscribe_changes(&self) -> broadcast::Receiver<()> {
        self.change_tx.subscribe()
    }

    pub fn snapshot(&self) -> HashMap<String, StreamSnapshot> {
        self.streams.read().unwrap().clone()
    }

    pub fn get(&self, message_id: &str) -> Option<StreamSnapshot> {
        self.streams.read().unwrap().get(message_id).cloned()
    }

    pub fn finalize(&self, message_id: &str) {
        self.streams.write().unwrap().remove(message_id);
        let _ = self.change_tx.send(());
    }

    pub fn clear_session_streams(&self, session_id: &str) {
        let mut streams = self.streams.write().unwrap();
        streams.retain(|_, snap| snap.session_id != session_id);
        let _ = self.change_tx.send(());
    }

    fn handle_event(&self, event: SocketEvent) {
        match event {
            SocketEvent::StreamingStart(e) => self.on_streaming_start(e),
            SocketEvent::StreamingChunk(e) => self.on_streaming_chunk(e),
            SocketEvent::StreamingEnd(e) => self.on_streaming_end(e),
            SocketEvent::StreamingError(e) => {
                self.logger.error(
                    LogCategory::Chat,
                    format!("Stream error for {}: {}", e.session_id, e.error),
                );
                self.clear_session_streams(&e.session_id);
            }
            SocketEvent::ThinkingStart(e) => {
                let msg_id = e
                    .message_id
                    .unwrap_or_else(|| format!("thinking-{}", e.session_id));
                let mut streams = self.streams.write().unwrap();
                let snap = streams.entry(msg_id).or_insert_with(|| StreamSnapshot {
                    session_id: e.session_id,
                    task_id: e.task_id,
                    ..Default::default()
                });
                snap.thinking.clear();
                let _ = self.change_tx.send(());
            }
            SocketEvent::ThinkingChunk(e) => {
                let msg_id = e
                    .message_id
                    .unwrap_or_else(|| format!("thinking-{}", e.session_id));
                let mut streams = self.streams.write().unwrap();
                if let Some(snap) = streams.get_mut(&msg_id) {
                    snap.thinking.push_str(&e.text);
                    let _ = self.change_tx.send(());
                }
            }
            SocketEvent::ThinkingEnd(e) => {
                let _ = self.change_tx.send(());
                let _ = e;
            }
            SocketEvent::MessageCreated(msg) => {
                self.finalize(&msg.message_id);
            }
            _ => {}
        }
    }

    fn on_streaming_start(&self, e: StreamingStartEvent) {
        let msg_id = e
            .message_id
            .unwrap_or_else(|| format!("stream-{}", e.session_id));

        let mut streams = self.streams.write().unwrap();
        streams.insert(
            msg_id,
            StreamSnapshot {
                text: String::new(),
                thinking: String::new(),
                finished: false,
                session_id: e.session_id,
                task_id: e.task_id,
            },
        );
        let _ = self.change_tx.send(());
    }

    fn on_streaming_chunk(&self, e: StreamingChunkEvent) {
        let msg_id = e
            .message_id
            .unwrap_or_else(|| format!("stream-{}", e.session_id));

        let mut streams = self.streams.write().unwrap();
        if let Some(snap) = streams.get_mut(&msg_id) {
            snap.text.push_str(&e.text);
            let _ = self.change_tx.send(());
        }
    }

    fn on_streaming_end(&self, e: StreamingEndEvent) {
        let msg_id = e
            .message_id
            .unwrap_or_else(|| format!("stream-{}", e.session_id));

        let mut streams = self.streams.write().unwrap();
        if let Some(snap) = streams.get_mut(&msg_id) {
            if let Some(final_text) = &e.final_text {
                snap.text = final_text.clone();
            }
            snap.finished = true;
            let _ = self.change_tx.send(());
        }
    }
}

pub fn should_clear_streams_for_session_status(status: &SessionStatus) -> bool {
    matches!(
        status,
        SessionStatus::Idle
            | SessionStatus::Completed
            | SessionStatus::Failed
            | SessionStatus::TimedOut
    )
}
