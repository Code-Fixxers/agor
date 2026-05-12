package live.agor.app.viewmodels

import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import live.agor.app.AppContainer
import live.agor.app.models.AgorTask
import live.agor.app.models.ContentBlock
import live.agor.app.models.InputRequestContent
import live.agor.app.models.InputRequestStatus
import live.agor.app.models.Message
import live.agor.app.models.MessageContent
import live.agor.app.models.MessageRole
import live.agor.app.models.PermissionStatus
import live.agor.app.models.PermissionMode
import live.agor.app.models.Session
import live.agor.app.network.UploadFileInput
import live.agor.app.network.sessionPermissionModePayload
import live.agor.app.network.StreamingService
import live.agor.app.ui.chat.ChatRow
import live.agor.app.ui.chat.ChatRowFlattener
import live.agor.app.ui.chat.TaskCentricChat
import live.agor.app.ui.chat.groupMessagesByTask
import live.agor.app.ui.chat.taskCentricTasks
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import live.agor.app.voice.ContinuousVoiceService
import live.agor.app.voice.PromptVoiceInputController
import live.agor.app.voice.PromptVoicePhase
import live.agor.app.voice.SessionVoicePolicy
import live.agor.app.voice.SessionVoiceSettings
import java.io.IOException
import java.util.UUID

/**
 * ViewModel backing a single session's chat screen.
 *
 * Design (changed in the perf overhaul, see plans/floofy-weaving-key.md):
 *
 *  - Three independent data flows ([messages], [tasks], [live]) instead of one
 *    monolithic [UiState]. A streaming chunk only churns [live]; tasks/messages
 *    keep their stable references and Compose smart-skip applies to bubbles
 *    whose own snapshot didn't change.
 *
 *  - Transient UI fields (loading, error, draft, session metadata) live in a
 *    separate [uiState] so a 45 s polling refresh that flips `isLoading` does
 *    not invalidate any row-flattening derivation downstream.
 *
 *  - Message inserts/patches use binary-search sorted insertion instead of
 *    `filterNot + sortedBy` over the whole list per event.
 */
class ChatViewModel(private val container: AppContainer, val sessionId: String) : ViewModel() {

    data class PendingSessionAttachment(
        val id: String,
        val filename: String,
        val mimeType: String?,
        val sizeBytes: Long,
        val bytes: ByteArray,
        val uploadedPath: String? = null,
    ) {
        fun toUploadInput(): UploadFileInput = UploadFileInput(filename, mimeType, bytes)
    }

    /** Transient UI bits — never observed by row flattening. */
    data class UiState(
        val session: Session? = null,
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val draft: String = "",
    )

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    val messageCount: StateFlow<Int> = _messages
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _tasks = MutableStateFlow<List<AgorTask>>(emptyList())
    val tasks: StateFlow<List<AgorTask>> = _tasks.asStateFlow()

    private val _messagesByTask = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messagesByTask: StateFlow<Map<String, List<Message>>> = _messagesByTask.asStateFlow()

    private val _loadedTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val loadedTaskIds: StateFlow<Set<String>> = _loadedTaskIds.asStateFlow()

    private val _taskWindow = MutableStateFlow(TaskCentricChat.initialWindow(emptyList()))

    /** Mirrors [StreamingService.live] directly; no extra copy. */
    val live: StateFlow<Map<String, StreamingService.StreamSnapshot>> = container.streaming.live

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _attachments = MutableStateFlow<List<PendingSessionAttachment>>(emptyList())
    val attachments: StateFlow<List<PendingSessionAttachment>> = _attachments.asStateFlow()

