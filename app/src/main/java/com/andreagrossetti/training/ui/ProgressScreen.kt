package com.andreagrossetti.training.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andreagrossetti.training.data.Exercise
import com.andreagrossetti.training.data.LogEntry
import com.andreagrossetti.training.data.LogRepository
import com.andreagrossetti.training.data.Program
import com.andreagrossetti.training.data.SetRecord
import com.andreagrossetti.training.data.date
import com.andreagrossetti.training.data.formatDuration
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val HISTORY_BARS = 10
private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)

/** Per-exercise history of a program, with a suggestion to raise targets that were all met. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(program: Program, log: LogRepository, onUpdate: (Program) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    // Recompose when new workouts are logged.
    val entries by log.entries.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Progressi", style = MaterialTheme.typography.titleLarge)
                        Text(program.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(program.exercises, key = { it.key }) { ex ->
                val history = remember(entries, ex.name) { log.history(ex.name) }
                ExerciseProgress(
                    exercise = ex,
                    history = history,
                    expectedSets = program.rounds ?: ex.sets,
                    onRaise = { target ->
                        onUpdate(program.copy(exercises = program.exercises.map { if (it.key == ex.key) it.withAmount(target) else it }))
                    },
                )
            }
        }
    }
}

@Composable
private fun ExerciseProgress(
    exercise: Exercise,
    history: List<Pair<LogEntry, List<SetRecord>>>,
    expectedSets: Int,
    onRaise: (Int) -> Unit,
) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(exercise.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                InfoPill("obiettivo ${exercise.amountText()}")
            }
            if (history.isEmpty()) {
                Text(
                    "Ancora nessuna sessione registrata col timer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@Column
            }

            // Total volume per session, oldest on the left.
            val sessions = history.take(HISTORY_BARS).reversed()
            val totals = sessions.map { (_, sets) -> sets.sumOf { (if (exercise.timed) it.seconds else it.reps) ?: 0 } }
            val max = totals.max().coerceAtLeast(1)
            Row(
                Modifier.fillMaxWidth().height(96.dp).padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                totals.forEachIndexed { i, total ->
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight((total.toFloat() / max).coerceAtLeast(0.06f))
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = if (i == totals.lastIndex) 1f else 0.45f),
                                    RoundedCornerShape(6.dp),
                                )
                        )
                    }
                }
            }
            Text(
                "Totale per sessione (${if (exercise.timed) "secondi" else "ripetizioni"})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                history.take(3).forEach { (entry, sets) ->
                    Row {
                        Text(shortDate.format(entry.date()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(setsText(exercise, sets), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }

            nextTarget(exercise, history.first().second, expectedSets)?.let { target ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
                        .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.TrendingUp, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "Tutto completato l'ultima volta",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    Button(onClick = { onRaise(target) }) {
                        Text("Porta a ${if (exercise.timed) formatDuration(target) else "$target"}")
                    }
                }
            }
        }
    }
}
