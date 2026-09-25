package com.andreagrossetti.training.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andreagrossetti.training.data.Exercise
import com.andreagrossetti.training.data.LogRepository
import com.andreagrossetti.training.data.SetRecord
import com.andreagrossetti.training.data.Step
import com.andreagrossetti.training.data.formatDuration
import com.andreagrossetti.training.workout.Phase
import com.andreagrossetti.training.workout.RemoteStatus
import com.andreagrossetti.training.workout.WorkoutEngine
import com.andreagrossetti.training.workout.WorkoutState
import com.andreagrossetti.training.workout.phaseLabel

/** Top and bottom colors of each phase's background gradient. */
private fun phaseGradient(phase: Phase): Pair<Color, Color> = when (phase) {
    Phase.READY -> Color(0xFF2E1308) to Color(0xFF0A0C0F)
    Phase.PREPARE -> Color(0xFFFFC53D) to Color(0xFFF08A00)
    Phase.WORK -> Color(0xFF1FC46A) to Color(0xFF05704D)
    Phase.REST -> Color(0xFF3D86F5) to Color(0xFF3A2FB8)
    Phase.FINISHED -> Color(0xFFA254F2) to Color(0xFFD63384)
}

/** Timer digits: display font with tabular figures, so the countdown doesn't jiggle. */
private val TimerStyle = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

@Composable
fun WorkoutScreen(
    state: WorkoutState,
    engine: WorkoutEngine,
    log: LogRepository,
    /** The workout was stopped early and saved: lets the caller offer notes for the entry. */
    onSavedEarly: (String) -> Unit,
) {
    KeepScreenOn()
    var confirmStop by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    // Bumped on every swipe-skip: shows the "undo" pill (again) for a few seconds.
    var undoKey by remember { mutableIntStateOf(0) }
    // Exercise notes start collapsed; once opened they stay open for the rest of the workout.
    var showNotes by rememberSaveable { mutableStateOf(false) }
    val finished = state.phase == Phase.FINISHED
    val close: () -> Unit = { if (finished) engine.stop() else confirmStop = true }
    BackHandler(onBack = close)

    val (top, bottom) = phaseGradient(state.phase)
    val topColor by animateColorAsState(top, tween(600), label = "top")
    val bottomColor by animateColorAsState(bottom, tween(600), label = "bottom")

    // The workout screen is always white on a colored gradient, whatever the system theme.
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val remote by engine.remoteStatus.collectAsStateWithLifecycle()
            TopBar(state, remote, onList = { showList = true }, onClose = close)
            Spacer(Modifier.height(12.dp))
            SegmentedProgress(
                fractions = blockProgress(state),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.22f),
            )

            // Everything about the current exercise: swipe it left to skip the exercise.
            Column(
                Modifier.weight(1f).fillMaxWidth().then(
                    if (finished) Modifier
                    else Modifier.swipeToSkip {
                        if (state.isLastBlock) confirmEnd = true
                        else {
                            engine.skipExercise()
                            undoKey++
                        }
                    }
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!finished) {
                    Spacer(Modifier.height(20.dp))
                    PhaseChip(phaseLabel(state), paused = state.paused)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state.exercise.name,
                        style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    SetDots(state)
                    val notes = state.exercise.notes
                    if (notes.isNotBlank()) Notes(notes, showNotes, onToggle = { showNotes = !showNotes })
                }

                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    CenterDisplay(state, engine, log)
                }
            }

            if (undoKey > 0 && !finished) UndoSkip(undoKey, onUndo = { engine.undoSkip(); undoKey = 0 }, onTimeout = { undoKey = 0 })

            val lastSets = remember(state.exercise.name) { log.lastSets(state.exercise.name) }
            nextUp(state, lastSets)?.let { (label, text) ->
                NextUpCard(label, text)
                Spacer(Modifier.height(14.dp))
            }

            Controls(state, engine)
        }
    }

    if (showList) ExerciseListSheet(state, onDismiss = { showList = false })

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("Saltare l'ultimo esercizio?") },
            text = { Text("L'allenamento finisce qui e viene salvato nel diario.") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; engine.skipExercise() }) { Text("Salta e termina") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Continua") } },
        )
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Interrompere l'allenamento?") },
            text = {
                if (state.setsDone > 0) Text("Hai completato ${state.setsDone} serie su ${state.setsTotal}.")
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { confirmStop = false; engine.stop() }) { Text("Non salvare") }
                    if (state.setsDone > 0) {
                        TextButton(onClick = { confirmStop = false; engine.stop(save = true)?.let(onSavedEarly) }) {
                            Text("Salva nel diario")
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Continua") } },
        )
    }
}

