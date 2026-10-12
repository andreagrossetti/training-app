package com.andreagrossetti.training.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Body position during a reading: values differ a lot between them, so each has its own baseline. */
@Serializable
enum class HrvPosition { LYING, SITTING, STANDING }

/** A morning heart rate variability reading from the finger sensor. */
@Serializable
data class HrvMeasurement(
    val id: String = newId(),
    /** Start of the recorded part (after stabilisation), epoch millis. */
    val timestamp: Long,
    /** Root mean square of successive differences between beats, ms. */
    val rmssd: Double,
    val meanHr: Double,
    /** Beats kept after the artifact filter. */
    val beats: Int,
    /** Share of beats dropped as artifacts, 0..1. */
    val artifacts: Double,
    /** Successive beat pairs the RMSSD is computed from; null in readings from before it was stored. */
    val pairs: Int? = null,
    /** Share of the recording time covered by usable intervals, 0..1; beats lost over Bluetooth lower it. */
    val coverage: Double = 1.0,
    /** Raw beat-to-beat intervals (ms) as received, kept to recompute later. */
    val rr: List<Int> = emptyList(),
    val position: HrvPosition = HrvPosition.LYING,
    /** Night's sleep quality, 1 (bad) to 5 (great). */
    val sleep: Int? = null,
    /** Mood on waking, 1 (low) to 5 (great). */
    val mood: Int? = null,
    val notes: String = "",
) {
    val lnRmssd: Double get() = ln(rmssd)
    val reliable: Boolean get() = (pairs ?: beats) >= MIN_PAIRS && artifacts <= MAX_ARTIFACTS
}

fun HrvMeasurement.date(): LocalDate = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

const val MIN_PAIRS = 40
const val MAX_ARTIFACTS = 0.15

/** A break in the data (e.g. the sensor reconnected): the intervals either side aren't successive. */
const val RR_GAP = -1

/** Result of the artifact filter: kept intervals, with [RR_GAP] wherever something was dropped. */
data class CleanRr(val series: List<Int>, val total: Int) {
    val beats: Int get() = series.count { it != RR_GAP }
    val artifacts: Double get() = if (total == 0) 0.0 else 1.0 - beats.toDouble() / total
}

/**
 * Drops intervals that are physiologically impossible or more than 20% away from the local median
 * (finger movement makes the optical sensor miss or invent beats). [rr] may contain [RR_GAP].
 */
fun cleanRr(rr: List<Int>): CleanRr {
    val valid = rr.filter { it != RR_GAP }
    val out = ArrayList<Int>(rr.size)
    for (i in rr.indices) {
        if (isKept(rr, i)) out += rr[i] else if (out.lastOrNull() != RR_GAP) out += RR_GAP
    }
    return CleanRr(out, valid.size)
}

/** Whether interval [i] of [rr] passes the artifact filter. */
internal fun isKept(rr: List<Int>, i: Int): Boolean {
    val value = rr[i]
    if (value !in 300..2000) return false
    val window = rr.subList(max(0, i - 5), minOf(rr.size, i + 6)).filter { it in 300..2000 }
    val median = window.sorted()[window.size / 2]
    return abs(value - median) <= median * 0.2
}

/** Differences between successive kept intervals, never across a gap. */
private fun successiveDiffs(series: List<Int>): List<Int> =
    series.zipWithNext().filter { (a, b) -> a != RR_GAP && b != RR_GAP }.map { (a, b) -> b - a }

/** Number of successive beat pairs (not across a gap) in a cleaned series. */
fun successivePairs(series: List<Int>): Int = successiveDiffs(series).size

/** RMSSD over successive kept intervals only (never across a gap); null if fewer than 2 pairs. */
fun rmssd(series: List<Int>): Double? {
    val diffs = successiveDiffs(series)
    return if (diffs.size < 2) null else sqrt(diffs.sumOf { it.toDouble() * it } / diffs.size)
}

fun meanHr(series: List<Int>): Double {
    val kept = series.filter { it != RR_GAP }
    return if (kept.isEmpty()) 0.0 else 60_000.0 / kept.average()
}

/**
 * Builds a measurement from raw intervals recorded over [durationMs], or null if there's too little data
 * to compute one.
 */
fun hrvMeasurement(
    timestamp: Long,
    durationMs: Long,
    rr: List<Int>,
    position: HrvPosition = HrvPosition.LYING,
): HrvMeasurement? {
    val clean = cleanRr(rr)
    val value = rmssd(clean.series)?.takeIf { it > 0 } ?: return null
    return HrvMeasurement(
        timestamp = timestamp,
        rmssd = value,
        meanHr = meanHr(clean.series),
        beats = clean.beats,
        artifacts = clean.artifacts,
        pairs = successiveDiffs(clean.series).size,
        coverage = (rr.filter { it != RR_GAP }.sum().toDouble() / durationMs).coerceAtMost(1.0),
        rr = rr,
        position = position,
    )
}

enum class Readiness { GREEN, YELLOW, RED }

sealed interface ReadinessResult {
    /** Not enough mornings yet to know what's normal. */
    data class Calibrating(val days: Int, val needed: Int) : ReadinessResult

    /** [baselineRmssd] is the typical value; [low]..[high] the normal range (all in ms). */
    data class Ready(
        val readiness: Readiness,
        val today: HrvMeasurement,
        val baselineRmssd: Double,
        val low: Double,
        val high: Double,
        /** Resting heart rate clearly above normal. */
        val highHr: Boolean,
        /** Typical resting heart rate over the baseline, bpm. */
        val baselineHr: Double,
        /** Fewer than [MIN_BASELINE_DAYS] mornings so far: a rougher comparison with fixed margins. */
        val provisional: Boolean = false,
        /** Mornings the baseline is made of. */
        val baselineDays: Int = 0,
    ) : ReadinessResult
}

