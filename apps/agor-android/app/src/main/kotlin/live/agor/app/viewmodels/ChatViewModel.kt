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
import live.agor.app.models.InputRequestStatus
import live.agor.app.models.Message
import live.agor.app.models.MessageContent
import live.agor.app.models.PermissionStatus
import live.agor.app.models.Session
import live.agor.app.network.UploadFileInput
import live.agor.app.network.StreamingService
import live.agor.app.ui.chat.ChatRow
import live.agor.app.ui.chat.ChatRowFlattener
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import live.agor.app.voice.PromptVoiceInputController
import live.agor.app.voice.PromptVoicePhase
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

    /** Mirrors [StreamingService.live] directly; no extra copy. */
    val live: StateFlow<Map<String, StreamingService.StreamSnapshot>> = container.streaming.live

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _attachments = MutableStateFlow<List<PendingSessionAttachment>>(emptyList())
    val attachments: StateFlow<List<PendingSessionAttachment>> = _attachments.asStateFlow()

    private val promptVoice = PromptVoiceInputController(
        container.appContext,
        container.tokenStore,
        container.voiceModels,
    )
    val promptVoiceState = promptVoice.state

    private val rowFlattener = ChatRowFlattener()
    private var cacheSaveJob: Job? = null
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
    private val rowStructure: StateFlow<ChatRowFlattener.Structure> = combine(
        _messages,
        _tasks,
    ) { m, t -> rowFlattener.buildStructure(m, t, m.size >= 100) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ChatRowFlattener.Structure(showLoadEarlier = false, messageLayouts = emptyList()),
        )

    val rows: StateFlow<List<ChatRow>> = combine(
        rowStructure,
        live,
    ) { structure, l -> rowFlattener.render(structure, l) }
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
            viewModelScope.launch { upsertSorted(msg) }
        }
        container.socket.onMessagePatched { msg ->
            if (msg.sessionId != sessionId) return@onMessagePatched
            viewModelScope.launch { upsertSorted(msg) }
        }
        container.socket.onTaskCreated { t ->
            if (t.sessionId != sessionId) return@onTaskCreated
            viewModelScope.launch {
                _tasks.update { current -> current + t }
                scheduleCacheSave()
            }
        }
        container.socket.onTaskPatched { t ->
            if (t.sessionId != sessionId) return@onTaskPatched
            viewModelScope.launch {
                _tasks.update { current ->
                    val idx = current.indexOfFirst { it.taskId == t.taskId }
                    if (idx < 0) current + t
                    else current.toMutableList().apply { set(idx, t) }
                }
                scheduleCacheSave()
            }
        }
        container.socket.onSessionPatched { s ->
            if (s.sessionId != sessionId) return@onSessionPatched
            viewModelScope.launch {
                _uiState.update { it.copy(session = s) }
                scheduleCacheSave()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val cached = container.chatCache.load(sessionId)
            if (cached != null) {
                _messages.value = cached.messages.sortedBy { it.index }
                _tasks.value = cached.tasks
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
                _messages.value = mergeMessages(cached?.messages.orEmpty(), latestMessages)
                _tasks.value = tasks
                _uiState.update {
                    it.copy(session = session, isLoading = false, errorMessage = null)
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                AppLogger.log(
                    "Chat $sessionId refresh loaded ${tasks.size} tasks and " +
                        "${latestMessages.size} latest messages in ${elapsed}ms",
                    LogLevel.DEBUG,
                    "Perf",
                )
                container.chatCache.save(session, tasks, _messages.value)
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
            scheduleCacheSave()
        }
    }

    fun updateDraft(text: String) {
        _uiState.update { it.copy(draft = text) }
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
        val text = AppLogger.exportText()
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
                    val message = if (text.isBlank()) {
                        "Attached file(s): {filepath}\n\nPlease review these attached files."
                    } else {
                        "$text\n\nAttached file(s): {filepath}"
                    }
                    val result = container.client.uploadSessionFiles(
                        sessionId = sessionId,
                        files = pendingAttachments.map { it.toUploadInput() },
                        notifyAgent = true,
                        message = message,
                    )
                    result.warning?.let { warning ->
                        AppLogger.log("Upload notification warning: $warning", LogLevel.WARNING, "Chat")
                    }
                }
                AppLogger.log(
                    "Session send completed session=${sessionId.take(8)} attachments=${pendingAttachments.size} elapsed=${SystemClock.elapsedRealtime() - started}ms",
                    LogLevel.INFO,
                    "Chat",
                )
                _uiState.update { it.copy(draft = "") }
                _attachments.value = emptyList()
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

    fun decidePermission(permissionId: String, approve: Boolean) {
        viewModelScope.launch {
            runCatching { container.client.decidePermission(sessionId, permissionId, approve) }
        }
    }

    fun answerInputRequest(inputRequestId: String, answers: List<String>) {
        viewModelScope.launch {
            runCatching { container.client.answerInputRequest(sessionId, inputRequestId, answers) }
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
            current.copy(draft = next)
        }
        if (final) voiceDraftPrefix = null
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
