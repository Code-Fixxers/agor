package live.agor.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import live.agor.app.notifications.NotificationChannels
import live.agor.app.notifications.SessionTransitionPollWorker

class AgorApplication : Application(), ImageLoaderFactory {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.crashLogs.install()
        registerNotificationChannels()
        SessionTransitionPollWorker.schedule(this)
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

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
}
