package live.agor.app.notifications

import live.agor.app.models.Session

class SessionIdleNotificationTracker {
    data class Candidate(val session: Session)

    private val tracker = SessionTransitionTracker()

    fun observe(sessions: List<Session>, favorites: Set<String>): List<Candidate> {
        return tracker.observe(sessions, favorites)
            .filter { it.kind == SessionTransitionTracker.EventKind.FAVORITE_IDLE }
            .map { Candidate(it.session) }
    }
}
