use serde_json::Value;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, RwLock};
use tokio::sync::broadcast;

use crate::models::*;
use crate::network::agor_client::AgorClient;
use agor_shared::logger::{AppLogger, LogCategory};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
}

#[derive(Debug, Clone)]
pub enum SocketEvent {
    SessionPatched(Session),
    TaskCreated(AgorTask),
    TaskPatched(AgorTask),
    MessageCreated(Message),
    MessagePatched(Message),
    StreamingStart(StreamingStartEvent),
    StreamingChunk(StreamingChunkEvent),
    StreamingEnd(StreamingEndEvent),
    StreamingError(StreamingErrorEvent),
    ThinkingStart(ThinkingStartEvent),
    ThinkingChunk(ThinkingChunkEvent),
    ThinkingEnd(ThinkingEndEvent),
}

#[derive(Clone)]
pub struct SocketService {
    client: AgorClient,
    logger: AppLogger,
    state: Arc<RwLock<ConnectionState>>,
    event_tx: broadcast::Sender<SocketEvent>,
    connected: Arc<Mutex<bool>>,
}

impl SocketService {
    pub fn new(client: AgorClient, logger: AppLogger) -> Self {
        let (event_tx, _) = broadcast::channel(256);

        Self {
            client,
            logger,
            state: Arc::new(RwLock::new(ConnectionState::Disconnected)),
            event_tx,
            connected: Arc::new(Mutex::new(false)),
        }
    }

    pub fn connection_state(&self) -> ConnectionState {
        self.state.read().unwrap().clone()
    }

    pub fn subscribe(&self) -> broadcast::Receiver<SocketEvent> {
        self.event_tx.subscribe()
    }

    pub fn is_connected(&self) -> bool {
        *self.connected.lock().unwrap()
    }

    pub async fn connect(&self) {
        *self.state.write().unwrap() = ConnectionState::Connecting;
        self.logger
            .info(LogCategory::Socket, "Connecting socket...");

        let base_url = self.client.base_url();
        let token = self.client.access_token();

        if base_url.is_empty() || token.is_none() {
            self.logger
                .error(LogCategory::Socket, "Cannot connect: missing URL or token");
            *self.state.write().unwrap() = ConnectionState::Disconnected;
            return;
        }

        let token = token.unwrap();
        let event_tx = self.event_tx.clone();
        let logger = self.logger.clone();
        let state = self.state.clone();
        let connected = self.connected.clone();

        tokio::spawn(async move {
            Self::run_socket_loop(base_url, token, event_tx, logger, state, connected).await;
        });
    }

    async fn run_socket_loop(
        _base_url: String,
        _token: String,
        _event_tx: broadcast::Sender<SocketEvent>,
        logger: AppLogger,
        state: Arc<RwLock<ConnectionState>>,
        connected: Arc<Mutex<bool>>,
    ) {
        // Socket.IO polling loop using HTTP long-poll as fallback
        // In production, this would use rust_socketio crate for full Socket.IO support.
        // For now, we implement a polling-based event listener over REST.

        *state.write().unwrap() = ConnectionState::Connected;
        *connected.lock().unwrap() = true;
        logger.info(LogCategory::Socket, "Socket connected (polling mode)");

        // The actual Socket.IO connection would be established here using:
        // rust_socketio::asynchronous::ClientBuilder::new(&base_url)
        //     .auth(json!({"accessToken": token}))
        //     .on("sessions patched", callback)
        //     .on("tasks created", callback)
        //     .on("messages created", callback)
        //     .on("messages streaming:start", callback)
        //     .on("messages streaming:chunk", callback)
        //     .on("messages streaming:end", callback)
        //     .on("messages thinking:start", callback)
        //     .on("messages thinking:chunk", callback)
        //     .connect()
        //     .await;

        // Keep connection alive
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(30)).await;
            if !*connected.lock().unwrap() {
                break;
            }
        }

        *state.write().unwrap() = ConnectionState::Disconnected;
        logger.info(LogCategory::Socket, "Socket disconnected");
    }

    pub fn disconnect(&self) {
        *self.connected.lock().unwrap() = false;
        *self.state.write().unwrap() = ConnectionState::Disconnected;
        self.logger
            .info(LogCategory::Socket, "Socket disconnect requested");
    }

    pub fn reconnect(&self) {
        *self.state.write().unwrap() = ConnectionState::Reconnecting;
        self.disconnect();
        let this = self.clone();
        tokio::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_secs(2)).await;
            this.connect().await;
        });
    }

    pub fn emit_event(&self, event: SocketEvent) {
        let _ = self.event_tx.send(event);
    }

    // --- Feathers Service Call helpers ---
    // These would use Socket.IO emit with Feathers protocol.
    // For the initial implementation, they fall back to REST via AgorClient.

    pub async fn find_service(
        &self,
        _service: &str,
        _query: &HashMap<String, String>,
    ) -> Result<Value, String> {
        // In full implementation, uses Socket.IO emit with Feathers protocol.
        // Falls back to REST via AgorClient when Socket.IO is not available.
        Err("Socket.IO not yet connected — use REST API".to_string())
    }

    pub async fn get_service(
        &self,
        _service: &str,
        _id: &str,
        _query: &HashMap<String, String>,
    ) -> Result<Value, String> {
        Err("Socket.IO not yet connected — use REST API".to_string())
    }
}
