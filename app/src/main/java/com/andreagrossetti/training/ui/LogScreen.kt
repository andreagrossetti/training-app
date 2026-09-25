package com.andreagrossetti.training.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andreagrossetti.training.data.LogEntry
import com.andreagrossetti.training.data.LogKind
import com.andreagrossetti.training.data.LogRepository
import com.andreagrossetti.training.data.SettingsRepository
import com.andreagrossetti.training.data.date
import com.andreagrossetti.training.data.effortLabels
import com.andreagrossetti.training.data.streak
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val italian = Locale.ITALIAN
private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", italian)
private val dayYearFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", italian)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", italian)

@Composable
fun LogScreen(
    log: LogRepository,
    settings: SettingsRepository,
    onAdd: () -> Unit,
    onOpenRun: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val entries by log.entries.collectAsStateWithLifecycle()
    val prefs by settings.settings.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<LogEntry?>(null) }
    var editing by remember { mutableStateOf<LogEntry?>(null) }
    val today = LocalDate.now()
    val byDay = entries.groupBy { it.date() }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Registra") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ScreenHeader("Diario", overline = "La tua attività") { SettingsButton(onSettings) }
            }
            item { Summary(byDay.keys, today, prefs.weeklyGoalDays) }
            if (entries.isNotEmpty()) item { YearHeatmap(byDay.mapValues { it.value.size }, today) }
            if (entries.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        IconBadge(Icons.AutoMirrored.Rounded.EventNote, MaterialTheme.colorScheme.onSurfaceVariant, size = 64.dp)
                        Text(
                            "Nessuna attività registrata",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(
                            "Gli allenamenti completati col timer finiscono qui automaticamente; " +
                                "corse e altro puoi aggiungerli con \"Registra\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            byDay.forEach { (day, dayEntries) ->
                item(key = day.toEpochDay()) {
                    Text(
                        dayLabel(day, today).uppercase(italian),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp, start = 4.dp),
                    )
                }
                items(dayEntries, key = { it.id }) { entry ->
                    LogEntryCard(
                        entry,
                        onOpen = { if (entry.hasRoute) onOpenRun(entry.id) else editing = entry },
                        onDelete = { toDelete = entry },
                    )
                }
            }
        }
    }

    editing?.let { entry ->
        NoteSheet(
            entry = entry,
            onSave = { effort, notes ->
                log.update(entry.id) { it.copy(effort = effort, notes = notes) }
                editing = null
            },
            onDismiss = { editing = null },
            onPlace = { place -> log.update(entry.id) { it.copy(place = place) } },
        )
    }

    toDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Eliminare \"${entry.title}\" dal diario?") },
            confirmButton = {
                TextButton(onClick = { log.delete(entry.id); toDelete = null }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun Summary(days: Set<LocalDate>, today: LocalDate, weeklyGoal: Int?) {
    val monday = today.with(DayOfWeek.MONDAY)
    val week = (0L..6L).map { monday.plusDays(it) }
    val weekCount = week.count { it in days }
    val month = days.count { it.year == today.year && it.month == today.month }
    val streak = streak(days, today)
    val accent = MaterialTheme.colorScheme.primary

    Panel(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.02f))))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.LocalFireDepartment, MaterialTheme.colorScheme.tertiary, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (streak == 1) "1 giorno" else "$streak giorni",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        if (streak > 0) "di fila, continua così!" else "Inizia una nuova serie oggi",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day -> WeekDay(day, active = day in days, isToday = day == today, Modifier.weight(1f)) }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

            if (weeklyGoal != null) {
                GoalBar(
                    label = "Obiettivo: $weeklyGoal giorni a settimana",
                    value = weekCount.toFloat(),
                    goal = weeklyGoal.toFloat(),
                    valueText = "$weekCount/$weeklyGoal",
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            Row(Modifier.fillMaxWidth()) {
                Stat(weekCount, "questa settimana", Modifier.weight(1f))
                Stat(month, "questo mese", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WeekDay(day: LocalDate, active: Boolean, isToday: Boolean, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            day.dayOfWeek.getDisplayName(TextStyle.NARROW, italian),
            style = MaterialTheme.typography.labelSmall,
            color = if (isToday) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(if (active) accent else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                .then(if (isToday && !active) Modifier.border(2.dp, accent, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            } else {
                Text(
                    "${day.dayOfMonth}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("$value", style = MaterialTheme.typography.headlineMedium)
        Text(
            "giorni attivi $label",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogEntryCard(entry: LogEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    val (icon, tint) = kindStyle(entry.kind)
    Panel(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                    if (entry.hasRoute) {
                        Icon(
                            Icons.Rounded.Route,
                            "Percorso",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp).size(16.dp),
                        )
                    }
                }
                Text(
                    details(entry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.effort?.let {
                    InfoPill("Fatica: ${effortLabels[it - 1]}", Icons.Rounded.Speed, Modifier.padding(top = 6.dp))
                }
                entry.place?.let {
                    InfoPill(it.label, Icons.Rounded.Place, Modifier.padding(top = 6.dp))
                }
                if (entry.notes.isNotBlank()) {
                    Text(entry.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, "Elimina", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun kindStyle(kind: LogKind): Pair<ImageVector, Color> = when (kind) {
    LogKind.PROGRAM -> Icons.Rounded.FitnessCenter to MaterialTheme.colorScheme.primary
    LogKind.RUN -> Icons.AutoMirrored.Rounded.DirectionsRun to MaterialTheme.colorScheme.secondary
    LogKind.OTHER -> Icons.Rounded.Star to MaterialTheme.colorScheme.tertiary
}

private fun details(entry: LogEntry): String {
    val parts = mutableListOf(Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.systemDefault()).format(timeFormat))
    entry.durationSeconds?.let { parts += formatMinutes(it) }
    entry.distanceKm?.let { km ->
        parts += "%.1f km".format(italian, km)
        entry.durationSeconds?.takeIf { km > 0 }?.let { secs ->
            val pace = (secs / km).toInt()
            parts += "%d:%02d /km".format(pace / 60, pace % 60)
        }
    }
    if (entry.setsDone != null && entry.setsTotal != null) parts += "${entry.setsDone}/${entry.setsTotal} serie"
    return parts.joinToString(" · ")
}

private fun formatMinutes(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return if (minutes < 60) "$minutes min" else "%d h %02d min".format(minutes / 60, minutes % 60)
}

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Oggi"
    today.minusDays(1) -> "Ieri"
    else -> (if (day.year == today.year) dayFormat else dayYearFormat).format(day)
}
