package com.andreagrossetti.training.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun LogEntry.date(): LocalDate = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

/** Consecutive active days ending today, or yesterday if nothing has been logged yet today. */
fun streak(days: Set<LocalDate>, today: LocalDate = LocalDate.now()): Int {
    var day = if (today in days) today else today.minusDays(1)
    var count = 0
    while (day in days) {
        count++
        day = day.minusDays(1)
    }
    return count
}

/** What the weekly plan has for a day. [program] is null for a run. */
data class Planned(val program: Program?, val done: Boolean) {
    val isRun: Boolean get() = program == null
    val title: String get() = program?.name ?: "Corsa"
}

fun planned(date: LocalDate, settings: Settings, programs: List<Program>, log: List<LogEntry>): Planned? {
    val value = settings.plan[date.dayOfWeek.value] ?: return null
    val program = if (value == PLAN_RUN) null else programs.find { it.id == value } ?: return null
    val done = log.any { e ->
        e.date() == date && if (program == null) e.kind == LogKind.RUN else e.kind == LogKind.PROGRAM && e.title == program.name
    }
    return Planned(program, done)
}
