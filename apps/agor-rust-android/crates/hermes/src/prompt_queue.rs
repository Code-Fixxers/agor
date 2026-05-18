use std::collections::{HashMap, VecDeque};
use std::sync::Mutex;

use crate::models::HermesAttachment;

#[derive(Debug, Clone)]
pub struct QueuedPrompt {
    pub prompt: String,
    pub image_data_urls: Vec<String>,
    pub attachments: Vec<HermesAttachment>,
}

pub struct HermesPromptQueue {
    queues: Mutex<HashMap<String, VecDeque<QueuedPrompt>>>,
}

impl HermesPromptQueue {
    pub fn new() -> Self {
        Self {
            queues: Mutex::new(HashMap::new()),
        }
    }

    pub fn enqueue(&self, session_id: &str, prompt: QueuedPrompt) -> usize {
        let mut map = self.queues.lock().unwrap();
        let queue = map.entry(session_id.to_string()).or_default();
        queue.push_back(prompt);
        queue.len()
    }

    pub fn dequeue(&self, session_id: &str) -> Option<QueuedPrompt> {
        let mut map = self.queues.lock().unwrap();
        let item = map.get_mut(session_id)?.pop_front();
        if map.get(session_id).map_or(false, |q| q.is_empty()) {
            map.remove(session_id);
        }
        item
    }

    pub fn depth(&self, session_id: &str) -> usize {
        self.queues
            .lock()
            .unwrap()
            .get(session_id)
            .map_or(0, |q| q.len())
    }
}
