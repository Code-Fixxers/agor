use crate::models::session::AgenticTool;

pub fn agent_icon_class(tool: &AgenticTool) -> &'static str {
    match tool {
        AgenticTool::ClaudeCode => "agent-claude",
        AgenticTool::Codex => "agent-codex",
        AgenticTool::Gemini => "agent-gemini",
        AgenticTool::Junie => "agent-junie",
        AgenticTool::Opencode => "agent-opencode",
    }
}
