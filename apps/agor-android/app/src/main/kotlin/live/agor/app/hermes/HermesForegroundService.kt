package live.agor.app.hermes

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import live.agor.app.AgorApplication
import live.agor.app.AppContainer
import live.agor.app.MainActivity
import live.agor.app.R
import live.agor.app.data.HermesAttachment
import live.agor.app.network.AgorJson
import live.agor.app.network.HermesResponseEvent
import live.agor.app.notifications.NotificationChannels
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel
import java.util.concurrent.ConcurrentHashMap

class HermesForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val queuedPrompts = ConcurrentHashMap<String, ArrayDeque<QueuedPrompt>>()
    private lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = (application as AgorApplication).container
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_STICKY
                val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
                val images = intent.getStringArrayListExtra(EXTRA_IMAGE_DATA_URLS).orEmpty()
                val attachments = decodeAttachments(intent.getStringExtra(EXTRA_ATTACHMENTS_JSON))
                enqueueOrStart(sessionId, prompt, images, attachments)
            }
            ACTION_CANCEL -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_STICKY
                activeJobs.remove(sessionId)?.cancel()
                scope.launch { container.hermesSessions.cancelSession(sessionId) }
                if (activeJobs.isEmpty()) stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startSessionRun(
        sessionId: String,
        prompt: String,
        imageDataUrls: List<String>,
        attachments: List<HermesAttachment>,
    ) {
        val job = scope.launch {
            val session = container.hermesSessions.getSession(sessionId)
            ?: container.hermesSessions.createSession(prompt)
            val turnId = container.hermesSessions.beginTurn(session.id, prompt, attachments)
            val reply = StringBuilder()
            var completed = false
            try {
                container.hermesClient.responseStream(
                    conversationId = session.conversationId,
                    prompt = prompt,
                    imageDataUrls = imageDataUrls,
                ).collect { event ->
                    when (event) {
                        is HermesResponseEvent.TextDelta -> {
                            reply.append(event.text)
                            container.hermesSessions.appendAssistantDelta(session.id, turnId, event.text)
                        }
                        is HermesResponseEvent.Progress -> {
                            container.hermesSessions.appendProgress(session.id, turnId, event.label)
                        }
                        is HermesResponseEvent.Completed -> {
                            completed = true
                            container.hermesSessions.completeAssistant(
                                sessionId = session.id,
                                turnId = turnId,
                                responseId = event.responseId,
                                finalText = event.outputText.ifBlank { reply.toString() },
                            )
                        }
                        is HermesResponseEvent.Failed -> throw IllegalStateException(event.message)
                    }
                }
                if (!completed) {
                    container.hermesSessions.completeAssistant(
                        sessionId = session.id,
                        turnId = turnId,
                        responseId = null,
                        finalText = reply.toString(),
                    )
                }
                val completedSession = container.hermesSessions.getSession(session.id)
                container.notifications.notifyHermesCompleted(session.id, completedSession?.title ?: "Hermes session")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    container.hermesSessions.cancelSession(session.id)
                } else {
                    AppLogger.log("Hermes foreground run failed: ${t.message}", LogLevel.WARNING, "Hermes")
                    container.hermesSessions.failAssistant(session.id, turnId, t.message ?: "Hermes run failed")
                }
            } finally {
                activeJobs.remove(session.id)
                val nextPrompt = dequeueNextPrompt(session.id)
                if (nextPrompt != null) {
                    AppLogger.log(
                        "Hermes dequeued queued prompt session=${session.id.take(8)} remaining=${queueDepth(session.id)}",
                        LogLevel.INFO,
                        "Hermes",
                    )
                    startSessionRun(
                        sessionId = session.id,
                        prompt = nextPrompt.prompt,
                        imageDataUrls = nextPrompt.imageDataUrls,
                        attachments = nextPrompt.attachments,
                    )
                } else if (activeJobs.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    startForeground(NOTIF_ID, buildNotification(activeJobs.size))
                }
            }
        }
        activeJobs[sessionId] = job
    }

    private fun enqueueOrStart(
        sessionId: String,
        prompt: String,
        imageDataUrls: List<String>,
        attachments: List<HermesAttachment>,
    ) {
        if (activeJobs.containsKey(sessionId)) {
            val queue = queuedPrompts.getOrPut(sessionId) { ArrayDeque() }
            val depth = synchronized(queue) {
                queue.addLast(QueuedPrompt(prompt, imageDataUrls, attachments))
                queue.size
            }
            AppLogger.log(
                "Hermes prompt queued session=${sessionId.take(8)} depth=$depth",
                LogLevel.INFO,
                "Hermes",
            )
            return
        }
        startForeground(NOTIF_ID, buildNotification(activeJobs.size + 1))
        startSessionRun(sessionId, prompt, imageDataUrls, attachments)
    }

    private fun dequeueNextPrompt(sessionId: String): QueuedPrompt? {
        val queue = queuedPrompts[sessionId] ?: return null
        return synchronized(queue) {
            val next = queue.removeFirstOrNull()
            if (queue.isEmpty()) queuedPrompts.remove(sessionId)
            next
        }
    }

    private fun queueDepth(sessionId: String): Int {
        val queue = queuedPrompts[sessionId] ?: return 0
        return synchronized(queue) { queue.size }
    }

    private fun buildNotification(activeCount: Int): Notification {
        val pi = PendingIntent.getActivity(
            this,
            5252,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationChannels.HERMES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.hermes_notification_title))
            .setContentText(
                if (activeCount <= 1) getString(R.string.hermes_notification_text)
                else "$activeCount Hermes sessions are running",
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun decodeAttachments(raw: String?): List<HermesAttachment> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            AgorJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(HermesAttachment.serializer()),
                raw,
            )
        }.getOrDefault(emptyList())
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_SEND = "live.agor.app.action.HERMES_SEND"
        private const val ACTION_CANCEL = "live.agor.app.action.HERMES_CANCEL"
        private const val EXTRA_SESSION_ID = "live.agor.app.extra.HERMES_SESSION_ID"
        private const val EXTRA_PROMPT = "live.agor.app.extra.HERMES_PROMPT"
        private const val EXTRA_IMAGE_DATA_URLS = "live.agor.app.extra.HERMES_IMAGE_DATA_URLS"
        private const val EXTRA_ATTACHMENTS_JSON = "live.agor.app.extra.HERMES_ATTACHMENTS_JSON"
        private const val NOTIF_ID = 5252

        private data class QueuedPrompt(
            val prompt: String,
            val imageDataUrls: List<String>,
            val attachments: List<HermesAttachment>,
        )

        fun startPrompt(
            context: Context,
            sessionId: String,
            prompt: String,
            imageDataUrls: List<String>,
            attachments: List<HermesAttachment>,
        ) {
            val intent = Intent(context, HermesForegroundService::class.java).apply {
                action = ACTION_SEND
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_PROMPT, prompt)
                putStringArrayListExtra(EXTRA_IMAGE_DATA_URLS, ArrayList(imageDataUrls))
                putExtra(
                    EXTRA_ATTACHMENTS_JSON,
                    AgorJson.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(HermesAttachment.serializer()),
                        attachments,
                    ),
                )
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context, sessionId: String) {
            val intent = Intent(context, HermesForegroundService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            context.startService(intent)
        }
    }
}
