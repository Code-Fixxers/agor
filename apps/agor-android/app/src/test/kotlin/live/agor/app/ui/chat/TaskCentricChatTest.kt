package live.agor.app.ui.chat

import live.agor.app.models.AgorTask
import live.agor.app.models.Message
import live.agor.app.models.MessageContent
import live.agor.app.models.MessageRole
import live.agor.app.models.MessageType
import live.agor.app.models.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCentricChatTest {
    @Test
    fun buildsVirtualTaskForTasklessSessionMessages() {
        val messages = listOf(message("m1", index = 1), message("m2", index = 2))

        val tasks = taskCentricTasks("session", emptyList(), messages)
        val grouped = groupMessagesByTask(messages, tasks)

        assertEquals(listOf(TaskCentricChat.VirtualTaskId), tasks.map { it.taskId })
        assertEquals(messages, grouped[TaskCentricChat.VirtualTaskId])
    }

    @Test
    fun initialWindowExpandsOnlyLatestTask() {
        val tasks = listOf(task("t1", first = 1), task("t2", first = 10), task("t3", first = 20))

        val window = TaskCentricChat.initialWindow(tasks)

        assertEquals(setOf("t3"), window.visibleTaskIds)
        assertEquals(setOf("t3"), window.expandedTaskIds)
        assertEquals(setOf("t3"), window.loadedTaskIds)
        assertEquals(2, window.olderTaskCount)
    }

    @Test
    fun revealingOlderTasksAddsCollapsedHeadersWithoutMarkingLoaded() {
        val tasks = listOf(task("t1", first = 1), task("t2", first = 10), task("t3", first = 20))
        val window = TaskCentricChat.initialWindow(tasks).revealOlder(batchSize = 1, orderedTasks = tasks)

        assertEquals(setOf("t2", "t3"), window.visibleTaskIds)
        assertEquals(setOf("t3"), window.expandedTaskIds)
        assertEquals(setOf("t3"), window.loadedTaskIds)
        assertEquals(1, window.olderTaskCount)
    }

    @Test
    fun collapsingTaskUnloadsItsMessages() {
        val tasks = listOf(task("t1", first = 1))
        val state = TaskCentricChat.initialState(
            orderedTasks = tasks,
            messagesByTask = mapOf("t1" to listOf(message("m1", taskId = "t1", index = 1))),
        )

        val collapsed = state.collapse("t1")

        assertFalse(collapsed.window.expandedTaskIds.contains("t1"))
        assertFalse(collapsed.window.loadedTaskIds.contains("t1"))
        assertFalse(collapsed.messagesByTask.containsKey("t1"))
    }

    @Test
    fun rowProjectionShowsOlderButtonAndOmitsCollapsedMessages() {
        val tasks = listOf(task("t1", first = 1), task("t2", first = 10))
        val messagesByTask = mapOf(
            "t1" to listOf(message("m1", taskId = "t1", index = 1, text = "older")),
            "t2" to listOf(message("m2", taskId = "t2", index = 10, text = "latest")),
        )
        val window = TaskCentricChat.TaskWindow(
            visibleTaskIds = setOf("t1", "t2"),
            expandedTaskIds = setOf("t2"),
            loadedTaskIds = setOf("t2"),
            olderTaskCount = 1,
        )

        val rows = ChatRowFlattener().renderTaskCentric(
            orderedTasks = tasks,
            messagesByTask = messagesByTask,
            window = window,
            live = emptyMap(),
        )

        assertTrue(rows.first() is ChatRow.ShowOlderTasks)
        assertEquals(listOf("t1" to false, "t2" to true), rows.filterIsInstance<ChatRow.TaskHeaderRow>().map { it.task.taskId to it.expanded })
        assertEquals(listOf("latest"), rows.filterIsInstance<ChatRow.TextBubbleRow>().map { it.text })
    }

    private fun task(id: String, first: Int): AgorTask = AgorTask(
        taskId = id,
        sessionId = "session",
        status = TaskStatus.COMPLETED,
        title = id,
        createdAt = "2026-05-11T00:00:${first.toString().padStart(2, '0')}Z",
        firstMessageIndex = first,
        lastMessageIndex = first + 1,
    )

    private fun message(
        id: String,
        taskId: String? = null,
        index: Int,
        text: String = id,
    ): Message = Message(
        messageId = id,
        sessionId = "session",
        taskId = taskId,
        type = MessageType.ASSISTANT,
        role = MessageRole.ASSISTANT,
        index = index,
        timestamp = "2026-05-11T00:00:${index.toString().padStart(2, '0')}Z",
        contentPreview = text,
        content = MessageContent.Text(text),
    )
}
