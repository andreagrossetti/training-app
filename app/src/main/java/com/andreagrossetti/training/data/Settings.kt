package com.andreagrossetti.training.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
enum class KmCue { OFF, BEEP, VOICE }

/** A guided interval run: [repeats] × (work by time or distance + recovery by time). */
@Serializable
data class IntervalPlan(
    val repeats: Int,
    val workSeconds: Int? = null,
    val workMeters: Int? = null,
    val recoverySeconds: Int,
    val name: String = "",
) {
    fun workText(): String = workMeters?.let { if (it >= 1000 && it % 1000 == 0) "${it / 1000} km" else "$it m" }
        ?: formatDuration(workSeconds ?: 0)

    fun label(): String = "$repeats × ${workText()} · rec. ${formatDuration(recoverySeconds)}"
}

val intervalPresets = listOf(
    IntervalPlan(8, workSeconds = 60, recoverySeconds = 120, name = "Corsa e camminata"),
    IntervalPlan(6, workSeconds = 180, recoverySeconds = 90, name = "Corsa e camminata +"),
    IntervalPlan(6, workMeters = 400, recoverySeconds = 90, name = "Ripetute 400 m"),
    IntervalPlan(4, workMeters = 1000, recoverySeconds = 120, name = "Ripetute 1 km"),
)

/** Planned activity for a weekday: a program id, or [PLAN_RUN]. */
const val PLAN_RUN = "run"

@Serializable
data class Settings(
    val kmCue: KmCue = KmCue.BEEP,
    val workoutVoice: Boolean = false,
    /** Volume up/down (e.g. from the headphones) completes a rep-based set. */
    val volumeDone: Boolean = true,
    /** Connect to the DIY Bluetooth remote during workouts. */
    val remote: Boolean = false,
    /** Active days per week to aim for; null = no goal. */
    val weeklyGoalDays: Int? = null,
    /** Running km per month to aim for; null = no goal. */
    val monthlyGoalKm: Int? = null,
    /** ISO weekday (1 = Monday) → program id or [PLAN_RUN]. */
    val plan: Map<Int, String> = emptyMap(),
    /** Daily reminder time in minutes after midnight; null = off. */
    val reminderMinutes: Int? = null,
    val lastInterval: IntervalPlan? = null,
    /** Mirror workouts, runs and HRV readings into Health Connect (and so Samsung Health). */
    val healthConnect: Boolean = false,
    /** Upload the full backup to Google Drive every week. */
    val driveBackup: Boolean = false,
)

class SettingsRepository(context: Context) {
    private val file = File(context.filesDir, "settings.json")
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(transform: (Settings) -> Settings) = replace(transform(_settings.value))

    fun replace(settings: Settings) {
        _settings.value = settings
        file.writeAtomically(programJson.encodeToString(settings))
    }

    private fun load(): Settings = runCatching {
        programJson.decodeFromString<Settings>(file.readText())
    }.getOrDefault(Settings())
}
