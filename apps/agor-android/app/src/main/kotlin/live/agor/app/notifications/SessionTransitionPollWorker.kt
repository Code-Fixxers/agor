package live.agor.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import live.agor.app.auth.SecureTokenStore
import live.agor.app.models.Session
import live.agor.app.models.SessionStatus
import live.agor.app.network.AgorClient
import live.agor.app.util.AppLogger
import live.agor.app.util.LogLevel

class SessionTransitionPollWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val tokenStore = SecureTokenStore(applicationContext)
        if (tokenStore.serverUrl.isNullOrBlank() || tokenStore.accessToken.isNullOrBlank()) {
            return Result.success()
        }

        return runCatching {
            val client = AgorClient(tokenStore)
            val sessions = client.listSessions(compact = true, includeArchived = false)
            val events = BackgroundSessionTransitionStore(applicationContext).observe(sessions)
            val notifications = AgorNotificationManager(applicationContext)
            events.forEach { event ->
                notifications.notifySessionTransition(
                    sessionId = event.session.sessionId,
                    title = event.session.displayTitle,
                    message = event.message,
                    sessionUrl = event.session.url,
                    tagSuffix = event.kind.name.lowercase(),
                )
            }
            AppLogger.log("Background transition poll checked ${sessions.size} sessions, events=${events.size}", LogLevel.DEBUG, "Nav")
            Result.success()
        }.getOrElse { error ->
            AppLogger.log("Background transition poll failed: ${error.message}", LogLevel.WARNING, "Nav")
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "session-transition-poll"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SessionTransitionPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
        }
    }
}

private class BackgroundSessionTransitionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session_transition_poll", Context.MODE_PRIVATE)

    fun observe(sessions: List<Session>): List<SessionTransitionTracker.Event> {
        val events = sessions.mapNotNull(::eventFor)
        prefs.edit().apply {
            sessions.forEach { session ->
                putString(session.sessionId, "${session.status.name}|${session.lastUpdated}")
            }
        }.apply()
        return events
    }

    private fun eventFor(session: Session): SessionTransitionTracker.Event? {
        val previousStatus = prefs.getString(session.sessionId, null)
            ?.substringBefore('|')
            ?.let { runCatching { SessionStatus.valueOf(it) }.getOrNull() }
            ?: return null
        if (previousStatus == session.status) return null
        val kind = when (session.status) {
            SessionStatus.AWAITING_PERMISSION -> SessionTransitionTracker.EventKind.AWAITING_PERMISSION
            SessionStatus.AWAITING_INPUT -> SessionTransitionTracker.EventKind.AWAITING_INPUT
            SessionStatus.COMPLETED -> SessionTransitionTracker.EventKind.COMPLETED
            SessionStatus.FAILED -> SessionTransitionTracker.EventKind.FAILED
            else -> return null
        }
        return SessionTransitionTracker.Event(session, kind)
    }
}
