use std::collections::HashMap;

use crate::models::*;
use crate::network::agor_client::AgorClient;
use crate::network::streaming_service::StreamSnapshot;
use crate::util::logger::{AppLogger, LogCategory};

#[derive(Debug, Clone, PartialEq)]
pub enum ChatRow {
    TaskHeader {
        task: AgorTask,
        expanded: bool,
        message_count: usize,
    },
    TextBubble {
        message: Message,
        is_streaming: bool,
        streaming_text: Option<String>,
    },
    ToolUseRow {
        message_id: String,
        block: ContentBlock,
    },
    ToolResultRow {
        message_id: String,
        block: ContentBlock,
    },
    ThinkingRow {
        message_id: String,
        thinking: String,
        is_streaming: bool,
    },
    ImageRow {
        message_id: String,
        block: ContentBlock,
    },
    PermissionCardRow {
        message: Message,
        request: PermissionRequestContent,
    },
    InputRequestRow {
        message: Message,
        request: InputRequestContent,
    },
    LiveStreamRow {
        session_id: String,
        text: String,
        thinking: String,
    },
    OlderTasksRow {
        count: usize,
    },
}

impl ChatRow {
    pub fn key(&self) -> String {
        match self {
            ChatRow::TaskHeader { task, .. } => format!("task-{}", task.task_id),
            ChatRow::TextBubble { message, .. } => format!("msg-{}", message.message_id),
            ChatRow::ToolUseRow { message_id, block } => {
                format!("tool-{}-{}", message_id, block.id())
            }
            ChatRow::ToolResultRow { message_id, block } => {
                format!("result-{}-{}", message_id, block.id())
            }
            ChatRow::ThinkingRow { message_id, .. } => format!("think-{message_id}"),
            ChatRow::ImageRow { message_id, block } => {
                format!("img-{}-{}", message_id, block.id())
            }
            ChatRow::PermissionCardRow { message, .. } => {
                format!("perm-{}", message.message_id)
            }
            ChatRow::InputRequestRow { message, .. } => {
                format!("input-{}", message.message_id)
            }
            ChatRow::LiveStreamRow { session_id, .. } => format!("live-{session_id}"),
            ChatRow::OlderTasksRow { count } => format!("older-{count}"),
        }
    }
}

#[derive(Debug, Clone)]
pub struct ChatStore {
    pub session: Option<Session>,
    pub tasks: Vec<AgorTask>,
    pub messages: Vec<Message>,
    pub messages_by_task: HashMap<String, Vec<Message>>,
    pub loaded_task_ids: std::collections::HashSet<String>,
    pub draft: String,
    pub is_loading: bool,
    pub error: Option<String>,
    pub visible_task_limit: usize,
}

impl ChatStore {
    pub fn new() -> Self {
        Self {
            session: None,
            tasks: Vec::new(),
            messages: Vec::new(),
            messages_by_task: HashMap::new(),
            loaded_task_ids: std::collections::HashSet::new(),
            draft: String::new(),
            is_loading: false,
            error: None,
            visible_task_limit: 20,
        }
    }

    pub fn reset(&mut self) {
        self.session = None;
        self.tasks.clear();
        self.messages.clear();
        self.messages_by_task.clear();
        self.loaded_task_ids.clear();
        self.draft.clear();
        self.is_loading = false;
        self.error = None;
        self.visible_task_limit = 20;
    }

    pub fn build_chat_rows(&self, live_streams: &HashMap<String, StreamSnapshot>) -> Vec<ChatRow> {
        let mut rows = Vec::new();
        let session_id = match &self.session {
            Some(s) => &s.session_id,
            None => return rows,
        };

        let total_tasks = self.tasks.len();
        let skip_count = if total_tasks > self.visible_task_limit {
            total_tasks - self.visible_task_limit
        } else {
            0
        };

        if skip_count > 0 {
            rows.push(ChatRow::OlderTasksRow { count: skip_count });
        }

        for (i, task) in self.tasks.iter().enumerate() {
            if i < skip_count {
                continue;
            }

            let is_latest = i == total_tasks - 1;
            let expanded = is_latest || self.loaded_task_ids.contains(&task.task_id);
            let task_messages = self.messages_by_task.get(&task.task_id);
            let msg_count = task_messages.map(|m| m.len()).unwrap_or(0);

            rows.push(ChatRow::TaskHeader {
                task: task.clone(),
                expanded,
                message_count: msg_count,
            });

            if expanded {
                if let Some(messages) = task_messages {
                    for msg in messages {
                        self.append_message_rows(&mut rows, msg);
                    }
                }
            }
        }

        if self.tasks.is_empty() {
            for msg in &self.messages {
                self.append_message_rows(&mut rows, msg);
            }
        }

        for (_msg_id, snap) in live_streams {
            if snap.session_id == *session_id && !snap.finished {
                rows.push(ChatRow::LiveStreamRow {
                    session_id: snap.session_id.clone(),
                    text: snap.text.clone(),
                    thinking: snap.thinking.clone(),
                });
            }
        }

        rows
    }

