package com.andreagrossetti.training.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** What to do today given the morning reading: the verdict, a concrete suggestion and why. */
data class DailyAdvice(
    val readiness: Readiness,
    val suggestion: String,
    val reasons: List<String>,
)

private val sleepWords = listOf("pessimo", "scarso", "discreto", "buono", "ottimo")
private val effortWords = listOf("leggera", "moderata", "impegnativa", "dura", "al limite")

/**
 * Builds today's advice from the readiness verdict, how the night went (the morning reading's tags) and the
 * last workout. A bad night turns a green light into yellow, as the HRV alone can miss it.
 */
fun dailyAdvice(ready: ReadinessResult.Ready, log: List<LogEntry>, today: LocalDate = LocalDate.now()): DailyAdvice {
    val morning = ready.today
    val badSleep = (morning.sleep ?: 3) <= 2
    val readiness = if (ready.readiness == Readiness.GREEN && badSleep) Readiness.YELLOW else ready.readiness
    val reasons = mutableListOf<String>()

    val rmssd = ready.today.rmssd.roundToInt()
    val typical = ready.baselineRmssd.roundToInt()
    val change = ((ready.today.rmssd / ready.baselineRmssd - 1) * 100).roundToInt()
    reasons += when {
        ready.today.rmssd < ready.low -> "HRV $rmssd ms: ${abs(change)}% sotto il tuo solito ($typical ms)"
        ready.today.rmssd > ready.high -> "HRV $rmssd ms: ${abs(change)}% sopra il tuo solito ($typical ms)"
        else -> "HRV $rmssd ms: nella tua norma ($typical ms)"
    }
    val hr = ready.today.meanHr.roundToInt()
    val hrDelta = (ready.today.meanHr - ready.baselineHr).roundToInt()
    reasons += when {
        hrDelta >= 3 -> "Battito a riposo $hr bpm, $hrDelta più del solito (${ready.baselineHr.roundToInt()})" +
            if (ready.highHr) ": il corpo sta ancora lavorando" else ""
        hrDelta <= -3 -> "Battito a riposo $hr bpm, più basso del solito"
        else -> "Battito a riposo $hr bpm, nella norma"
    }
    morning.sleep?.let { reasons += "Sonno ${sleepWords[it - 1]}" + if (badSleep) ": conta quanto l'HRV" else "" }
    lastWorkout(log, today)?.let { reasons += it }
    if (ready.provisional) {
        val left = MIN_BASELINE_DAYS - ready.baselineDays
        reasons += "Valutazione provvisoria: con ancora $left ${if (left == 1) "mattina" else "mattine"} conoscerò meglio i tuoi valori"
    }

    val suggestion = when (readiness) {
        Readiness.GREEN -> "Allenati come da programma: oggi va bene anche una seduta intensa (ripetute, serie pesanti)."
        Readiness.YELLOW -> "Muoviti, ma leggero: corsa facile 20–40 minuti a ritmo di conversazione, camminata o mobilità. " +
            "Se fai forza, una serie in meno per esercizio e recuperi più lunghi. Niente ripetute né serie a cedimento."
        Readiness.RED -> "Giornata di recupero: camminata di 20–30 minuti, mobilità, stretching. Rimanda l'intensità a quando l'HRV risale."
    }
    return DailyAdvice(readiness, suggestion, reasons)
}

/** The most recent workout of the last two days, e.g. "Ieri: Giornata A (41 min, fatica moderata)". */
private fun lastWorkout(log: List<LogEntry>, today: LocalDate): String? {
    val entry = log.maxByOrNull { it.timestamp } ?: return null
    val date = Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    val day = when (date) {
        today -> "Oggi"
        today.minusDays(1) -> "Ieri"
        today.minusDays(2) -> "L'altro ieri"
        else -> return null
    }
    val details = listOfNotNull(
        entry.distanceKm?.let { "%.1f km".format(it) },
        entry.durationSeconds?.let { "${(it + 30) / 60} min" },
        entry.effort?.let { "fatica ${effortWords[it - 1]}" },
    )
    return "$day: ${entry.title}" + if (details.isEmpty()) "" else " (${details.joinToString(", ")})"
}
