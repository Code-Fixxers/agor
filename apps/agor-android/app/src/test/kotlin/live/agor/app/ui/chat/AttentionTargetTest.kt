package live.agor.app.ui.chat

import live.agor.app.models.InputRequestContent
import live.agor.app.models.InputRequestStatus
import live.agor.app.models.MessageRole
import live.agor.app.models.PermissionRequestContent
import live.agor.app.models.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttentionTargetTest {
    @Test
    fun picksNewestPendingPermissionOrInputRow() {
        val rows = listOf(
            text("a"),
            permission("resolved", PermissionStatus.APPROVED),
            permission("pending-perm", PermissionStatus.PENDING),
            input("pending-input", InputRequestStatus.PENDING),
        )

        assertEquals(3, newestAttentionRowIndex(rows))
    }

    @Test
    fun ignoresResolvedAttentionRows() {
        val rows = listOf(
            permission("approved", PermissionStatus.APPROVED),
            input("answered", InputRequestStatus.ANSWERED),
        )

        assertNull(newestAttentionRowIndex(rows))
    }

    private fun text(id: String): ChatRow =
        ChatRow.TextBubbleRow("text-$id", MessageRole.ASSISTANT, id, streaming = false)

    private fun permission(id: String, status: PermissionStatus): ChatRow =
        ChatRow.PermissionRow(
            key = "perm-$id",
            messageId = id,
            taskId = null,
            request = PermissionRequestContent(
                requestId = id,
                toolName = "Bash",
                status = status,
            ),
        )

    private fun input(id: String, status: InputRequestStatus): ChatRow =
        ChatRow.InputRequestRow(
            key = "input-$id",
            messageId = id,
            taskId = null,
            request = InputRequestContent(
                requestId = id,
                status = status,
            ),
        )
}
