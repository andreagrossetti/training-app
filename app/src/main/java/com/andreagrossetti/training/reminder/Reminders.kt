package com.andreagrossetti.training.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.andreagrossetti.training.MainActivity
import com.andreagrossetti.training.R
import com.andreagrossetti.training.TrainingApp
import com.andreagrossetti.training.data.planned
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Daily reminder for the weekly plan. The alarm fires every day at the chosen time (inexact,
 * so no special permission is needed); the receiver only notifies if something is planned
 * for today and not done yet.
 */
object Reminders {
    private const val CHANNEL_ID = "reminder"
    private const val NOTIFICATION_ID = 3

    fun schedule(context: Context) {
        val app = context.applicationContext as TrainingApp
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = pendingIntent(context)
        val minutes = app.settings.value.reminderMinutes
        if (minutes == null || app.settings.value.plan.isEmpty()) {
            alarm.cancel(intent)
            return
        }
        val time = LocalTime.of(minutes / 60, minutes % 60)
        var next = LocalDate.now().atTime(time)
        if (!next.isAfter(LocalDateTime.now())) next = next.plusDays(1)
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    fun notifyIfPlanned(context: Context) {
        val app = context.applicationContext as TrainingApp
        val today = planned(LocalDate.now(), app.settings.value, app.repository.programs.value, app.log.entries.value)
            ?: return
        if (today.done) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Promemoria allenamento", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Oggi: ${today.title}")
                .setContentText(if (today.isRun) "È il giorno della corsa, scarpe ai piedi!" else "Il tuo allenamento ti aspetta.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Fires the daily reminder, and re-arms the alarm after a reboot. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) Reminders.notifyIfPlanned(context)
        Reminders.schedule(context)
    }
}
