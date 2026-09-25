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
import com.andreagrossetti.training.data.date
import com.andreagrossetti.training.data.planned
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Daily reminders:
 * - the weekly plan's, at the chosen time (inexact, so no special permission is needed), shown only
 *   if something is planned for today and not done yet;
 * - the morning HRV reading's, on time (exact alarm), shown only if there's no reading yet today.
 */
object Reminders {
    private const val CHANNEL_ID = "reminder"
    private const val HRV_CHANNEL_ID = "hrv_reminder"
    private const val NOTIFICATION_ID = 3
    private const val HRV_NOTIFICATION_ID = 4
    private const val ACTION_HRV = "com.andreagrossetti.training.HRV_REMINDER"
    /** Opens the HRV screen from the notification. */
    const val ACTION_OPEN_HRV = "com.andreagrossetti.training.OPEN_HRV"

    fun schedule(context: Context) {
        val app = context.applicationContext as TrainingApp
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = pendingIntent(context)
        val minutes = app.settings.value.reminderMinutes
        if (minutes == null || app.settings.value.plan.isEmpty()) {
            alarm.cancel(intent)
        } else {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt(minutes), intent)
        }

        val hrvIntent = pendingIntent(context, ACTION_HRV)
        val hrvMinutes = app.settings.value.hrvReminderMinutes
        when {
            hrvMinutes == null -> alarm.cancel(hrvIntent)
            // Exact, since it's meant for the moment you wake up; USE_EXACT_ALARM is granted at install.
            Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms() ->
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt(hrvMinutes), hrvIntent)
            else -> alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt(hrvMinutes), hrvIntent)
        }
    }

    /** Next occurrence of [minutes] after midnight, epoch millis. */
    private fun nextAt(minutes: Int): Long {
        var next = LocalDate.now().atTime(LocalTime.of(minutes / 60, minutes % 60))
        if (!next.isAfter(LocalDateTime.now())) next = next.plusDays(1)
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** [force] shows it even if today's reading is done (the "Prova notifica" button). */
    fun notifyHrvIfNotMeasured(context: Context, force: Boolean = false) {
        val app = context.applicationContext as TrainingApp
        if (!force && app.hrv.measurements.value.any { it.date() == LocalDate.now() }) return
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(HRV_CHANNEL_ID, "Promemoria misura HRV", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_HRV).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            HRV_NOTIFICATION_ID,
            Notification.Builder(context, HRV_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Buongiorno! Misura l'HRV")
                .setContentText("Resta disteso, metti il dito nel CorSense e tocca qui: 2 minuti.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Removes the HRV reminder once the reading is done. */
    fun cancelHrvNotification(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(HRV_NOTIFICATION_ID)

    private fun canNotify(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notifyIfPlanned(context: Context) {
        val app = context.applicationContext as TrainingApp
        val today = planned(LocalDate.now(), app.settings.value, app.repository.programs.value, app.log.entries.value)
            ?: return
        if (today.done || !canNotify(context)) return
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

    private fun pendingIntent(context: Context, action: String? = null) = PendingIntent.getBroadcast(
        context, if (action == null) 0 else 1, Intent(context, ReminderReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    fun isHrvAlarm(intent: Intent) = intent.action == ACTION_HRV
}

/** Fires the daily reminders, and re-arms the alarms after a reboot. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when {
            Reminders.isHrvAlarm(intent) -> Reminders.notifyHrvIfNotMeasured(context)
            intent.action != Intent.ACTION_BOOT_COMPLETED -> Reminders.notifyIfPlanned(context)
        }
        Reminders.schedule(context)
    }
}
