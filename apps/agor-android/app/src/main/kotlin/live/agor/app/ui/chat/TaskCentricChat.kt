package live.agor.app.ui.chat

import live.agor.app.models.AgorTask
import live.agor.app.models.Message
import live.agor.app.models.TaskStatus

object TaskCentricChat {
    const val VirtualTaskId: String = "__agor_taskless__"
    const val OlderTaskBatchSize: Int = 5

    data class TaskWindow(
        val visibleTaskIds: Set<String>,
        val expandedTaskIds: Set<String>,
        val loadedTaskIds: Set<String>,
        val olderTaskCount: Int,
    ) {
        fun revealOlder(batchSize: Int = OlderTaskBatchSize, orderedTasks: List<AgorTask>): TaskWindow {
            if (olderTaskCount <= 0 || orderedTasks.isEmpty()) return this
            val visibleIndexes = orderedTasks
                .mapIndexedNotNull { index, task -> if (task.taskId in visibleTaskIds) index else null }
            val firstVisible = visibleIndexes.minOrNull() ?: orderedTasks.lastIndex + 1
            val start = (firstVisible - batchSize).coerceAtLeast(0)
            val revealed = orderedTasks.subList(start, firstVisible).mapTo(LinkedHashSet()) { it.taskId }
            val nextVisible = LinkedHashSet<String>()
            nextVisible += revealed
            nextVisible += visibleTaskIds
            return copy(
                visibleTaskIds = nextVisible,
                olderTaskCount = start,
            )
        }

        fun expand(taskId: String): TaskWindow = copy(
            visibleTaskIds = visibleTaskIds + taskId,
            expandedTaskIds = expandedTaskIds + taskId,
            loadedTaskIds = loadedTaskIds + taskId,
        )
    }

    data class State(
        val orderedTasks: List<AgorTask>,
        val messagesByTask: Map<String, List<Message>>,
        val window: TaskWindow,
    ) {
        fun collapse(taskId: String): State = copy(
            messagesByTask = messagesByTask - taskId,
            window = window.copy(
                expandedTaskIds = window.expandedTaskIds - taskId,
                loadedTaskIds = window.loadedTaskIds - taskId,
            ),
        )

        fun withLoadedTask(taskId: String, messages: List<Message>): State = copy(
            messagesByTask = messagesByTask + (taskId to messages.sortedBy { it.index }),
            window = window.expand(taskId),
        )
    }

    fun initialWindow(orderedTasks: List<AgorTask>): TaskWindow {
        val latest = orderedTasks.lastOrNull()?.taskId
        return if (latest == null) {
            TaskWindow(
                visibleTaskIds = emptySet(),
                expandedTaskIds = emptySet(),
                loadedTaskIds = emptySet(),
                olderTaskCount = 0,
            )
        } else {
            TaskWindow(
                visibleTaskIds = setOf(latest),
                expandedTaskIds = setOf(latest),
                loadedTaskIds = setOf(latest),
                olderTaskCount = orderedTasks.lastIndex,
            )
        }
    }

    fun initialState(
        orderedTasks: List<AgorTask>,
        messagesByTask: Map<String, List<Message>>,
    ): State {
        val window = initialWindow(orderedTasks)
        val loaded = messagesByTask.filterKeys { it in window.loadedTaskIds }
        return State(orderedTasks, loaded, window)
    }
}

fun taskCentricTasks(
    sessionId: String,
    tasks: List<AgorTask>,
    messages: List<Message>,
): List<AgorTask> {
    if (tasks.isNotEmpty()) return tasks.sortedWith(taskOrderComparator(messages))
    if (messages.isEmpty()) return emptyList()
    val sorted = messages.sortedBy { it.index }
    return listOf(
        AgorTask(
            taskId = TaskCentricChat.VirtualTaskId,
            sessionId = sessionId,
            status = TaskStatus.COMPLETED,
            title = "Session history",
            prompt = sorted.firstOrNull()?.contentPreview,
            createdAt = sorted.firstOrNull()?.timestamp.orEmpty(),
            firstMessageIndex = sorted.firstOrNull()?.index,
            lastMessageIndex = sorted.lastOrNull()?.index,
        ),
    )
}

fun groupMessagesByTask(
    messages: List<Message>,
    tasks: List<AgorTask>,
): Map<String, List<Message>> {
    if (messages.isEmpty()) return emptyMap()
    val knownTaskIds = tasks.mapTo(HashSet()) { it.taskId }
    val fallbackTaskId = if (TaskCentricChat.VirtualTaskId in knownTaskIds) TaskCentricChat.VirtualTaskId else null
    return messages
        .asSequence()
        .mapNotNull { message ->
            val taskId = message.taskId?.takeIf { it in knownTaskIds } ?: fallbackTaskId
            taskId?.let { it to message }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, grouped) -> grouped.sortedBy { it.index } }
}

private fun taskOrderComparator(messages: List<Message>): Comparator<AgorTask> {
    val messageIndexByTask = messages
        .asSequence()
        .filter { it.taskId != null }
        .groupBy { it.taskId!! }
        .mapValues { (_, grouped) -> grouped.minOf { it.index } }
    return compareBy<AgorTask>(
        { it.firstMessageIndex ?: messageIndexByTask[it.taskId] ?: Int.MAX_VALUE },
        { it.createdAt },
        { it.taskId },
    )
}
