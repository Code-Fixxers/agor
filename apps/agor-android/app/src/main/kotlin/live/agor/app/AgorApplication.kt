package live.agor.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import live.agor.app.notifications.NotificationChannels

class AgorApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannels()
    }

    private fun registerNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val voice = NotificationChannel(
            NotificationChannels.VOICE,
            getString(R.string.voice_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.voice_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(voice)

        val sessions = NotificationChannel(
            NotificationChannels.SESSIONS,
            getString(R.string.session_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.session_channel_description)
        }
        nm.createNotificationChannel(sessions)

        val hermes = NotificationChannel(
            NotificationChannels.HERMES,
            getString(R.string.hermes_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.hermes_channel_description)
        }
        nm.createNotificationChannel(hermes)
    }
}
