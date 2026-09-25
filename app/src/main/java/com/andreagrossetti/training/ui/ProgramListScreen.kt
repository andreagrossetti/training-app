package com.andreagrossetti.training.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andreagrossetti.training.data.Program
import com.andreagrossetti.training.data.HrvRepository
import com.andreagrossetti.training.data.LogRepository
import com.andreagrossetti.training.data.Planned
import com.andreagrossetti.training.data.ProgramRepository
import com.andreagrossetti.training.data.SettingsRepository
import com.andreagrossetti.training.data.planned
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val JSON_EXAMPLE = """{
  "name": "Full body",
  "exercises": [
    { "name": "Tenute alla sbarra", "sets": 3, "seconds": 30, "rest": 60 },
    { "name": "Piegamenti", "sets": 3, "reps": 10, "rest": 90 }
  ]
}"""

private const val PREVIEW_EXERCISES = 4

private val overlineFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)

@Composable
fun ProgramListScreen(
    repository: ProgramRepository,
    log: LogRepository,
    settings: SettingsRepository,
    hrv: HrvRepository,
    onStart: (Program) -> Unit,
    onEdit: (Program?) -> Unit,
    /** Null when the on-device AI isn't available. */
    onAiCreate: (() -> Unit)?,
    onProgress: (Program) -> Unit,
    onRun: () -> Unit,
    onSettings: () -> Unit,
    onHrv: () -> Unit,
) {
    val programs by repository.programs.collectAsStateWithLifecycle()
    val entries by log.entries.collectAsStateWithLifecycle()
    val prefs by settings.settings.collectAsStateWithLifecycle()
    val today = planned(LocalDate.now(), prefs, programs, entries)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var showFormat by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Program?>(null) }

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            repository.import(text)
        }.onSuccess { message("Importati $it programmi") }
            .onFailure { message("Import fallito: ${it.message}") }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(repository.exportJson()) }
        }.onSuccess { message("Esportati ${programs.size} programmi") }
            .onFailure { message("Export fallito: ${it.message}") }
    }
    val import = { importLauncher.launch(arrayOf("*/*")) }

    Scaffold(
        floatingActionButton = {
            if (programs.isNotEmpty()) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onAiCreate != null) {
                        SmallFloatingActionButton(
                            onClick = onAiCreate,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = CircleShape,
                        ) { Icon(Icons.Rounded.AutoAwesome, "Crea con l'AI") }
                    }
                    ExtendedFloatingActionButton(
                        onClick = { onEdit(null) },
                        icon = { Icon(Icons.Rounded.Add, null) },
                        text = { Text("Nuovo") },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = CircleShape,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 104.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                ScreenHeader(
                    title = "Allenamenti",
                    overline = overlineFormat.format(LocalDate.now()),
                ) {
                    SettingsButton(onSettings)
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "Menu") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Importa JSON") },
                                leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                                onClick = { menuOpen = false; import() },
                            )
                            DropdownMenuItem(
                                text = { Text("Esporta tutto") },
                                leadingIcon = { Icon(Icons.Rounded.FileUpload, null) },
                                onClick = { menuOpen = false; exportLauncher.launch("allenamenti.json") },
                            )
                            DropdownMenuItem(
                                text = { Text("Formato JSON") },
                                leadingIcon = { Icon(Icons.Rounded.Code, null) },
                                onClick = { menuOpen = false; showFormat = true },
                            )
                        }
                    }
                }
            }
            item { ReadinessCard(hrv, plannedTitle = today?.takeIf { !it.done }?.title, onOpen = onHrv) }
            today?.let { plan ->
                item {
                    TodayCard(plan, onStart = { plan.program?.let(onStart) ?: onRun() })
                }
            }
            if (programs.isEmpty()) {
                item { EmptyPrograms(onCreate = { onEdit(null) }, onImport = import) }
            }
            items(programs, key = { it.id }) { program ->
                ProgramCard(
                    program = program,
                    onStart = { onStart(program) },
                    onEdit = { onEdit(program) },
                    onProgress = { onProgress(program) },
                    onDelete = { toDelete = program },
                )
            }
        }
    }

    toDelete?.let { program ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Eliminare \"${program.name}\"?") },
            confirmButton = {
                TextButton(onClick = { repository.delete(program.id); toDelete = null }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } },
        )
    }

    if (showFormat) {
        AlertDialog(
            onDismissRequest = { showFormat = false },
            title = { Text("Formato JSON") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Usa \"reps\" per le serie a ripetizioni o \"seconds\" per quelle a tempo. " +
                            "\"rest\" è il recupero in secondi. Puoi importare un programma o un array di programmi."
                    )
                    SelectionContainer {
                        Text(
                            JSON_EXAMPLE,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                                .padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFormat = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun EmptyPrograms(onCreate: () -> Unit, onImport: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(Icons.Rounded.FitnessCenter, MaterialTheme.colorScheme.primary, size = 88.dp)
        Spacer(Modifier.height(20.dp))
        Text("Nessun programma", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Crea il tuo primo allenamento o importa un file JSON.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreate, modifier = Modifier.height(52.dp)) {
            Icon(Icons.Rounded.Add, null)
            Text("Crea programma", Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = onImport) { Text("Importa JSON") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgramCard(
    program: Program,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onProgress: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(program.id) { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Panel(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.10f), accent.copy(alpha = 0f))))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    program.name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).padding(top = 6.dp),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "Opzioni") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Progressi") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TrendingUp, null) },
                            onClick = { menuOpen = false; onProgress() },
                        )
                        DropdownMenuItem(
                            text = { Text("Elimina") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                InfoPill("${program.exercises.size} esercizi", Icons.Rounded.Layers)
                program.rounds?.let { InfoPill("Circuito · $it giri", Icons.Rounded.Autorenew) }
                    ?: InfoPill("${program.exercises.sumOf { it.sets }} serie", Icons.Rounded.Repeat)
                if (program.exercises.isNotEmpty()) InfoPill("~${program.estimatedMinutes()} min", Icons.Rounded.Schedule)
            }

            Column(
                Modifier.padding(top = 16.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val shown = if (expanded) program.exercises else program.exercises.take(PREVIEW_EXERCISES)
                shown.forEachIndexed { i, ex ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(24.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                ex.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = if (expanded) 2 else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (expanded && ex.notes.isNotBlank()) {
                                Text(
                                    ex.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            if (program.circuit) ex.amountText() else ex.summary(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                val hidden = program.exercises.size - PREVIEW_EXERCISES
                if (hidden > 0) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = PaddingValues(start = 36.dp, end = 12.dp),
                    ) {
                        Text(if (expanded) "Mostra meno" else "+ $hidden ${if (hidden == 1) "altro" else "altri"} · mostra tutti")
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            null,
                            Modifier.padding(start = 4.dp).size(18.dp),
                        )
                    }
                }
            }

            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStart,
                    enabled = program.exercises.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Text("Inizia", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Rounded.Edit, "Modifica", Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun TodayCard(plan: Planned, onStart: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Panel(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.04f))))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                if (plan.isRun) Icons.AutoMirrored.Rounded.DirectionsRun else Icons.Rounded.EventAvailable,
                accent,
            )
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("OGGI IN PROGRAMMA", style = MaterialTheme.typography.labelSmall, color = accent)
                Text(plan.title, style = MaterialTheme.typography.titleLarge)
            }
            if (plan.done) {
                InfoPill("Fatto", Icons.Rounded.Check)
            } else {
                Button(onClick = onStart) { Text(if (plan.isRun) "Corri" else "Inizia") }
            }
        }
    }
}
