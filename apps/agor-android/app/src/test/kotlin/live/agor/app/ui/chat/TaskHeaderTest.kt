package live.agor.app.ui.chat

import live.agor.app.models.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskHeaderTest {
    @Test
    fun failedTaskStatusIsVisibleInCollapsedHeaders() {
        assertEquals("failed", taskStatusLabel(TaskStatus.FAILED))
    }

    @Test
    fun completedTaskStatusDoesNotAddHeaderNoise() {
        assertNull(taskStatusLabel(TaskStatus.COMPLETED))
    }
}