    val sessionVoiceState: StateFlow<live.agor.app.voice.SessionVoiceState> = ContinuousVoiceService.state
    val sessionVoiceSettings: StateFlow<SessionVoiceSettings> = container.sessionVoiceSettings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionVoiceSettings.Default)

    private val promptVoice = PromptVoiceInputController(
        container.appContext,
        container.tokenStore,
        container.voiceModels,
    )
    val promptVoiceState = promptVoice.state

    private val rowFlattener = ChatRowFlattener()
    private var cacheSaveJob: Job? = null
    private var draftSaveJob: Job? = null
    private var postSendRefreshJob: Job? = null
    private var voiceDraftPrefix: String? = null

    /** Lazy derivation — scans messages from the end, returns first pending. */
    val pendingPermissionId: StateFlow<String?> = _messages
        .map(::firstPendingPermission)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pendingInputRequestId: StateFlow<String?> = _messages
        .map(::firstPendingInput)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Pre-flattened, render-ready rows for the chat LazyColumn.
     *
     * Runs on [Dispatchers.Default], not Main — so the JSON serialization,
     * string joins, and merged streaming text in [flattenChatRows] no longer
     * block the UI thread on every streaming chunk. The previous design ran
     * the flatten inside `remember(...)` in the Composable, which executed
     * on Main on every state change.
     *
     * `WhileSubscribed(5_000)` keeps the result alive across short navigations
     * (drawer toggle, file browser sheet) so re-entry doesn't trigger a cold
     * recomputation.
     */
    val rows: StateFlow<List<ChatRow>> = combine(
        _tasks,
        _messagesByTask,
        _taskWindow,
        live,
    ) { t, byTask, window, l ->
        rowFlattener.renderTaskCentric(
            orderedTasks = t,
            messagesByTask = byTask,
            window = window,
            live = l,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        promptVoice.onPartialTranscribed = { text ->
            viewModelScope.launch { applyVoiceDraft(text, final = false) }
        }
        promptVoice.onTranscribed = { text ->
            viewModelScope.launch { applyVoiceDraft(text, final = true) }
        }
        container.socket.onMessageCreated { msg ->
            if (msg.sessionId != sessionId) return@onMessageCreated
            viewModelScope.launch {
                upsertSorted(msg)
                speakAssistantIfNeeded(msg)
            }
        }
        container.socket.onMessagePatched { msg ->
            if (msg.sessionId != sessionId) return@onMessagePatched
            viewModelScope.launch {
                upsertSorted(msg)
                speakAssistantIfNeeded(msg)
            }
        }
        container.socket.onTaskCreated { t ->
            if (t.sessionId != sessionId) return@onTaskCreated
            viewModelScope.launch {
                _tasks.update { current ->
                    taskCentricTasks(sessionId, current.filterNot { it.taskId == t.taskId } + t, _messages.value)
                }
                _taskWindow.update { it.expand(t.taskId) }
                _loadedTaskIds.value = _taskWindow.value.loadedTaskIds
                scheduleCacheSave()
            }
        }
        container.socket.onTaskPatched { t ->
            if (t.sessionId != sessionId) return@onTaskPatched
            viewModelScope.launch {
                _tasks.update { current ->
                    val idx = current.indexOfFirst { it.taskId == t.taskId }
                    val next = if (idx < 0) current + t
                    else current.toMutableList().apply { set(idx, t) }
                    taskCentricTasks(sessionId, next, _messages.value)
                }
                scheduleCacheSave()
            }
        }
        container.socket.onSessionPatched { s ->
            if (s.sessionId != sessionId) return@onSessionPatched
            viewModelScope.launch {
                _uiState.update { it.copy(session = s) }
                syncSessionVoice(s)
                scheduleCacheSave()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val savedDraft = container.chatDrafts.load(sessionId)
            if (savedDraft.isNotEmpty()) {
                _uiState.update { it.copy(draft = savedDraft) }
            }
            val cached = container.chatCache.load(sessionId)
            if (cached != null) {
                applyInitialTaskCentricState(cached.tasks, cached.messages)
                _uiState.update {
                    it.copy(session = cached.session, isLoading = false, errorMessage = null)
                }
            }
            try {
                val started = SystemClock.elapsedRealtime()
                val (session, tasks, latestMessages) = coroutineScope {
                    val sessionDeferred = async { container.client.getSession(sessionId) }
                    val tasksDeferred = async { container.client.listTasks(sessionId) }
                    val messagesDeferred = async { container.client.listMessages(sessionId, limit = 200) }
                    Triple(sessionDeferred.await(), tasksDeferred.await(), messagesDeferred.await())
                }
                val viewedSession = if (session.readyForPrompt == true) {
                    runCatching {
                        container.client.patchSession(
                            sessionId,
                            JsonObject(mapOf("ready_for_prompt" to JsonPrimitive(false))),
                        )
                    }.getOrDefault(session.copy(readyForPrompt = false))
                } else {
                    session
                }
                val orderedTasks = taskCentricTasks(sessionId, tasks, latestMessages)
                val latestTaskId = orderedTasks.lastOrNull()?.taskId
                val latestTaskMessages = if (latestTaskId == null || latestTaskId == TaskCentricChat.VirtualTaskId) {
                    latestMessages
                } else {
                    runCatching {
                        container.client.listMessages(sessionId, limit = 200, taskId = latestTaskId)
                    }.getOrElse {
                        latestMessages.filter { message -> message.taskId == latestTaskId }
                    }
                }
                applyInitialTaskCentricState(tasks, latestTaskMessages)
                _uiState.update {
                    it.copy(session = viewedSession, isLoading = false, errorMessage = null)
                }
                syncSessionVoice(viewedSession)
                val elapsed = SystemClock.elapsedRealtime() - started
                AppLogger.log(
                    "Chat $sessionId refresh loaded ${tasks.size} tasks and " +
                        "${latestMessages.size} latest messages in ${elapsed}ms",
                    LogLevel.DEBUG,
                    "Perf",
                )
                container.chatCache.save(viewedSession, tasks, _messages.value)
            } catch (t: Throwable) {
                AppLogger.log("Chat load failed: ${t.message}", LogLevel.ERROR, "Chat")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (cached == null) t.message else null,
                    )
                }
            }
        }
    }

    fun loadEarlier() {
        viewModelScope.launch {
            val current = _messages.value
            val skip = current.size
            val older = container.client.listMessages(sessionId, limit = 100, skip = skip)
            // Older messages have lower indexes; merge then re-sort. This path is
            // a user-driven action (button tap) — not in the streaming hot path —
            // so a single sortedBy is fine.
            _messages.value = mergeMessages(older, current)
            regroupLoadedMessages(_messages.value)
            scheduleCacheSave()
        }
    }

    fun showOlderTasks() {
        _taskWindow.update { it.revealOlder(orderedTasks = _tasks.value) }
        _loadedTaskIds.value = _taskWindow.value.loadedTaskIds
    }

    fun toggleTask(taskId: String) {
        val window = _taskWindow.value
        if (taskId in window.expandedTaskIds) {
            _messagesByTask.update { it - taskId }
            _taskWindow.update {
                it.copy(
                    expandedTaskIds = it.expandedTaskIds - taskId,
                    loadedTaskIds = it.loadedTaskIds - taskId,
                )
            }
            _loadedTaskIds.value = _taskWindow.value.loadedTaskIds
            syncFlatMessagesFromTaskMap()
            scheduleCacheSave()
            return
        }

        viewModelScope.launch {
            val loaded = loadMessagesForTask(taskId)
            _messagesByTask.update { current -> current + (taskId to loaded) }
            _taskWindow.update { it.expand(taskId) }
            _loadedTaskIds.value = _taskWindow.value.loadedTaskIds
            syncFlatMessagesFromTaskMap()
            scheduleCacheSave()
        }
    }

    fun updateDraft(text: String) {
        _uiState.update { it.copy(draft = text) }
        scheduleDraftSave(text)
    }

    fun hasMicPermission(): Boolean = promptVoice.hasMicPermission()

    fun startVoiceInput() {
        voiceDraftPrefix = _uiState.value.draft.trimEnd()
        promptVoice.start()
    }

    fun stopVoiceInput() {
        promptVoice.stop()
        if (promptVoiceState.value.phase != PromptVoicePhase.Recording) {
            voiceDraftPrefix = null
        }
    }

    fun downloadVoiceWhisperModel() {
        promptVoice.downloadWhisperModel()
    }

    fun dismissVoiceWhisperDownloadPrompt() {
        promptVoice.dismissWhisperDownloadPrompt()
    }

    fun startSessionVoiceMode() {
        ContinuousVoiceService.start(container.appContext, sessionId)
        _uiState.value.session?.let(::syncSessionVoice)
    }

    fun stopSessionVoiceMode() {
        ContinuousVoiceService.stop(container.appContext)
    }

    fun updateSessionVoiceTranscript(text: String) {
        ContinuousVoiceService.updatePendingTranscript(text)
    }

    fun sendSessionVoiceTranscriptNow() {
        ContinuousVoiceService.sendPendingTranscript()
    }

    fun cancelSessionVoiceTranscript() {
        ContinuousVoiceService.cancelPendingTranscript()
    }

    fun skipSessionVoiceTts() {
        ContinuousVoiceService.skipTts()
    }

    fun downloadSessionVoiceWhisperModel() {
        ContinuousVoiceService.downloadWhisperModel()
    }

    fun dismissSessionVoiceWhisperDownloadPrompt() {
        ContinuousVoiceService.dismissWhisperDownloadPrompt()
    }

    fun saveSessionVoiceSettings(settings: SessionVoiceSettings) {
        viewModelScope.launch { container.sessionVoiceSettings.save(settings) }
    }

    fun resetSessionVoiceSettings() {
        viewModelScope.launch { container.sessionVoiceSettings.reset() }
    }

    fun addAttachmentFromUri(uri: Uri) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { readAttachment(uri) } }
                .onSuccess { attachment ->
                    _attachments.update { current -> current + attachment }
                    AppLogger.log(
                        "Attached ${attachment.filename} (${attachment.mimeType ?: "unknown"}, ${attachment.sizeBytes} bytes)",
                        LogLevel.INFO,
                        "Chat",
                    )
                }
                .onFailure { t ->
                    AppLogger.log("Attachment read failed: ${t.message}", LogLevel.ERROR, "Chat")
                    _uiState.update { it.copy(errorMessage = t.message) }
                }
        }
    }

    fun addLogsAttachment() {
        val text = AppLogger.exportText(container.crashLogs.read())
        val filename = "agor-android-logs-${System.currentTimeMillis()}.txt"
        _attachments.update { current ->
            current + PendingSessionAttachment(
                id = UUID.randomUUID().toString(),
                filename = filename,
                mimeType = "text/plain",
                sizeBytes = text.toByteArray().size.toLong(),
                bytes = text.toByteArray(),
            )
        }
        AppLogger.log("Attached application logs as $filename", LogLevel.INFO, "Chat")
    }

    fun removeAttachment(id: String) {
        _attachments.update { current -> current.filterNot { it.id == id } }
    }

    fun sendPrompt() {
        val text = _uiState.value.draft.trim()
        val pendingAttachments = _attachments.value
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                if (pendingAttachments.isEmpty()) {
                    container.client.sendPrompt(sessionId, text)
                } else {
                    val message = uploadNotificationMessage(text)
                    val result = container.client.uploadSessionFiles(
                        sessionId = sessionId,
                        files = pendingAttachments.map { it.toUploadInput() },
                        notifyAgent = true,
                        message = message,
                    )
                    result.warning?.let { warning ->
                        AppLogger.log("Upload notification warning: $warning", LogLevel.WARNING, "Chat")
                    }
                    val uploadedPaths = result.files.map { it.path }
                    if (uploadedPaths.isNotEmpty()) {
                        _attachments.value = pendingAttachments.withUploadedPaths(uploadedPaths)
                    }
                }
                AppLogger.log(
                    "Session send completed session=${sessionId.take(8)} attachments=${pendingAttachments.size} elapsed=${SystemClock.elapsedRealtime() - started}ms",
                    LogLevel.INFO,
                    "Chat",
                )
                _uiState.update { it.copy(draft = "") }
                container.chatDrafts.clear(sessionId)
                _attachments.value = emptyList()
                schedulePostSendRefresh()
            } catch (t: Throwable) {
                AppLogger.log(
                    "Send failed session=${sessionId.take(8)} attachments=${pendingAttachments.size} elapsed=${SystemClock.elapsedRealtime() - started}ms: ${t.message}",
                    LogLevel.ERROR,
                    "Chat",
                )
                _uiState.update { it.copy(errorMessage = t.message) }
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            runCatching { container.client.stopSession(sessionId) }
        }
    }

    fun decidePermission(permissionId: String, taskId: String?, approve: Boolean) {
        viewModelScope.launch {
            val decidedBy = container.authService.user.value?.userId ?: "anonymous"
            runCatching {
                container.client.decidePermission(
                    sessionId = sessionId,
                    requestId = permissionId,
                    taskId = taskId,
                    approve = approve,
                    decidedBy = decidedBy,
                )
            }
        }
    }

    fun answerInputRequest(request: InputRequestContent, taskId: String?, answers: List<String>) {
        viewModelScope.launch {
            val respondedBy = container.authService.user.value?.userId ?: "anonymous"
            runCatching {
                container.client.answerInputRequest(
                    sessionId = sessionId,
                    requestId = request.inputRequestId,
                    taskId = taskId,
                    answers = answersByQuestion(request, answers),
                    respondedBy = respondedBy,
                )
            }
        }
    }

    fun archiveSession() {
        viewModelScope.launch {
            runCatching {
                container.client.patchSession(
                    sessionId,
                    JsonObject(mapOf("archived" to JsonPrimitive(true))),
                )
            }
        }
    }

    fun resetSession(onCreated: (String) -> Unit) {
        val current = _uiState.value.session ?: return
        viewModelScope.launch {
            runCatching {
                container.client.patchSession(
                    sessionId,
                    JsonObject(
                        mapOf(
                            "archived" to JsonPrimitive(true),
                            "archived_reason" to JsonPrimitive("reset"),
                        ),
                    ),
                )
                container.client.createSession(
                    worktreeId = current.worktreeId,
                    agenticTool = current.agenticTool,
                    title = current.title,
                    permissionConfig = current.permissionConfig,
                    modelConfig = current.modelConfig,
                )
            }.onSuccess { fresh ->
                onCreated(fresh.sessionId)
            }.onFailure { error ->
                AppLogger.log("Reset session failed: ${error.message}", LogLevel.ERROR, "Chat")
                _uiState.update { it.copy(errorMessage = error.message) }
            }
        }
    }

    fun renameSession(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                container.client.patchSession(
                    sessionId,
                    JsonObject(mapOf("title" to JsonPrimitive(trimmed))),
                )
            }.onSuccess { updated ->
                _uiState.update { it.copy(session = updated, errorMessage = null) }
                scheduleCacheSave()
            }.onFailure { error ->
                AppLogger.log("Rename failed: ${error.message}", LogLevel.ERROR, "Chat")
                _uiState.update { it.copy(errorMessage = error.message) }
            }
        }
    }

    fun changePermissionMode(mode: PermissionMode) {
        viewModelScope.launch {
            runCatching {
                container.client.patchSession(sessionId, sessionPermissionModePayload(mode))
            }.onSuccess { updated ->
                _uiState.update { it.copy(session = updated, errorMessage = null) }
                scheduleCacheSave()
            }.onFailure { error ->
                AppLogger.log("Permission mode update failed: ${error.message}", LogLevel.ERROR, "Chat")
                _uiState.update { it.copy(errorMessage = error.message) }
            }
        }
    }

    override fun onCleared() {
        promptVoice.release()
        super.onCleared()
    }

    /**
     * Replace-or-insert [message] in [_messages] while keeping the list sorted
     * by [Message.index]. O(log n) search + O(n) array-list insert; the previous
     * implementation was O(n) filter + O(n log n) sort on every socket event.
     */
    private fun upsertSorted(message: Message) {
        _messages.update { current ->
            val withoutDup = if (current.any { it.messageId == message.messageId }) {
                current.filterNot { it.messageId == message.messageId }
            } else {
                current
            }
            val pos = withoutDup.binarySearch { it.index.compareTo(message.index) }
            val insertIdx = if (pos < 0) -(pos + 1) else pos
            val out = ArrayList<Message>(withoutDup.size + 1)
            out.addAll(withoutDup)
            if (insertIdx >= out.size) out.add(message) else out.add(insertIdx, message)
            out
        }
        upsertTaskMessage(message)
        // Drop the streaming buffer for this id once the canonical message lands.
        container.streaming.finalize(message.messageId)
        scheduleCacheSave()
    }

    private fun scheduleCacheSave() {
        val session = _uiState.value.session ?: return
        cacheSaveJob?.cancel()
        cacheSaveJob = viewModelScope.launch {
            delay(500)
            container.chatCache.save(session, _tasks.value, _messages.value)
        }
    }

    private fun scheduleDraftSave(text: String) {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(300)
            container.chatDrafts.save(sessionId, text)
        }
    }

    private fun schedulePostSendRefresh() {
        postSendRefreshJob?.cancel()
        postSendRefreshJob = viewModelScope.launch {
            delay(1_200)
            runCatching {
                val tasks = container.client.listTasks(sessionId)
                val latestMessages = container.client.listMessages(sessionId, limit = 200)
                val orderedTasks = taskCentricTasks(sessionId, tasks, latestMessages)
                _tasks.value = orderedTasks
                val currentLatest = orderedTasks.lastOrNull()?.taskId
                val merged = mergeMessages(_messages.value, latestMessages)
                _messages.value = merged
                if (currentLatest != null) {
                    _taskWindow.update { it.expand(currentLatest) }
                }
                regroupLoadedMessages(merged)
                _loadedTaskIds.value = _taskWindow.value.loadedTaskIds
                scheduleCacheSave()
            }.onFailure { error ->
                AppLogger.log(
                    "Post-send refresh failed session=${sessionId.take(8)}: ${error.message}",
                    LogLevel.WARNING,
                    "Chat",
                )
            }
        }
    }

    private fun applyInitialTaskCentricState(
        rawTasks: List<AgorTask>,
        messages: List<Message>,
    ) {
        val orderedTasks = taskCentricTasks(sessionId, rawTasks, messages)
        val grouped = groupMessagesByTask(messages, orderedTasks)
        val state = TaskCentricChat.initialState(orderedTasks, grouped)
        _tasks.value = state.orderedTasks
        _messagesByTask.value = state.messagesByTask
        _taskWindow.value = state.window
        _loadedTaskIds.value = state.window.loadedTaskIds
        syncFlatMessagesFromTaskMap()
    }

    private suspend fun loadMessagesForTask(taskId: String): List<Message> =
        if (taskId == TaskCentricChat.VirtualTaskId) {
            container.client.listMessages(sessionId, limit = 200)
        } else {
            runCatching {
                container.client.listMessages(sessionId, limit = 200, taskId = taskId)
            }.getOrElse {
                _messages.value.filter { message -> message.taskId == taskId }
            }
        }

    private fun regroupLoadedMessages(messages: List<Message>) {
        val grouped = groupMessagesByTask(messages, _tasks.value)
        _messagesByTask.value = grouped.filterKeys { it in _taskWindow.value.loadedTaskIds }
        syncFlatMessagesFromTaskMap()
    }

    private fun upsertTaskMessage(message: Message) {
        ensureVirtualTaskFor(message)
        val taskId = message.taskId ?: TaskCentricChat.VirtualTaskId
        val taskKnown = _tasks.value.any { it.taskId == taskId }
        if (!taskKnown) return
        val isLatestTask = _tasks.value.lastOrNull()?.taskId == taskId
        if (isLatestTask || _taskWindow.value.visibleTaskIds.isEmpty()) {
            _taskWindow.update { it.expand(taskId) }
            _loadedTaskIds.value = _taskWindow.value.loadedTaskIds
        }
        if (taskId !in _taskWindow.value.loadedTaskIds) return
        _messagesByTask.update { current ->
            val existing = current[taskId].orEmpty()
            val merged = mergeMessages(existing, listOf(message))
            current + (taskId to merged)
        }
        syncFlatMessagesFromTaskMap()
    }

    private fun ensureVirtualTaskFor(message: Message) {
        if (message.taskId != null) return
        if (_tasks.value.any { it.taskId == TaskCentricChat.VirtualTaskId }) return
        if (_tasks.value.isNotEmpty()) return
        _tasks.value = taskCentricTasks(sessionId, emptyList(), _messages.value.ifEmpty { listOf(message) })
        _taskWindow.value = TaskCentricChat.initialWindow(_tasks.value)
        _loadedTaskIds.value = _taskWindow.value.loadedTaskIds
    }

    private fun syncFlatMessagesFromTaskMap() {
        _messages.value = _messagesByTask.value.values
            .flatten()
            .distinctBy { it.messageId }
            .sortedBy { it.index }
    }

    private fun mergeMessages(
        existing: List<Message>,
        incoming: List<Message>,
    ): List<Message> {
        if (existing.isEmpty()) return incoming.sortedBy { it.index }
        if (incoming.isEmpty()) return existing.sortedBy { it.index }
        val byId = LinkedHashMap<String, Message>(existing.size + incoming.size)
        for (m in existing) byId[m.messageId] = m
        for (m in incoming) byId[m.messageId] = m
        return byId.values.sortedBy { it.index }
    }

    private fun applyVoiceDraft(text: String, final: Boolean) {
        val transcript = text.trim()
        if (transcript.isBlank()) return
        _uiState.update { current ->
            val prefix = voiceDraftPrefix ?: current.draft.trimEnd().also { voiceDraftPrefix = it }
            val next = if (prefix.isBlank()) transcript else "$prefix $transcript"
            scheduleDraftSave(next)
            current.copy(draft = next)
        }
        if (final) voiceDraftPrefix = null
    }

    private fun syncSessionVoice(session: Session) {
        ContinuousVoiceService.updatePromptability(
            sessionId = session.sessionId,
            canPrompt = SessionVoicePolicy.isPromptable(session.status, session.readyForPrompt),
            status = session.status,
        )
    }

    private fun speakAssistantIfNeeded(message: Message) {
        if (message.role != MessageRole.ASSISTANT) return
        if (sessionVoiceState.value.activeSessionId != sessionId) return
        val text = assistantText(message).trim()
        if (text.isBlank()) return
        ContinuousVoiceService.speakAssistant(message.messageId, text)
    }

    private fun assistantText(message: Message): String = when (val content = message.content) {
        is MessageContent.Text -> content.text
        is MessageContent.Blocks -> content.blocks.asSequence()
            .mapNotNull { block ->
                when (block) {
                    is ContentBlock.Text -> block.text
                    else -> null
                }
            }
            .filterNot { it.trimStart().startsWith("```") }
            .joinToString("\n")
        is MessageContent.Permission,
        is MessageContent.InputRequest -> ""
    }

    private fun readAttachment(uri: Uri): PendingSessionAttachment {
        val resolver = container.appContext.contentResolver
        val metadata = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
                name to size
            } else {
                null to -1L
            }
        } ?: (null to -1L)

        val filename = metadata.first?.takeIf { it.isNotBlank() }
            ?: "attachment-${System.currentTimeMillis()}"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not read attachment")
        if (bytes.size > 50 * 1024 * 1024) {
            throw IOException("Attachment exceeds the 50 MB upload limit")
        }
        val mimeType = inferUploadMimeType(resolver.getType(uri), filename)
        return PendingSessionAttachment(
            id = UUID.randomUUID().toString(),
            filename = filename,
            mimeType = mimeType,
            sizeBytes = if (metadata.second >= 0) metadata.second else bytes.size.toLong(),
            bytes = bytes,
        )
    }

    /** Walks the (already sorted) message list from the end. */
    private fun firstPendingPermission(messages: List<Message>): String? {
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            val c = m.content
            if (c is MessageContent.Permission && c.request.status == PermissionStatus.PENDING) {
                return c.request.permissionId
            }
        }
        return null
    }

    private fun firstPendingInput(messages: List<Message>): String? {
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            val c = m.content
            if (c is MessageContent.InputRequest && c.request.status == InputRequestStatus.PENDING) {
                return c.request.inputRequestId
            }
        }
        return null
    }
}

