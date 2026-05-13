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
