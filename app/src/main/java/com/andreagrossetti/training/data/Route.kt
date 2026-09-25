package com.andreagrossetti.training.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/**
 * A GPS fix of a recorded run: [t] moving time (ms) and [d] distance (m) from the start,
 * [a] the fix accuracy (m), kept to diagnose gaps in the track.
 */
@Serializable
data class RoutePoint(val lat: Double, val lon: Double, val t: Long, val d: Double = 0.0, val a: Float? = null)

/** Standard distances (m) for personal records. */
val recordDistances = listOf(1000, 5000, 10000, 21097)

fun recordLabel(meters: Int): String = when (meters) {
    21097 -> "Mezza"
    else -> "${meters / 1000} km"
}

/** Fastest time (s) over each of [recordDistances] the route covers, interpolated between fixes. */
fun bestEfforts(points: List<RoutePoint>): Map<Int, Int> = recordDistances.mapNotNull { target ->
    var best = Double.MAX_VALUE
    var i = 0
    for (j in points.indices) {
        while (i + 1 < j && points[j].d - points[i + 1].d >= target) i++
        val covered = points[j].d - points[i].d
        if (covered >= target) best = minOf(best, (points[j].t - points[i].t) / 1000.0 * target / covered)
    }
    if (best == Double.MAX_VALUE) null else target to best.toInt()
}.toMap()

private val routeJson = Json { ignoreUnknownKeys = true }

/**
 * Routes live in one file per run, outside log.json, so the log stays small
 * no matter how many runs are recorded.
 */
class RouteStore(context: Context) {
    private val dir = File(context.filesDir, "routes").apply { mkdirs() }

    fun save(id: String, points: List<RoutePoint>) = file(id).writeAtomically(routeJson.encodeToString(points))

    fun load(id: String): List<RoutePoint> = runCatching {
        routeJson.decodeFromString<List<RoutePoint>>(file(id).readText())
    }.getOrDefault(emptyList())

    fun delete(id: String) = file(id).delete()

    private fun file(id: String) = File(dir, "$id.json")
}

fun formatPace(secondsPerKm: Int?): String =
    secondsPerKm?.let { "%d:%02d".format(it / 60, it % 60) } ?: "–:––"

fun formatKm(meters: Double): String = "%.2f".format(Locale.ITALIAN, meters / 1000)
