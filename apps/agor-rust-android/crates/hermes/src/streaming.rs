#[derive(Debug, Clone)]
pub struct StreamTextUpdate {
    pub text: String,
    pub replace_existing: bool,
    pub emit_text_event: bool,
}

pub struct HermesStreamTextState {
    final_text: String,
    reasoning_shown: bool,
    final_started: bool,
}

impl HermesStreamTextState {
    pub fn new() -> Self {
        Self {
            final_text: String::new(),
            reasoning_shown: false,
            final_started: false,
        }
    }

    pub fn final_text(&self) -> &str {
        &self.final_text
    }

    pub fn on_reasoning_delta(&mut self, delta: &str) -> Option<StreamTextUpdate> {
        if delta.trim().is_empty() || self.final_started {
            return None;
        }
        self.reasoning_shown = true;
        Some(StreamTextUpdate {
            text: delta.to_string(),
            replace_existing: false,
            emit_text_event: false,
        })
    }

    pub fn on_text_delta(&mut self, delta: &str) -> Option<StreamTextUpdate> {
        if delta.is_empty() {
            return None;
        }
        let replace = self.reasoning_shown && !self.final_started;
        self.final_started = true;
        self.final_text.push_str(delta);
        Some(StreamTextUpdate {
            text: delta.to_string(),
            replace_existing: replace,
            emit_text_event: true,
        })
    }
}
