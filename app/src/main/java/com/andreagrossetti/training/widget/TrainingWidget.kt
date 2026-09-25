package com.andreagrossetti.training.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.andreagrossetti.training.MainActivity
import com.andreagrossetti.training.R
import com.andreagrossetti.training.TrainingApp
import com.andreagrossetti.training.data.date
import com.andreagrossetti.training.data.planned
import com.andreagrossetti.training.data.streak
import com.andreagrossetti.training.ui.estimatedMinutes
import java.time.LocalDate

/** Home screen widget: streak, today's plan, quick start of the planned workout or a run. */
class TrainingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)

    companion object {
        const val ACTION_START_RUN = "com.andreagrossetti.training.START_RUN"
        const val ACTION_START_PROGRAM = "com.andreagrossetti.training.START_PROGRAM"
        const val EXTRA_PROGRAM_ID = "program_id"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TrainingWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val app = context.applicationContext as TrainingApp
            val entries = app.log.entries.value
            val today = LocalDate.now()
            val days = streak(entries.map { it.date() }.toSet(), today)
            val plan = planned(today, app.settings.value, app.repository.programs.value, entries)

            return RemoteViews(context.packageName, R.layout.widget).apply {
                setTextViewText(R.id.streak, if (days > 0) "🔥 ${if (days == 1) "1 GIORNO" else "$days GIORNI"} DI FILA" else "INIZIA LA SERIE OGGI")
                setOnClickPendingIntent(R.id.widget_root, open(context, null))
                setOnClickPendingIntent(R.id.run, open(context, Intent(ACTION_START_RUN)))
                when {
                    plan == null -> {
                        setTextViewText(R.id.title, "Allenamento")
                        setTextViewText(R.id.subtitle, "Nessun piano per oggi")
                        setTextViewText(R.id.primary, "Programmi")
                        setOnClickPendingIntent(R.id.primary, open(context, null))
                        setViewVisibility(R.id.run, View.VISIBLE)
                    }
                    plan.done -> {
                        setTextViewText(R.id.title, "Oggi: ${plan.title} ✓")
                        setTextViewText(R.id.subtitle, "Fatto, grande!")
                        setTextViewText(R.id.primary, "Diario")
                        setOnClickPendingIntent(R.id.primary, open(context, null))
                        setViewVisibility(R.id.run, if (plan.isRun) View.GONE else View.VISIBLE)
                    }
                    plan.isRun -> {
                        setTextViewText(R.id.title, "Oggi: corsa")
                        setTextViewText(R.id.subtitle, "Scarpe ai piedi!")
                        setTextViewText(R.id.primary, "▶  Corri")
                        setOnClickPendingIntent(R.id.primary, open(context, Intent(ACTION_START_RUN)))
                        setViewVisibility(R.id.run, View.GONE)
                    }
                    else -> {
                        val program = plan.program!!
                        setTextViewText(R.id.title, "Oggi: ${program.name}")
                        setTextViewText(R.id.subtitle, "${program.exercises.size} esercizi · ~${program.estimatedMinutes()} min")
                        setTextViewText(R.id.primary, "▶  Inizia")
                        setOnClickPendingIntent(
                            R.id.primary,
                            open(context, Intent(ACTION_START_PROGRAM).putExtra(EXTRA_PROGRAM_ID, program.id)),
                        )
                        setViewVisibility(R.id.run, View.VISIBLE)
                    }
                }
            }
        }

        private fun open(context: Context, action: Intent?): PendingIntent {
            val intent = (action ?: Intent()).setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val code = (action?.action + action?.getStringExtra(EXTRA_PROGRAM_ID)).hashCode()
            return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
