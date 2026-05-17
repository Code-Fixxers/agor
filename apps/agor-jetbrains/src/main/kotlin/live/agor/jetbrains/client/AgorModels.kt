package live.agor.jetbrains.client

data class AgorBoard(
    val boardId: String,
    val name: String,
)

data class AgorRepo(
    val repoId: String,
    val name: String,
    val slug: String,
    val defaultBranch: String?,
)

data class AgorWorktree(
    val worktreeId: String,
    val repoId: String?,
    val boardId: String?,
    val name: String,
    val ref: String?,
    val path: String,
)

enum class AgorSessionStatus {
    RUNNING,
    IDLE,
    COMPLETED,
    FAILED,
    QUEUED,
    UNKNOWN,
}

data class AgorSession(
    val sessionId: String,
    val worktreeId: String,
    val title: String,
    val agenticTool: String,
    val status: AgorSessionStatus,
)

enum class AgorMessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    UNKNOWN,
}

enum class AgorMessageType {
    USER,
    ASSISTANT,
    SYSTEM,
    FILE_HISTORY_SNAPSHOT,
    PERMISSION_REQUEST,
    INPUT_REQUEST,
    UNKNOWN,
}

data class AgorMessage(
    val messageId: String,
    val sessionId: String,
    val taskId: String?,
    val type: AgorMessageType,
    val role: AgorMessageRole,
    val index: Int,
    val timestamp: String?,
    val contentPreview: String,
    val text: String,
    val status: String?,
)

sealed class AgorSocketEvent {
    data class SnapshotChanged(val sessionId: String? = null, val messageId: String? = null) : AgorSocketEvent()
    data class StreamingStarted(
        val sessionId: String,
        val messageId: String?,
        val taskId: String?,
        val index: Int?,
        val timestamp: String?,
    ) : AgorSocketEvent()

    data class StreamingChunk(
        val sessionId: String,
        val messageId: String?,
        val text: String,
        val thinking: Boolean = false,
    ) : AgorSocketEvent()

    data class StreamingEnded(val sessionId: String, val messageId: String?) : AgorSocketEvent()
    data class StreamingFailed(val sessionId: String, val messageId: String?, val error: String) : AgorSocketEvent()
}

enum class AgorPermissionScope(val wireName: String) {
    ONCE("once"),
    PROJECT("project"),
    USER("user"),
    LOCAL("local"),
}

data class AgorPermissionRequest(
    val messageId: String,
    val sessionId: String,
    val taskId: String?,
    val requestId: String,
    val toolName: String,
    val toolInputJson: String,
)

data class AgorSnapshot(
    val boards: List<AgorBoard> = emptyList(),
    val worktrees: List<AgorWorktree> = emptyList(),
    val sessions: List<AgorSession> = emptyList(),
    val permissionRequests: List<AgorPermissionRequest> = emptyList(),
)

data class AgorCreateSessionRequest(
    val worktreeId: String,
    val agenticTool: String,
    val title: String?,
    val initialPrompt: String?,
)

data class AgorCreateWorktreeRequest(
    val repoId: String,
    val boardId: String?,
    val name: String,
    val sourceBranch: String,
    val createBranch: Boolean = true,
    val pullLatest: Boolean = true,
)

data class AgorSpawnSessionRequest(
    val parentSessionId: String,
    val prompt: String,
    val title: String?,
    val agenticTool: String?,
)