const val BASELINE_DAYS = 30
const val MIN_BASELINE_DAYS = 7
/** From this many mornings there's a provisional verdict, before the full one at [MIN_BASELINE_DAYS]. */
const val MIN_PROVISIONAL_DAYS = 3

/** Readings up to this long after the first of the day are repeats of the same morning check. */
private const val MORNING_WINDOW_MS = 60 * 60_000L

/**
 * One value per day, from the morning check: the first reliable reading, averaged (ln RMSSD, heart rate) with
 * any repeats in the same position within the next hour, since a single short reading is noisy. Optionally only
 * among readings taken in [position]. Newest day first; each value carries the first reading's id and time.
 */
fun dailyHrv(measurements: List<HrvMeasurement>, position: HrvPosition? = null): List<HrvMeasurement> =
    measurements.filter { it.reliable && (position == null || it.position == position) }
        .groupBy { it.date() }
        .map { (_, day) ->
            val first = day.minBy { it.timestamp }
            val morning = day.filter { it.position == first.position && it.timestamp - first.timestamp <= MORNING_WINDOW_MS }
            if (morning.size == 1) first
            else first.copy(rmssd = kotlin.math.exp(morning.map { it.lnRmssd }.average()), meanHr = morning.map { it.meanHr }.average())
        }
        .sortedByDescending { it.timestamp }

/**
 * Compares [date]'s reading with the previous [BASELINE_DAYS] days (ln RMSSD, as in the HRV literature):
 * within 1 SD below the mean or higher is green, down to 2 SD yellow, lower red.
 * Only readings in the same position count towards the baseline.
 * A resting heart rate 2 SD above normal turns green into yellow. Null if [date] has no reading.
 */
fun readiness(measurements: List<HrvMeasurement>, date: LocalDate = LocalDate.now()): ReadinessResult? {
    val today = dailyHrv(measurements).find { it.date() == date } ?: return null
    val from = date.minusDays(BASELINE_DAYS.toLong())
    val baseline = dailyHrv(measurements, today.position).filter { it.date() < date && it.date() >= from }
    if (baseline.size < MIN_PROVISIONAL_DAYS) return ReadinessResult.Calibrating(baseline.size, MIN_PROVISIONAL_DAYS)

    val (mean, sd) = meanSd(baseline.map { it.lnRmssd })
    val (hrMean, hrSd) = meanSd(baseline.map { it.meanHr })
    if (baseline.size < MIN_BASELINE_DAYS) {
        // Too few mornings for a meaningful spread: fixed margins on ln RMSSD (0.15 ≈ −14%, 0.5 ≈ −40%).
        val drop = today.lnRmssd - mean
        val highHr = today.meanHr > hrMean + 5
        val readiness = when {
            drop < -0.5 -> Readiness.RED
            drop < -0.15 || highHr -> Readiness.YELLOW
            else -> Readiness.GREEN
        }
        return ReadinessResult.Ready(
            readiness = readiness,
            today = today,
            baselineRmssd = kotlin.math.exp(mean),
            low = kotlin.math.exp(mean - 0.15),
            high = kotlin.math.exp(mean + 0.15),
            highHr = highHr,
            baselineHr = hrMean,
            provisional = true,
            baselineDays = baseline.size,
        )
    }
    val spread = max(sd, 0.05) // a few very similar mornings shouldn't make every small dip look alarming
    val z = (today.lnRmssd - mean) / spread
    val highHr = today.meanHr > hrMean + 2 * max(hrSd, 1.5)
    val readiness = when {
        z < -2 -> Readiness.RED
        z < -1 || highHr -> Readiness.YELLOW
        else -> Readiness.GREEN
    }
    return ReadinessResult.Ready(
        readiness = readiness,
        today = today,
        baselineRmssd = kotlin.math.exp(mean),
        low = kotlin.math.exp(mean - spread),
        high = kotlin.math.exp(mean + spread),
        highHr = highHr,
        baselineHr = hrMean,
        baselineDays = baseline.size,
    )
}

private fun meanSd(values: List<Double>): Pair<Double, Double> {
    val mean = values.average()
    val variance = if (values.size < 2) 0.0 else values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
    return mean to sqrt(variance)
}

class HrvRepository(context: Context) {
    private val file = File(context.filesDir, "hrv.json")
    private val _measurements = MutableStateFlow(load())
    /** Newest first. */
    val measurements: StateFlow<List<HrvMeasurement>> = _measurements.asStateFlow()

    fun add(measurement: HrvMeasurement) = update(_measurements.value + measurement)

    /** Replaces the reading with the same id (e.g. after editing its tags). */
    fun save(measurement: HrvMeasurement) = update(_measurements.value.map { if (it.id == measurement.id) measurement else it })

    fun delete(id: String) = update(_measurements.value.filterNot { it.id == id })

    fun replaceAll(measurements: List<HrvMeasurement>) = update(measurements)

    private fun update(list: List<HrvMeasurement>) {
        val sorted = list.sortedByDescending { it.timestamp }
        _measurements.value = sorted
        file.writeAtomically(programJson.encodeToString(sorted))
    }

    private fun load(): List<HrvMeasurement> {
        if (!file.exists()) return emptyList()
        return runCatching { programJson.decodeFromString<List<HrvMeasurement>>(file.readText()) }
            .getOrDefault(emptyList())
    }
}