@Composable
private fun TopBar(state: WorkoutState, remote: RemoteStatus, onList: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                state.program.name.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
            Text(
                if (state.phase == Phase.FINISHED) "Completato"
                else "Esercizio ${state.exerciseIndex + 1} di ${state.program.exercises.size}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (remote.connected) {
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BluetoothConnected, "Telecomando collegato", Modifier.size(20.dp))
                remote.battery?.let { Text("$it%", style = MaterialTheme.typography.labelMedium) }
            }
        }
        val colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.16f))
        IconButton(onClick = onList, colors = colors) {
            Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, "Elenco esercizi")
        }
        Spacer(Modifier.size(8.dp))
        IconButton(onClick = onClose, colors = colors) { Icon(Icons.Rounded.Close, "Chiudi") }
    }
}

/** Every exercise of the program with how many of its sets are done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseListSheet(state: WorkoutState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(state.program.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${state.setsDone} serie fatte su ${state.setsTotal}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            state.program.exercises.forEachIndexed { i, ex ->
                val mine = state.steps.indices.filter { state.steps[it].exerciseIndex == i }
                val done = mine.count(state::isDone)
                val current = i == state.exerciseIndex && state.phase != Phase.FINISHED
                val status = when {
                    done == mine.size -> "Fatto"
                    current -> "In corso · $done/${mine.size}"
                    // Circuits pass through every exercise each round: only "skipped" outside circuits.
                    !state.program.circuit && i < state.exerciseIndex || state.phase == Phase.FINISHED ->
                        if (done > 0) "Interrotto · $done/${mine.size}" else "Saltato"
                    done > 0 -> "$done/${mine.size}"
                    else -> ""
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (current) Brand.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.shapes.medium,
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        if (done == mine.size) {
                            Icon(Icons.Rounded.Check, null, tint = Brand)
                        } else {
                            Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = if (current) Brand else Color.Unspecified)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(ex.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (state.program.circuit) ex.amountText() else ex.summary(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (status.isNotEmpty()) {
                        Text(
                            status,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (current) Brand else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Progress bar segments: one per exercise, or one per round in a circuit, filling set by set. */
private fun blockProgress(state: WorkoutState): List<Float> {
    val blocks = state.steps.indices.groupBy { i ->
        val step = state.steps[i]
        if (state.program.circuit) step.round else step.exerciseIndex
    }
    return blocks.values.map { indices -> indices.count(state::isDone).toFloat() / indices.size }
}

