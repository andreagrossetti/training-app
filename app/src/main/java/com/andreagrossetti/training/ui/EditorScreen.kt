package com.andreagrossetti.training.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.andreagrossetti.training.data.Exercise
import com.andreagrossetti.training.data.Program
import com.andreagrossetti.training.data.programJson
import com.andreagrossetti.training.data.steps

private val ProgramSaver = Saver<Program, String>(
    save = { programJson.encodeToString(it) },
    restore = { programJson.decodeFromString(it) },
)

@Composable
fun EditorScreen(
    initial: Program?,
    onSave: (Program) -> Unit,
    onClose: () -> Unit,
    /** False when [initial] is an unsaved draft rather than a stored program. */
    existing: Boolean = initial != null,
    /** Shown above the form, e.g. to ask for a review of an AI draft. */
    notice: String? = null,
) {
    var program by rememberSaveable(stateSaver = ProgramSaver) {
        mutableStateOf(initial ?: Program(name = "", exercises = listOf(Exercise(name = "", reps = 10))))
    }

    fun setExercises(transform: MutableList<Exercise>.() -> Unit) {
        program = program.copy(exercises = program.exercises.toMutableList().apply(transform))
    }

    val valid = program.exercises.isNotEmpty() &&
        program.exercises.all { it.name.isNotBlank() && (program.circuit || it.sets > 0) && it.amount > 0 } &&
        (program.rounds ?: 1) > 0

    Scaffold(
        topBar = {
            FormTopBar(
                title = if (existing) "Modifica programma" else "Nuovo programma",
                onClose = onClose,
                onSave = { onSave(program.copy(name = program.name.ifBlank { "Programma" })) },
                saveEnabled = valid,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column {
                    BareTextField(
                        value = program.name,
                        onValueChange = { program = program.copy(name = it) },
                        placeholder = "Nome programma",
                        textStyle = MaterialTheme.typography.headlineMedium,
                    )
                    val sets = program.steps().size
                    Text(
                        "${program.exercises.size} esercizi · $sets serie · ~${program.estimatedMinutes()} min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
            notice?.let { item { NoticeCard(it) } }
            item { ModeCard(program, onChange = { program = it }) }
            itemsIndexed(program.exercises, key = { _, ex -> ex.key }) { index, exercise ->
                ExerciseCard(
                    circuit = program.circuit,
                    number = index + 1,
                    exercise = exercise,
                    onChange = { updated -> setExercises { set(index, updated) } },
                    onMoveUp = if (index > 0) ({ setExercises { add(index - 1, removeAt(index)) } }) else null,
                    onMoveDown = if (index < program.exercises.lastIndex) {
                        { setExercises { add(index + 1, removeAt(index)) } }
                    } else null,
                    onDelete = { setExercises { removeAt(index) } },
                    modifier = Modifier.animateItem(),
                )
            }
            item {
                OutlinedButton(
                    onClick = { setExercises { add(Exercise(name = "", reps = 10)) } },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Rounded.Add, null)
                    Text("Aggiungi esercizio", Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String) {
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ModeCard(program: Program, onChange: (Program) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !program.circuit,
                    onClick = { onChange(program.copy(rounds = null)) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Serie") }
                SegmentedButton(
                    selected = program.circuit,
                    onClick = { onChange(program.copy(rounds = program.rounds ?: 3)) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Circuito") }
            }
            Text(
                if (program.circuit) "Un giro fa tutti gli esercizi uno dopo l'altro, poi si ricomincia. " +
                    "Con recupero 0 tra due esercizi hai una superserie."
                else "Tutte le serie di un esercizio, poi il successivo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            program.rounds?.let { rounds ->
                Stepper("Giri", rounds, { onChange(program.copy(rounds = it)) }, min = 1)
                Stepper("Recupero tra i giri", program.roundRest, { onChange(program.copy(roundRest = it)) }, step = 15, suffix = "s")
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    circuit: Boolean,
    number: Int,
    exercise: Exercise,
    onChange: (Exercise) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$number", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary)
                }
                BareTextField(
                    value = exercise.name,
                    onValueChange = { onChange(exercise.copy(name = it)) },
                    placeholder = "Nome esercizio",
                    textStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                val subtle = MaterialTheme.colorScheme.onSurfaceVariant
                IconButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) {
                    Icon(Icons.Rounded.KeyboardArrowUp, "Su", tint = subtle.copy(alpha = if (onMoveUp != null) 1f else 0.3f))
                }
                IconButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Giù", tint = subtle.copy(alpha = if (onMoveDown != null) 1f else 0.3f))
                }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Elimina", tint = subtle) }
            }

            Column(Modifier.padding(end = 8.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !exercise.timed,
                        onClick = { onChange(exercise.withTimed(false)) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = { Icon(Icons.Rounded.Repeat, null, Modifier.size(18.dp)) },
                    ) { Text("Ripetizioni") }
                    SegmentedButton(
                        selected = exercise.timed,
                        onClick = { onChange(exercise.withTimed(true)) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = { Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp)) },
                    ) { Text("A tempo") }
                }

                if (!circuit) {
                    Stepper("Serie", exercise.sets, { onChange(exercise.copy(sets = it)) }, min = 1)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (exercise.timed) {
                    Stepper("Durata", exercise.amount, { onChange(exercise.withAmount(it)) }, step = 5, min = 1, suffix = "s")
                } else {
                    Stepper("Ripetizioni", exercise.amount, { onChange(exercise.withAmount(it)) }, min = 1)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Stepper(if (circuit) "Recupero dopo" else "Recupero", exercise.rest, { onChange(exercise.copy(rest = it)) }, step = 15, suffix = "s")

                AppTextField(
                    value = exercise.notes,
                    onValueChange = { onChange(exercise.copy(notes = it)) },
                    label = "Note (opzionale)",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

/** Borderless text field that reads as a heading. */
@Composable
private fun BareTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, style = textStyle) },
        textStyle = textStyle,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
