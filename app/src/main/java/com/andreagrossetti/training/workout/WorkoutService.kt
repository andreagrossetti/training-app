package com.andreagrossetti.training.workout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.andreagrossetti.training.MainActivity
import com.andreagrossetti.training.R
import com.andreagrossetti.training.TrainingApp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Keeps the workout ticking (and beeping) with the screen off, and publishes it as a media
 * session + media notification so it shows up on the lock screen and Always On Display.
 * The media progress bar is extrapolated by the system, so updates are only needed on
 * phase changes.
 */
class WorkoutService : Service() {
    private val scope = MainScope()
    private var wakeLock: PowerManager.WakeLock? = null
    private var media: WorkoutMedia? = null
    private val engine get() = (application as TrainingApp).engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { Log.d("Workout", "notification action: $it") }
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> engine.state.value?.let { if (it.isTimed && !it.paused) engine.pause() else engine.play() }
            ACTION_NEXT -> engine.next()
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel("workout") // channel of the first version
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Allenamento in corso", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        val media = media ?: WorkoutMedia(this, engine).also { media = it }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(engine.state.value, media),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "training:workout")
                .apply { acquire(4 * 60 * 60 * 1000L) }
            scope.launch {
                engine.state
                    .distinctUntilChangedBy { s ->
                        s?.let { listOf(it.phase, it.stepIndex, it.reps, it.paused, it.durationMs, it.seekCount) }
                    }
                    .collect { state ->
                        if (state == null || state.phase == Phase.FINISHED) {
                            stopSelf()
                        } else {
                            media.update(state)
                            manager.notify(NOTIFICATION_ID, notification(state, media))
                        }
                    }
            }
            // The Always On Display does not animate the media progress bar by itself:
            // republish the session every second so both the bar and the time in the title move.
            scope.launch {
                engine.state
                    .distinctUntilChangedBy { s -> s?.takeIf { it.isTimed && !it.paused }?.remainingSeconds }
                    .collect { state ->
                        if (state != null && state.phase != Phase.FINISHED) {
                            media.update(state)
                            // Samsung's Now Bar only refreshes when the notification is reposted.
                            manager.notify(NOTIFICATION_ID, notification(state, media))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        media?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(state: WorkoutState?, media: WorkoutMedia): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val running = state != null && state.isTimed && !state.paused
        // Android 13+ builds the media controls from the session; these actions are for older versions.
        val playPause = Notification.Action.Builder(
            Icon.createWithResource(this, if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (running) "Pausa" else "Avvia",
            serviceIntent(ACTION_PLAY_PAUSE),
        ).build()
        val next = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_next),
            "Salta",
            serviceIntent(ACTION_NEXT),
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state?.let(media::title) ?: "Allenamento")
            .setContentText(state?.exercise?.name)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(playPause)
            .addAction(next)
            .setStyle(Notification.MediaStyle().setMediaSession(media.token).setShowActionsInCompactView(0, 1))
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun serviceIntent(action: String) = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, WorkoutService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val CHANNEL_ID = "workout_live"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "play_pause"
        const val ACTION_NEXT = "next"
    }
}
