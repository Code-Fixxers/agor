use crate::models::HermesVoiceState;

pub struct HermesVoiceManager {
    state: HermesVoiceState,
}

impl HermesVoiceManager {
    pub fn new() -> Self {
        Self {
            state: HermesVoiceState::default(),
        }
    }

    pub fn state(&self) -> &HermesVoiceState {
        &self.state
    }

    pub fn start(&mut self) {
        self.state.enabled = true;
    }

    pub fn stop(&mut self) {
        self.state.enabled = false;
        self.state.phase = crate::models::HermesVoicePhase::Idle;
    }

    pub fn toggle(&mut self) {
        if self.state.enabled {
            self.stop();
        } else {
            self.start();
        }
    }

    pub fn set_active_session(&mut self, session_id: Option<String>) {
        self.state.active_session_id = session_id;
    }
}
