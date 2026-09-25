package com.andreagrossetti.training.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
enum class LogKind {
    @SerialName("program") PROGRAM,
    @SerialName("run") RUN,
    @SerialName("other") OTHER,
}

/** One day's activity: a program (done with the timer or logged by hand), a run, or anything else. */
@Serializable
data class LogEntry(
    val id: String = newId(),
    /** Start time, epoch millis. */
    val timestamp: Long,
    val kind: LogKind,
    val title: String,
    val durationSeconds: Int? = null,
    val distanceKm: Double? = null,
    val setsDone: Int? = null,
    val setsTotal: Int? = null,
    val notes: String = "",
    /** GPS-recorded runs: seconds taken by each full kilometre. */
    val splitsSeconds: List<Int>? = null,
    /** True when a GPS route is stored for this entry in [RouteStore]. */
    val hasRoute: Boolean = false,
    /** Timer workouts: every set as actually performed. */
    val sets: List<SetRecord>? = null,
    /** Perceived effort, 1 (light) to 5 (all out). */
    val effort: Int? = null,
    /** GPS runs: fastest time (s) over each standard distance (m) covered. */
    val bestEfforts: Map<Int, Int>? = null,
    /** Interval runs: each work interval as run. */
    val intervals: List<IntervalRecord>? = null,
    /** Where it was done, if the user saved it. */
    val place: Place? = null,
)

/** A GPS position with its address, when one could be found. */
@Serializable
data class Place(val lat: Double, val lon: Double, val name: String? = null) {
    val label: String get() = name ?: "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon)
}

@Serializable
data class IntervalRecord(val distanceM: Double, val seconds: Int)

val effortLabels = listOf("Leggero", "Moderato", "Impegnativo", "Duro", "Al limite")

class LogRepository(context: Context) {
    private val file = File(context.filesDir, "log.json")
    val routes = RouteStore(context)
    private val _entries = MutableStateFlow(load())
    /** Newest first. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun add(entry: LogEntry) = update(_entries.value + entry)

    fun get(id: String): LogEntry? = _entries.value.find { it.id == id }

    fun update(id: String, transform: (LogEntry) -> LogEntry) =
        update(_entries.value.map { if (it.id == id) transform(it) else it })

    fun replaceAll(entries: List<LogEntry>) = update(entries)

    /** The sets of [exercise] in the most recent workout that included it. */
    fun lastSets(exercise: String): List<SetRecord>? =
        _entries.value.firstNotNullOfOrNull { e -> e.sets?.filter { it.exercise == exercise }?.takeIf { it.isNotEmpty() } }

    /** Per-workout history of [exercise], newest first. */
    fun history(exercise: String): List<Pair<LogEntry, List<SetRecord>>> =
        _entries.value.mapNotNull { e -> e.sets?.filter { it.exercise == exercise }?.takeIf { it.isNotEmpty() }?.let { e to it } }

    fun delete(id: String) {
        update(_entries.value.filterNot { it.id == id })
        routes.delete(id)
    }

    private fun update(list: List<LogEntry>) {
        val sorted = list.sortedByDescending { it.timestamp }
        _entries.value = sorted
        file.writeAtomically(programJson.encodeToString(sorted))
    }

    private fun load(): List<LogEntry> {
        if (!file.exists()) return emptyList()
        return runCatching { programJson.decodeFromString<List<LogEntry>>(file.readText()) }
            .getOrDefault(emptyList())
    }
}
