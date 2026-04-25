package live.agor.app.notifications

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import live.agor.app.MainActivity
import live.agor.app.R

/**
 * Local-notification dispatch for "favorited session finished" and similar events.
 * Notification IDs are derived deterministically from session id + state so duplicate
 * triggers (socket reconnect + missed transition check) collapse to a single notification.
 */
class AgorNotificationManager(private val context: Context) {

    fun notifySessionIdle(sessionId: String, title: String, sessionUrl: String?) {
        if (!hasPostNotifPermission()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            sessionUrl?.let { data = android.net.Uri.parse(it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(context, NotificationChannels.SESSIONS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("Session finished")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context)
            .notify("session-$sessionId-idle", 0, notification)
    }

    fun cancelSessionIdle(sessionId: String) {
        NotificationManagerCompat.from(context).cancel("session-$sessionId-idle", 0)
    }

    private fun hasPostNotifPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