@Composable
private fun PhaseChip(label: String, paused: Boolean) {
    Text(
        if (paused) "IN PAUSA" else label,
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp),
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** One dot per set (or round) of the current exercise. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetDots(state: WorkoutState) {
    val mine = state.steps.indices.filter { state.steps[it].exerciseIndex == state.exerciseIndex }
    // A single set needs neither dots nor "Serie 1 di 1".
    if (mine.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        mine.forEach { i ->
            val done = state.isDone(i)
            val current = i == state.stepIndex && state.phase != Phase.READY && !state.restBefore && !done
            val width by animateFloatAsState(if (current) 28f else 10f, label = "dot")
            Box(
                Modifier
                    .padding(vertical = 2.dp)
                    .size(width.dp, 10.dp)
                    .background(Color.White.copy(alpha = if (done || current) 1f else 0.3f), CircleShape)
            )
        }
    }
    if (!state.restBefore && mine.size < 2) return
    Text(
        if (state.restBefore) "Prossimo esercizio · ${state.exercise.summary()}" else state.roundLabel().replace("/", " di "),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.8f),
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** The exercise's notes, collapsed behind a toggle. */
@Composable
private fun Notes(notes: String, expanded: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle) {
        Text(if (expanded) "Nascondi note" else "Mostra note", color = Color.White.copy(alpha = 0.8f))
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Color.White.copy(alpha = 0.8f))
    }
    if (expanded) {
        Text(
            notes,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.08f), MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** New target when every set of the last session reached the current one. */
fun nextTarget(ex: Exercise, last: List<SetRecord>, expectedSets: Int): Int? {
    if (last.size < expectedSets) return null
    val met = last.all { (if (ex.timed) it.seconds else it.reps ?: 0)?.let { v -> v >= ex.amount } == true }
    return if (met) ex.amount + if (ex.timed) 5 else 1 else null
}

fun setsText(ex: Exercise, sets: List<SetRecord>): String =
    sets.joinToString(" · ") { if (ex.timed) formatDuration(it.seconds ?: 0) else "${it.reps ?: 0}" }

@Composable
private fun CenterDisplay(state: WorkoutState, engine: WorkoutEngine, log: LogRepository) {
    val ex = state.exercise
    when {
        state.phase == Phase.FINISHED -> Finished(state, log)
        state.phase == Phase.READY && state.program.circuit -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${state.program.rounds} giri", style = TimerStyle, fontSize = 64.sp, color = Brand)
            Text("circuito", style = MaterialTheme.typography.titleMedium)
            Column(
                Modifier.padding(top = 20.dp).background(Color.White.copy(alpha = 0.08f), MaterialTheme.shapes.large).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.program.exercises.forEach { e ->
                    Row(Modifier.widthIn(min = 220.dp)) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(e.amountText(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
        state.phase == Phase.READY -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(ex.summary(), style = TimerStyle, fontSize = 72.sp, color = Brand)
            Text(if (ex.timed) "serie a tempo" else "serie × ripetizioni", style = MaterialTheme.typography.titleMedium)
            if (ex.sets > 1) {
                Text(
                    "recupero ${formatDuration(ex.rest)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            val last = remember(ex.name) { log.lastSets(ex.name) }
            if (last != null) {
                val target = nextTarget(ex, last, ex.sets)
                Column(
                    Modifier.padding(top = 20.dp).background(Color.White.copy(alpha = 0.08f), MaterialTheme.shapes.medium)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("L'ultima volta: ${setsText(ex, last)}", style = MaterialTheme.typography.bodyLarge)
                    if (target != null) {
                        Text(
                            "Tutto completato: prova con ${if (ex.timed) formatDuration(target) else "$target"}!",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFFFFB020),
                        )
                    }
                }
            }
        }
        state.isTimed -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // The ring takes what's left between header and controls, never more.
            val d = minOf(maxWidth * 0.85f, maxHeight, 320.dp)
            Box(Modifier.size(d), contentAlignment = Alignment.Center) {
                ProgressRing(
                    progress = state.progress,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    stroke = 14.dp,
                    modifier = Modifier.fillMaxSize(),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        timerText(state.remainingSeconds),
                        style = TimerStyle,
                        fontSize = (d.value * if (state.remainingSeconds >= 60) 0.27f else 0.36f).sp,
                    )
                    Text(
                        if (state.remainingSeconds >= 60) "minuti" else "secondi",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
        else -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          val d = minOf(maxWidth * 0.72f, maxHeight - 64.dp, 280.dp).coerceAtLeast(120.dp)
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(d)
                    .background(Color.White.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${state.reps}", style = TimerStyle, fontSize = (d.value * 0.46f).sp)
                    Text(
                        if (state.reps == ex.amount) "ripetizioni" else "obiettivo ${ex.amount}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
            // Adjust before "FATTO" if the set didn't go as planned.
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                )
                FilledTonalIconButton(onClick = { engine.adjustReps(-1) }, colors = colors, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Remove, "Una in meno")
                }
                FilledTonalIconButton(onClick = { engine.adjustReps(1) }, colors = colors, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Add, "Una in più")
                }
            }
          }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Finished(state: WorkoutState, log: LogRepository) {
    val minutes = remember { ((System.currentTimeMillis() - state.startedAt) / 60_000).toInt() }
    val entries by log.entries.collectAsStateWithLifecycle()
    val entry = entries.find { it.id == state.logEntryId }
    var editingNote by remember { mutableStateOf(false) }
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).background(Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.EmojiEvents, null, Modifier.size(48.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Allenamento\ncompletato!", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FinishedStat("${state.setsDone}/${state.setsTotal}", "serie", Modifier.weight(1f))
            FinishedStat("${state.program.exercises.size}", "esercizi", Modifier.weight(1f))
            FinishedStat("$minutes", "minuti", Modifier.weight(1f))
        }
        if (entry != null) {
            EffortPicker(
                effort = entry.effort,
                onChange = { e -> log.update(entry.id) { it.copy(effort = e) } },
                color = Color.White,
                idle = Color.White.copy(alpha = 0.16f),
                onColor = Color(0xFF6A1B6A),
                modifier = Modifier.padding(top = 24.dp),
            )
            TextButton(onClick = { editingNote = true }) {
                Icon(Icons.Rounded.EditNote, null, tint = Color.White)
                Text(
                    if (entry.notes.isBlank()) "Aggiungi una nota" else "“${entry.notes}”",
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            PlaceButton(
                entry.place,
                onChange = { place -> log.update(entry.id) { it.copy(place = place) } },
                color = Color.White,
            )
            if (editingNote) {
                NoteSheet(
                    entry = entry,
                    onSave = { effort, notes ->
                        log.update(entry.id) { it.copy(effort = effort, notes = notes) }
                        editingNote = false
                    },
                    onDismiss = { editingNote = false },
                )
            }
        }
    }
}

@Composable
private fun FinishedStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.14f), MaterialTheme.shapes.large)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = TimerStyle, fontSize = 28.sp)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
    }
}

@Composable
private fun NextUpCard(label: String, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.18f), MaterialTheme.shapes.large)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Controls(state: WorkoutState, engine: WorkoutEngine) {
    val big = Modifier.fillMaxWidth().height(68.dp)
    val white = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0A0C0F))
    val translucent = ButtonDefaults.filledTonalButtonColors(
        containerColor = Color.White.copy(alpha = 0.18f),
        contentColor = Color.White,
    )
    val secondary = Modifier.height(56.dp)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            state.phase == Phase.READY -> {
                BigButton("INIZIA", Icons.Rounded.PlayArrow, engine::begin, big, ButtonDefaults.buttonColors(
                    containerColor = Brand,
                    contentColor = OnBrand,
                ))
                SwipeHint(state)
            }
            state.phase == Phase.FINISHED -> BigButton("CHIUDI", Icons.Rounded.Check, { engine.stop() }, big, white)
            state.phase == Phase.WORK && !state.exercise.timed -> {
                BigButton("FATTO", Icons.Rounded.Check, engine::skip, big, white)
                SwipeHint(state)
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Pause is rarely needed: a small button next to the main action, which turns
                    // into a white "resume" while paused so it stands out.
                    FilledTonalIconButton(
                        onClick = engine::togglePause,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (state.paused) Color.White else Color.White.copy(alpha = 0.18f),
                            contentColor = if (state.paused) Color(0xFF0A0C0F) else Color.White,
                        ),
                        modifier = Modifier.size(56.dp),
                    ) {
                        if (state.paused) Icon(Icons.Rounded.PlayArrow, "Riprendi") else Icon(Icons.Rounded.Pause, "Pausa")
                    }
                    if (state.phase == Phase.REST) {
                        FilledTonalButton(
                            onClick = { engine.addTime(15_000) },
                            colors = translucent,
                            modifier = secondary.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                            Text("15 s", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    FilledTonalButton(onClick = engine::skip, colors = translucent, modifier = secondary.weight(2f)) {
                        Text(
                            when (state.phase) {
                                Phase.REST -> "Salta recupero"
                                Phase.PREPARE -> "Parti subito"
                                else -> "Fine serie"
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
                SwipeHint(state)
            }
        }
    }
}

/** Swiping is invisible: a quiet reminder of it under the controls. */
@Composable
private fun SwipeHint(state: WorkoutState) {
    Text(
        if (state.isLastBlock) "‹ scorri a sinistra per saltare e terminare" else "‹ scorri a sinistra per saltare l'esercizio",
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.55f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}

/** Swipe left past a third of the width to call [onSkip]; the content slides out and back in. */
@Composable
private fun Modifier.swipeToSkip(onSkip: () -> Unit): Modifier {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnSkip by rememberUpdatedState(onSkip)
    return this
        .graphicsLayer {
            translationX = offset.value
            alpha = 1f - (abs(offset.value) / size.width).coerceIn(0f, 0.7f)
        }
        .pointerInput(Unit) {
            val width = size.width.toFloat()
            detectHorizontalDragGestures(
                onDragEnd = {
                    scope.launch {
                        if (offset.value < -width / 3) {
                            offset.animateTo(-width, tween(150))
                            currentOnSkip()
                            offset.snapTo(width * 0.4f)
                            offset.animateTo(0f, tween(250))
                        } else {
                            offset.animateTo(0f)
                        }
                    }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f) } },
            ) { change, amount ->
                change.consume()
                scope.launch { offset.snapTo((offset.value + amount).coerceAtMost(0f)) }
            }
        }
}

/** "Exercise skipped · Undo", for a few seconds after a swipe. */
@Composable
private fun UndoSkip(key: Int, onUndo: () -> Unit, onTimeout: () -> Unit) {
    LaunchedEffect(key) {
        delay(5_000)
        onTimeout()
    }
    Row(
        Modifier
            .padding(bottom = 10.dp)
            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            .padding(start = 18.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Esercizio saltato", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onUndo) { Text("ANNULLA", color = Color.White, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun BigButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    colors: ButtonColors,
) {
    Button(onClick = onClick, modifier = modifier, colors = colors, shape = CircleShape) {
        Icon(icon, null, Modifier.size(28.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 1.sp),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Label and text of the card above the controls: only what's worth a look, i.e. an exercise
 * change with no rest in between, the last set, or last time's results before an exercise.
 */
private fun nextUp(state: WorkoutState, lastSets: List<SetRecord>?): Pair<String, String>? {
    val next = state.nextStep
    return when (state.phase) {
        Phase.WORK -> when {
            next == null -> "ULTIMA SERIE" to "Dai tutto!"
            state.step.restAfter == 0 && next.exerciseIndex != state.exerciseIndex -> {
                val ex = state.program.exercises[next.exerciseIndex]
                "PROSSIMO ESERCIZIO" to "${ex.name} · ${ex.amountText()}"
            }
            else -> null
        }
        Phase.REST -> if (state.restBefore) lastSets?.let { "L'ULTIMA VOLTA" to setsText(state.exercise, it) } else null
        else -> null
    }
}

private fun timerText(seconds: Int): String =
    if (seconds >= 60) "%d:%02d".format(seconds / 60, seconds % 60) else seconds.toString()

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
