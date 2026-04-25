package live.agor.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SessionStatus {
    @SerialName("idle") IDLE,
    @SerialName("running") RUNNING,
    @SerialName("stopping") STOPPING,
    @SerialName("awaiting_permission") AWAITING_PERMISSION,
    @SerialName("awaiting_input") AWAITING_INPUT,
    @SerialName("timed_out") TIMED_OUT,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED;

    val needsAttention: Boolean
        get() = this == AWAITING_PERMISSION || this == AWAITING_INPUT

    val isActive: Boolean
        get() = this == RUNNING || this == STOPPING || needsAttention

    val displayLabel: String
        get() = when (this) {
            IDLE -> "Idle"
            RUNNING -> "Running"
            STOPPING -> "Stopping"
            AWAITING_PERMISSION -> "Awaiting Permission"
            AWAITING_INPUT -> "Awaiting Input"
            TIMED_OUT -> "Timed Out"
            COMPLETED -> "Completed"
            FAILED -> "Failed"
        }
}

@Serializable
enum class AgenticTool {
    @SerialName("claude-code") CLAUDE_CODE,
    @SerialName("codex") CODEX,
    @SerialName("gemini") GEMINI,
    @SerialName("opencode") OPENCODE;

    val displayName: String
        get() = when (this) {
            CLAUDE_CODE -> "Claude Code"
            CODEX -> "Codex"
            GEMINI -> "Gemini"
            OPENCODE -> "OpenCode"
        }
}

@Serializable
enum class PermissionMode {
    @SerialName("default") DEFAULT,
    @SerialName("acceptEdits") ACCEPT_EDITS,
    @SerialName("bypassPermissions") BYPASS,
    @SerialName("plan") PLAN,
    @SerialName("dontAsk") DONT_ASK,
    @SerialName("autoEdit") AUTO_EDIT,
    @SerialName("yolo") YOLO,
    @SerialName("ask") ASK,
    @SerialName("auto") AUTO,
    @SerialName("on-failure") ON_FAILURE,
    @SerialName("allow-all") ALLOW_ALL,
}

@Serializable
data class GitState(
    val ref: String? = null,
    @SerialName("base_sha") val baseSha: String? = null,
    @SerialName("current_sha") val currentSha: String? = null,
)

@Serializable
data class SessionGenealogy(
    @SerialName("forked_from_session_id") val forkedFromSessionId: String? = null,
    @SerialName("fork_point_task_id") val forkPointTaskId: String? = null,
    @SerialName("fork_point_message_index") val forkPointMessageIndex: Int? = null,
    @SerialName("parent_session_id") val parentSessionId: String? = null,
    @SerialName("spawn_point_task_id") val spawnPointTaskId: String? = null,
    @SerialName("spawn_point_message_index") val spawnPointMessageIndex: Int? = null,
    val children: List<String> = emptyList(),
)

@Serializable
data class PermissionConfig(
    val mode: PermissionMode? = null,
)

@Serializable
data class ModelConfig(
    val mode: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val notes: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val thinkingMode: String? = null,
    val manualThinkingTokens: Int? = null,
    val effort: String? = null,
)

@Serializable
data class Session(
    @SerialName("session_id") val sessionId: String,
    @SerialName("agentic_tool") val agenticTool: AgenticTool,
    @SerialName("agentic_tool_version") val agenticToolVersion: String? = null,
    @SerialName("sdk_session_id") val sdkSessionId: String? = null,
    val status: SessionStatus,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_updated") val lastUpdated: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("unix_username") val unixUsername: String? = null,
    @SerialName("worktree_id") val worktreeId: String,
    @SerialName("worktree_board_id") val worktreeBoardId: String? = null,
    val url: String? = null,
    @SerialName("git_state") val gitState: GitState? = null,
    val genealogy: SessionGenealogy? = null,
    val tasks: List<String>? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    val title: String? = null,
    val description: String? = null,
    @SerialName("permission_config") val permissionConfig: PermissionConfig? = null,
    @SerialName("model_config") val modelConfig: ModelConfig? = null,
    @SerialName("current_context_usage") val currentContextUsage: Int? = null,
    @SerialName("context_window_limit") val contextWindowLimit: Int? = null,
    @SerialName("scheduled_from_worktree") val scheduledFromWorktree: Boolean? = null,
    @SerialName("ready_for_prompt") val readyForPrompt: Boolean? = null,
    val archived: Boolean? = null,
    @SerialName("archived_reason") val archivedReason: String? = null,
) {
    val displayTitle: String
        get() {
            title?.takeIf { it.isNotEmpty() }?.let { return it }
            description?.takeIf { it.isNotEmpty() }?.let { return it }
            return "Session ${sessionId.take(8)}"
        }

    val hasExplicitTitle: Boolean
        get() {
            val t = title ?: return false
            if (t.isEmpty()) return false
            if (t.startsWith("[Scheduled run")) return false
            return true
        }

    val isPlanMode: Boolean
        get() = permissionConfig?.mode == PermissionMode.PLAN

    val isPromptable: Boolean
        get() = status == SessionStatus.IDLE || readyForPrompt == true

    val isScheduled: Boolean
        get() = scheduledFromWorktree == true || title?.startsWith("[Scheduled ") == true
}