internal fun uploadNotificationMessage(prompt: String): String {
    val trimmed = prompt.trim()
    return if (trimmed.isBlank()) {
        "Attached file(s): {filepath}\n\nPlease review these attached files."
    } else {
        "$trimmed\n\nAttached file(s): {filepath}"
    }
}

internal fun answersByQuestion(
    request: InputRequestContent,
    answers: List<String>,
): Map<String, String> {
    if (answers.isEmpty()) return emptyMap()
    val questions = request.questions
    if (questions.isEmpty()) return mapOf("answer" to answers.joinToString(", "))
    if (questions.size == 1) return mapOf(questions.first().question to answers.joinToString(", "))
    return questions.mapIndexedNotNull { index, question ->
        val answer = answers.getOrNull(index)?.trim().orEmpty()
        if (answer.isEmpty()) null else question.question to answer
    }.toMap()
}

internal fun List<ChatViewModel.PendingSessionAttachment>.withUploadedPaths(
    paths: List<String>,
): List<ChatViewModel.PendingSessionAttachment> =
    mapIndexed { index, attachment ->
        attachment.copy(uploadedPath = paths.getOrNull(index))
    }

private fun inferUploadMimeType(contentResolverType: String?, filename: String): String? {
    val provided = contentResolverType?.substringBefore(';')?.lowercase()
    if (!provided.isNullOrBlank() && provided != "application/octet-stream") return provided
    return when (filename.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "txt", "log" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "zip" -> "application/zip"
        "gz" -> "application/gzip"
        "tar" -> "application/x-tar"
        else -> contentResolverType
    }
}
