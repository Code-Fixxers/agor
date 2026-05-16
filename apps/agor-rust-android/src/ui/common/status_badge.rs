use crate::models::session::SessionStatus;

pub fn status_class(status: &SessionStatus) -> &'static str {
    match status {
        SessionStatus::Idle => "status-idle",
        SessionStatus::Running => "status-running",
        SessionStatus::Stopping => "status-stopping",
        SessionStatus::AwaitingPermission => "status-attention",
        SessionStatus::AwaitingInput => "status-attention",
        SessionStatus::TimedOut => "status-error",
        SessionStatus::Completed => "status-completed",
        SessionStatus::Failed => "status-error",
    }
}
