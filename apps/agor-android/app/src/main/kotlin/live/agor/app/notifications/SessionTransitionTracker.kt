package live.agor.app.notifications

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import live.agor.app.models.Session
import live.agor.app.models.SessionStatus

class SessionTransitionTracker {
    enum class EventKind {
        FAVORITE_IDLE,
        AWAITING_PERMISSION,
        AWAITING_INPUT,
        COMPLETED,
        FAILED,
    }

    data class Event(
        val session: Session,
        val kind: EventKind,
    ) {
        val message: String
            get() = when (kind) {
                EventKind.FAVORITE_IDLE -> "${session.displayTitle} finished"
                EventKind.AWAITING_PERMISSION -> "${session.displayTitle} needs permission"
                EventKind.AWAITING_INPUT -> "${session.displayTitle} needs input"
                EventKind.COMPLETED -> "${session.displayTitle} completed"
                EventKind.FAILED -> "${session.displayTitle} failed"
            }
    }

    private data class TrackedSession(
        val status: SessionStatus,
        val updatedAtMillis: Long?,
        val notifiedAtByKind: Map<EventKind, Long?>,
    )

    private val tracked = mutableMapOf<String, TrackedSession>()

    fun observe(sessions: List<Session>, favorites: Set<String>): List<Event> {
        return sessions.mapNotNull { observeOne(it, favorites) }
    }

    private fun observeOne(session: Session, favorites: Set<String>): Event? {
        val sessionId = session.sessionId
        val previous = tracked[sessionId]
        val updatedAtMillis = parseEpochMillis(session.lastUpdated) ?: parseEpochMillis(session.createdAt)
        if (previous != null &&
            previous.updatedAtMillis != null &&
            updatedAtMillis != null &&
            updatedAtMillis < previous.updatedAtMillis
        ) {
            return null
        }

        val kind = eventKindFor(previous?.status, session.status, sessionId, favorites)
        val event = if (kind != null && wasNotAlreadyEmitted(kind, updatedAtMillis, previous)) {
            Event(session, kind)
        } else {
            null
        }
        val nextNotified = if (event != null) {
            previous?.notifiedAtByKind.orEmpty() + (event.kind to (updatedAtMillis ?: previous?.updatedAtMillis))
        } else {
            previous?.notifiedAtByKind.orEmpty()
        }
        tracked[sessionId] = TrackedSession(
            status = session.status,
            updatedAtMillis = updatedAtMillis,
            notifiedAtByKind = nextNotified,
        )

        return event
    }

    private fun eventKindFor(
        previous: SessionStatus?,
        current: SessionStatus,
        sessionId: String,
        favorites: Set<String>,
    ): EventKind? {
        if (previous == null || previous == current) return null
        if (previous == SessionStatus.RUNNING && current == SessionStatus.IDLE && favorites.contains(sessionId)) {
            return EventKind.FAVORITE_IDLE
        }
        return when (current) {
            SessionStatus.AWAITING_PERMISSION -> EventKind.AWAITING_PERMISSION
            SessionStatus.AWAITING_INPUT -> EventKind.AWAITING_INPUT
            SessionStatus.COMPLETED -> EventKind.COMPLETED
            SessionStatus.FAILED -> EventKind.FAILED
            else -> null
        }
    }

    private fun wasNotAlreadyEmitted(
        kind: EventKind,
        updatedAtMillis: Long?,
        previous: TrackedSession?,
    ): Boolean {
        val notifiedAt = previous?.notifiedAtByKind?.get(kind) ?: return true
        return updatedAtMillis != null && updatedAtMillis > notifiedAt
    }

    private fun parseEpochMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }
            .getOrElse {
                runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                    .getOrElse {
                        runCatching {
                            LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
                        }.getOrNull()
                    }
            }
    }
}
