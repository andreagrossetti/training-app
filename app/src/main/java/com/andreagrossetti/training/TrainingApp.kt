package com.andreagrossetti.training

import android.app.Application
import com.andreagrossetti.training.ai.LocalAi
import com.andreagrossetti.training.ai.ProgramGenerator
import com.andreagrossetti.training.data.HrvRepository
import com.andreagrossetti.training.data.LogEntry
import com.andreagrossetti.training.data.LogKind
import com.andreagrossetti.training.data.LogRepository
import com.andreagrossetti.training.data.ProgramRepository
import com.andreagrossetti.training.data.SettingsRepository
import com.andreagrossetti.training.data.bestEfforts
import com.andreagrossetti.training.drive.DriveBackup
import com.andreagrossetti.training.health.HealthSync
import com.andreagrossetti.training.hrv.HrvSession
import com.andreagrossetti.training.reminder.Reminders
import com.andreagrossetti.training.run.RunState
import com.andreagrossetti.training.run.RunTracker
import com.andreagrossetti.training.widget.TrainingWidget
import com.andreagrossetti.training.workout.Cues
import com.andreagrossetti.training.workout.Voice
import com.andreagrossetti.training.workout.WorkoutEngine
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class TrainingApp : Application() {
    lateinit var repository: ProgramRepository
        private set
    lateinit var log: LogRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var engine: WorkoutEngine
        private set
    lateinit var tracker: RunTracker
        private set
    lateinit var voice: Voice
        private set
    lateinit var hrv: HrvRepository
        private set
    lateinit var hrvSession: HrvSession
        private set
    lateinit var health: HealthSync
        private set
    lateinit var drive: DriveBackup
        private set
    val ai by lazy { LocalAi() }
    val programGenerator by lazy { ProgramGenerator(ai) }

    override fun onCreate() {
        super.onCreate()
        repository = ProgramRepository(this)
        log = LogRepository(this)
        settings = SettingsRepository(this)
        voice = Voice(this)
        hrv = HrvRepository(this)
        val cues = Cues(this)
        hrvSession = HrvSession(this, hrv, cues)
        health = HealthSync(this, log.routes)
        drive = DriveBackup(this)
        engine = WorkoutEngine(
            this,
            cues,
            voice,
            voiceEnabled = { settings.value.workoutVoice },
            volumeDoneEnabled = { settings.value.volumeDone },
            remoteEnabled = { settings.value.remote },
        ) { workout ->
            val entry = LogEntry(
                timestamp = workout.startedAt,
                kind = LogKind.PROGRAM,
                title = workout.program.name,
                durationSeconds = ((System.currentTimeMillis() - workout.startedAt) / 1000).toInt(),
                setsDone = workout.setsDone,
                setsTotal = workout.setsTotal,
                sets = workout.results,
            )
            log.add(entry)
            entry.id
        }
        tracker = RunTracker(this, cues, voice, kmCue = { settings.value.kmCue })

        // Keep the home screen widget and the reminder alarm in sync with the data.
        MainScope().launch {
            combine(log.entries, settings.settings, repository.programs) { _, _, _ -> }.collect {
                TrainingWidget.updateAll(this@TrainingApp)
                Reminders.schedule(this@TrainingApp)
            }
        }
        MainScope().launch {
            settings.settings.map { it.driveBackup }.distinctUntilChanged().collect { DriveBackup.schedule(this@TrainingApp, it) }
        }
        // Mirror the diary and HRV readings into Health Connect, a moment after they settle.
        MainScope().launch {
            combine(log.entries, hrv.measurements, settings.settings) { entries, readings, s ->
                if (s.healthConnect) entries to readings else null
            }.debounce(2_000).collect { data ->
                if (data != null && health.hasPermissions()) health.sync(data.first, data.second)
            }
        }
    }

    /** Logs a finished run with its route; returns the new log entry id. */
    fun saveRun(run: RunState): String {
        val plan = run.plan
        val entry = LogEntry(
            timestamp = run.startedAt,
            kind = LogKind.RUN,
            title = if (plan != null) plan.name.ifBlank { "Ripetute" } else "Corsa",
            durationSeconds = (run.movingMs() / 1000).toInt(),
            distanceKm = run.distanceM / 1000,
            splitsSeconds = run.splitsMs.map { (it / 1000).toInt() },
            hasRoute = run.points.size >= 2,
            bestEfforts = bestEfforts(run.points).takeIf { it.isNotEmpty() },
            intervals = run.intervalResults.takeIf { plan != null },
        )
        if (entry.hasRoute) log.routes.save(entry.id, run.points)
        log.add(entry)
        return entry.id
    }
}