    fn append_message_rows(&self, rows: &mut Vec<ChatRow>, msg: &Message) {
        match &msg.content {
            MessageContent::Permission(req) => {
                rows.push(ChatRow::PermissionCardRow {
                    message: msg.clone(),
                    request: req.clone(),
                });
            }
            MessageContent::InputRequest(req) => {
                rows.push(ChatRow::InputRequestRow {
                    message: msg.clone(),
                    request: req.clone(),
                });
            }
            MessageContent::Blocks(blocks) => {
                for block in blocks {
                    match block {
                        ContentBlock::Text { text } if !text.is_empty() => {
                            rows.push(ChatRow::TextBubble {
                                message: msg.clone(),
                                is_streaming: false,
                                streaming_text: None,
                            });
                        }
                        ContentBlock::ToolUse { .. } => {
                            rows.push(ChatRow::ToolUseRow {
                                message_id: msg.message_id.clone(),
                                block: block.clone(),
                            });
                        }
                        ContentBlock::ToolResult { .. } => {
                            rows.push(ChatRow::ToolResultRow {
                                message_id: msg.message_id.clone(),
                                block: block.clone(),
                            });
                        }
                        ContentBlock::Thinking { thinking } => {
                            if let Some(text) = thinking {
                                rows.push(ChatRow::ThinkingRow {
                                    message_id: msg.message_id.clone(),
                                    thinking: text.clone(),
                                    is_streaming: false,
                                });
                            }
                        }
                        ContentBlock::Image { .. } => {
                            rows.push(ChatRow::ImageRow {
                                message_id: msg.message_id.clone(),
                                block: block.clone(),
                            });
                        }
                        _ => {}
                    }
                }
            }
            MessageContent::Text(text) if !text.is_empty() => {
                rows.push(ChatRow::TextBubble {
                    message: msg.clone(),
                    is_streaming: false,
                    streaming_text: None,
                });
            }
            _ => {}
        }
    }

    pub fn insert_message(&mut self, msg: Message) {
        let idx = self
            .messages
            .binary_search_by(|m| m.index.cmp(&msg.index))
            .unwrap_or_else(|i| i);

        if idx < self.messages.len() && self.messages[idx].message_id == msg.message_id {
            self.messages[idx] = msg.clone();
        } else {
            self.messages.insert(idx, msg.clone());
        }

        if let Some(task_id) = &msg.task_id {
            let task_msgs = self.messages_by_task.entry(task_id.clone()).or_default();
            let tidx = task_msgs
                .binary_search_by(|m| m.index.cmp(&msg.index))
                .unwrap_or_else(|i| i);
            if tidx < task_msgs.len() && task_msgs[tidx].message_id == msg.message_id {
                task_msgs[tidx] = msg;
            } else {
                task_msgs.insert(tidx, msg);
            }
        }
    }

    pub fn insert_task(&mut self, task: AgorTask) {
        if let Some(existing) = self.tasks.iter_mut().find(|t| t.task_id == task.task_id) {
            *existing = task;
        } else {
            self.tasks.push(task);
            self.tasks.sort_by(|a, b| a.created_at.cmp(&b.created_at));
        }
    }
}

pub async fn load_session(
    client: &AgorClient,
    chat: &mut ChatStore,
    session_id: &str,
    logger: &AppLogger,
) -> Result<(), String> {
    chat.reset();
    chat.is_loading = true;

    logger.info(LogCategory::Chat, format!("Loading session {session_id}"));

    let session = client
        .get_session(session_id)
        .await
        .map_err(|e| e.to_string())?;

    if session.ready_for_prompt.unwrap_or(false) {
        let _ = client
            .patch_session(session_id, &serde_json::json!({"ready_for_prompt": false}))
            .await;
    }

    let tasks = client
        .list_tasks(session_id)
        .await
        .map_err(|e| e.to_string())?;

    let latest_task_id = tasks.last().map(|t| t.task_id.as_str());

    let messages = client
        .list_messages(session_id, latest_task_id, Some(200), None)
        .await
        .map_err(|e| e.to_string())?;

    chat.session = Some(session);
    chat.tasks = tasks;

    for msg in messages {
        chat.insert_message(msg);
    }

    if let Some(task) = chat.tasks.last() {
        chat.loaded_task_ids.insert(task.task_id.clone());
    }

    chat.is_loading = false;

    logger.info(
        LogCategory::Chat,
        format!(
            "Loaded {} tasks, {} messages",
            chat.tasks.len(),
            chat.messages.len(),
        ),
    );

    Ok(())
}

pub async fn send_prompt(
    client: &AgorClient,
    chat: &mut ChatStore,
    logger: &AppLogger,
) -> Result<(), String> {
    let session_id = chat
        .session
        .as_ref()
        .map(|s| s.session_id.clone())
        .ok_or("No session selected")?;

    let prompt = chat.draft.trim().to_string();
    if prompt.is_empty() {
        return Err("Prompt is empty".to_string());
    }

    logger.info(LogCategory::Chat, format!("Sending prompt to {session_id}"));

    client
        .send_prompt(&session_id, &prompt)
        .await
        .map_err(|e| e.to_string())?;

    chat.draft.clear();

    logger.info(LogCategory::Chat, "Prompt sent successfully");

    Ok(())
}

pub async fn decide_permission(
    client: &AgorClient,
    session_id: &str,
    request_id: &str,
    task_id: Option<&str>,
    allow: bool,
    user_id: &str,
    logger: &AppLogger,
) -> Result<(), String> {
    let scope = if allow { "project" } else { "once" };

    logger.info(
        LogCategory::Chat,
        format!(
            "Permission decision: {} for request {request_id}",
            if allow { "approve" } else { "deny" }
        ),
    );

    client
        .decide_permission(session_id, request_id, task_id, allow, scope, user_id)
        .await
        .map_err(|e| e.to_string())
}

pub async fn answer_input_request(
    client: &AgorClient,
    session_id: &str,
    request_id: &str,
    task_id: Option<&str>,
    answers: &HashMap<String, String>,
    user_id: &str,
    logger: &AppLogger,
) -> Result<(), String> {
    logger.info(
        LogCategory::Chat,
        format!("Answering input request {request_id}"),
    );

    client
        .answer_input_request(session_id, request_id, task_id, answers, user_id)
        .await
        .map_err(|e| e.to_string())
}
