use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

const MAX_ENTRIES: usize = 500;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LogLevel {
    Debug,
    Info,
    Warning,
    Error,
}

impl LogLevel {
    pub fn label(&self) -> &str {
        match self {
            Self::Debug => "DEBUG",
            Self::Info => "INFO",
            Self::Warning => "WARN",
            Self::Error => "ERROR",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LogCategory {
    Http,
    Auth,
    Socket,
    Nav,
    Chat,
    Voice,
    Notification,
    General,
}

impl LogCategory {
    pub fn label(&self) -> &str {
        match self {
            Self::Http => "HTTP",
            Self::Auth => "AUTH",
            Self::Socket => "SOCKET",
            Self::Nav => "NAV",
            Self::Chat => "CHAT",
            Self::Voice => "VOICE",
            Self::Notification => "NOTIF",
            Self::General => "GEN",
        }
    }
}

#[derive(Debug, Clone)]
pub struct LogEntry {
    pub timestamp: String,
    pub level: LogLevel,
    pub category: LogCategory,
    pub message: String,
}

impl LogEntry {
    pub fn format(&self) -> String {
        format!(
            "[{}] [{}] [{}] {}",
            self.timestamp,
            self.level.label(),
            self.category.label(),
            self.message
        )
    }
}

#[derive(Debug, Clone)]
pub struct AppLogger {
    entries: Arc<Mutex<VecDeque<LogEntry>>>,
}

impl AppLogger {
    pub fn new() -> Self {
        Self {
            entries: Arc::new(Mutex::new(VecDeque::with_capacity(MAX_ENTRIES))),
        }
    }

    pub fn log(&self, level: LogLevel, category: LogCategory, message: impl Into<String>) {
        let entry = LogEntry {
            timestamp: chrono::Utc::now().format("%H:%M:%S%.3f").to_string(),
            level,
            category,
            message: message.into(),
        };

        #[cfg(not(target_arch = "wasm32"))]
        tracing::debug!("{}", entry.format());

        let mut entries = self.entries.lock().unwrap();
        if entries.len() >= MAX_ENTRIES {
            entries.pop_front();
        }
        entries.push_back(entry);
    }

    pub fn info(&self, category: LogCategory, message: impl Into<String>) {
        self.log(LogLevel::Info, category, message);
    }

    pub fn error(&self, category: LogCategory, message: impl Into<String>) {
        self.log(LogLevel::Error, category, message);
    }

    pub fn debug(&self, category: LogCategory, message: impl Into<String>) {
        self.log(LogLevel::Debug, category, message);
    }

    pub fn entries(&self) -> Vec<LogEntry> {
        self.entries.lock().unwrap().iter().cloned().collect()
    }

    pub fn export_text(&self) -> String {
        self.entries()
            .iter()
            .map(|e| e.format())
            .collect::<Vec<_>>()
            .join("\n")
    }

    pub fn clear(&self) {
        self.entries.lock().unwrap().clear();
    }
}

impl Default for AppLogger {
    fn default() -> Self {
        Self::new()
    }
}
