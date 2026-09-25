package com.andreagrossetti.training.run

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
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.andreagrossetti.training.MainActivity
import com.andreagrossetti.training.R
import com.andreagrossetti.training.TrainingApp
import com.andreagrossetti.training.data.formatKm
import com.andreagrossetti.training.data.formatPace
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Foreground service of type "location": keeps GPS updates flowing with the screen off
 * and shows the run in an ongoing notification with a running chronometer.
 */
class RunService : Service() {
    private val scope = MainScope()
    private var wakeLock: PowerManager.WakeLock? = null
    private val tracker get() = (application as TrainingApp).tracker

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) tracker.togglePause()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Corsa in corso", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(tracker.state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "training:run")
                .apply { acquire(6 * 60 * 60 * 1000L) }
            scope.launch {
                tracker.state
                    // Repost on phase changes and every 10 m, not on every fix.
                    .distinctUntilChangedBy { s -> s?.let { it.phase to (it.distanceM / 10).toInt() } }
                    .collect { state ->
                        if (state == null) stopSelf() else manager.notify(NOTIFICATION_ID, notification(state))
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(state: RunState?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }

        when (state?.phase) {
            null, RunPhase.ACQUIRING -> builder
                .setContentTitle("Corsa")
                .setContentText(if (state?.gpsReady == true) "GPS pronto" else "Ricerca GPS…")
            RunPhase.RUNNING, RunPhase.PAUSED -> {
                val running = state.phase == RunPhase.RUNNING
                builder
                    .setContentTitle("${formatKm(state.distanceM)} km" + if (running) "" else " · in pausa")
                    .setContentText("Passo medio ${formatPace(state.averagePace())} /km")
                    .setShowWhen(running)
                    .setUsesChronometer(running)
                    .setWhen(System.currentTimeMillis() - state.movingMs(SystemClock.elapsedRealtime()))
                    .addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this, if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                            if (running) "Pausa" else "Riprendi",
                            PendingIntent.getService(
                                this, 0,
                                Intent(this, RunService::class.java).setAction(ACTION_PAUSE),
                                PendingIntent.FLAG_IMMUTABLE,
                            ),
                        ).build()
                    )
            }
        }
        return builder.build()
    }

    private companion object {
        const val CHANNEL_ID = "run_live"
        const val NOTIFICATION_ID = 2
        const val ACTION_PAUSE = "pause"
    }
}
