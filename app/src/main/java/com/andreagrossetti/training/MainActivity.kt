package com.andreagrossetti.training

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andreagrossetti.training.ai.LocalAi
import com.andreagrossetti.training.ai.ProgramGenerator
import com.andreagrossetti.training.data.Program
import com.andreagrossetti.training.data.programJson
import com.andreagrossetti.training.reminder.Reminders
import com.andreagrossetti.training.ui.AiProgramSheet
import com.andreagrossetti.training.ui.EditorScreen
import com.andreagrossetti.training.ui.HrvScreen
import com.andreagrossetti.training.ui.LogEntryScreen
import com.andreagrossetti.training.ui.LogScreen
import com.andreagrossetti.training.ui.NoteSheet
import com.andreagrossetti.training.ui.ProgramListScreen
import com.andreagrossetti.training.ui.ProgressScreen
import com.andreagrossetti.training.ui.RunDetailScreen
import com.andreagrossetti.training.ui.RunHomeScreen
import com.andreagrossetti.training.ui.RunScreen
import com.andreagrossetti.training.ui.SettingsScreen
import com.andreagrossetti.training.ui.TrainingTheme
import com.andreagrossetti.training.ui.WorkoutScreen
import com.andreagrossetti.training.widget.TrainingWidget
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Action requested from outside (the home screen widget), consumed by the UI. */
    private val launchAction = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) launchAction.value = intent
        val app = application as TrainingApp
        setContent {
            TrainingTheme {
                val action by launchAction.collectAsStateWithLifecycle()
                AppRoot(app, action, onActionHandled = { launchAction.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchAction.value = intent
    }
}

private const val NEW_PROGRAM = "new"

private enum class Tab { PROGRAMS, RUN, LOG }

private sealed interface Screen {
    data object Workout : Screen
    data object Run : Screen
    data class RunDetail(val id: String) : Screen
    data class Editor(val id: String) : Screen
    data class Progress(val id: String) : Screen
    data object AddLog : Screen
    data object Settings : Screen
    data object Hrv : Screen
    data object Tabs : Screen
}

@Composable
private fun AppRoot(app: TrainingApp, action: Intent?, onActionHandled: () -> Unit) {
    RequestNotificationPermission()
    val workout by app.engine.state.collectAsStateWithLifecycle()
    val run by app.tracker.state.collectAsStateWithLifecycle()
    val entries by app.log.entries.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.PROGRAMS) }
    // null = no editor open, NEW_PROGRAM or a program id = editor.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var addingLog by rememberSaveable { mutableStateOf(false) }
    var viewingRun by rememberSaveable { mutableStateOf<String?>(null) }
    var progressOf by rememberSaveable { mutableStateOf<String?>(null) }
    var inSettings by rememberSaveable { mutableStateOf(false) }
    var inHrv by rememberSaveable { mutableStateOf(false) }
    // Diary entry to annotate after a workout stopped early and saved.
    var noteFor by rememberSaveable { mutableStateOf<String?>(null) }
    var autoStartRun by remember { mutableStateOf(false) }
    // Program drafted by the AI, as JSON, waiting for review in the editor.
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    var aiSheet by rememberSaveable { mutableStateOf(false) }
    val aiReady by produceState(false) {
        value = runCatching { app.ai.status() == LocalAi.Status.AVAILABLE }.getOrDefault(false)
    }

    LaunchedEffect(action) {
        when (action?.action) {
            TrainingWidget.ACTION_START_RUN -> {
                tab = Tab.RUN
                autoStartRun = true
            }
            Reminders.ACTION_OPEN_HRV -> inHrv = true
            TrainingWidget.ACTION_START_PROGRAM -> {
                action.getStringExtra(TrainingWidget.EXTRA_PROGRAM_ID)?.let(app.repository::get)?.let(app.engine::start)
            }
        }
        if (action != null) onActionHandled()
    }

    // While a workout or a run is on, its screen is shown on top of the lock screen.
    val activity = LocalActivity.current
    val active = workout != null || run != null
    LaunchedEffect(active) {
        if (Build.VERSION.SDK_INT >= 27) activity?.setShowWhenLocked(active)
    }

    val screen = when {
        workout != null -> Screen.Workout
        run != null -> Screen.Run
        editing != null -> Screen.Editor(editing!!)
        addingLog -> Screen.AddLog
        viewingRun != null -> Screen.RunDetail(viewingRun!!)
        progressOf != null -> Screen.Progress(progressOf!!)
        inSettings -> Screen.Settings
        inHrv -> Screen.Hrv
        else -> Screen.Tabs
    }
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(220, delayMillis = 60), initialScale = 0.96f))
                .togetherWith(fadeOut(tween(120)))
        },
        label = "screen",
    ) { target ->
        when (target) {
            Screen.Workout -> workout?.let {
                WorkoutScreen(it, app.engine, app.log, onSavedEarly = { id -> noteFor = id })
            }
            Screen.Run -> run?.let { RunScreen(it, app.tracker, onSave = { final -> viewingRun = app.saveRun(final) }) }
            is Screen.RunDetail -> {
                val entry = entries.find { it.id == target.id }
                if (entry != null) {
                    RunDetailScreen(
                        entry = entry,
                        route = remember(target.id) { app.log.routes.load(target.id) },
                        allEntries = entries,
                        onUpdate = { effort, notes -> app.log.update(entry.id) { it.copy(effort = effort, notes = notes) } },
                        onClose = { viewingRun = null },
                    )
                }
            }
            is Screen.Editor -> {
                val close = { editing = null; draft = null }
                BackHandler(onBack = close)
                val stored = app.repository.get(target.id)
                val drafted = if (stored == null) draft?.let { programJson.decodeFromString<Program>(it) } else null
                EditorScreen(
                    initial = stored ?: drafted,
                    existing = stored != null,
                    notice = if (drafted != null) "Creato con l'AI: controlla esercizi, serie e recuperi prima di salvare." else null,
                    onSave = { app.repository.save(it); close() },
                    onClose = close,
                )
            }
            is Screen.Progress -> app.repository.get(target.id)?.let { program ->
                ProgressScreen(program, app.log, onUpdate = app.repository::save, onClose = { progressOf = null })
            }
            Screen.AddLog -> {
                BackHandler { addingLog = false }
                LogEntryScreen(
                    programs = app.repository.programs.value,
                    onSave = { app.log.add(it); addingLog = false },
                    onClose = { addingLog = false },
                )
            }
            Screen.Settings -> SettingsScreen(app, onClose = { inSettings = false })
            Screen.Hrv -> HrvScreen(app.hrvSession, app.hrv, onClose = { inHrv = false })
            Screen.Tabs -> Tabs(
                app = app,
                tab = tab,
                onTab = { tab = it },
                onEdit = { editing = it?.id ?: NEW_PROGRAM },
                onAiCreate = if (aiReady) ({ aiSheet = true }) else null,
                onProgress = { progressOf = it },
                onAddLog = { addingLog = true },
                onOpenRun = { viewingRun = it },
                onSettings = { inSettings = true },
                onHrv = { inHrv = true },
                autoStartRun = autoStartRun,
                onAutoStarted = { autoStartRun = false },
            )
        }
    }

    if (aiSheet) {
        AiProgramSheet(
            generator = app.programGenerator,
            knownExercises = { ProgramGenerator.knownExercises(app.repository.programs.value, entries) },
            onDraft = {
                draft = programJson.encodeToString(it)
                aiSheet = false
                editing = NEW_PROGRAM
            },
            onDismiss = { aiSheet = false },
        )
    }

    noteFor?.let { id ->
        entries.find { it.id == id }?.let { entry ->
            NoteSheet(
                entry = entry,
                onSave = { effort, notes ->
                    app.log.update(id) { it.copy(effort = effort, notes = notes) }
                    noteFor = null
                },
                onDismiss = { noteFor = null },
                onPlace = { place -> app.log.update(id) { it.copy(place = place) } },
            )
        }
    }
}

@Composable
private fun Tabs(
    app: TrainingApp,
    tab: Tab,
    onTab: (Tab) -> Unit,
    onEdit: (Program?) -> Unit,
    onAiCreate: (() -> Unit)?,
    onProgress: (String) -> Unit,
    onAddLog: () -> Unit,
    onOpenRun: (String) -> Unit,
    onSettings: () -> Unit,
    onHrv: () -> Unit,
    autoStartRun: Boolean,
    onAutoStarted: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                val colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                )
                NavigationBarItem(
                    selected = tab == Tab.PROGRAMS,
                    onClick = { onTab(Tab.PROGRAMS) },
                    icon = { Icon(Icons.Rounded.FitnessCenter, null) },
                    label = { Text("Programmi") },
                    colors = colors,
                )
                NavigationBarItem(
                    selected = tab == Tab.RUN,
                    onClick = { onTab(Tab.RUN) },
                    icon = { Icon(Icons.AutoMirrored.Rounded.DirectionsRun, null) },
                    label = { Text("Corsa") },
                    colors = colors,
                )
                NavigationBarItem(
                    selected = tab == Tab.LOG,
                    onClick = { onTab(Tab.LOG) },
                    icon = { Icon(Icons.Rounded.CalendarMonth, null) },
                    label = { Text("Diario") },
                    colors = colors,
                )
            }
        },
    ) { padding ->
        Crossfade(tab, Modifier.padding(padding).consumeWindowInsets(padding), label = "tab") { current ->
            when (current) {
                Tab.PROGRAMS -> ProgramListScreen(
                    repository = app.repository,
                    log = app.log,
                    settings = app.settings,
                    hrv = app.hrv,
                    onStart = app.engine::start,
                    onEdit = onEdit,
                    onAiCreate = onAiCreate,
                    onProgress = { onProgress(it.id) },
                    onRun = { onTab(Tab.RUN) },
                    onSettings = onSettings,
                    onHrv = onHrv,
                )
                Tab.RUN -> RunHomeScreen(
                    log = app.log,
                    tracker = app.tracker,
                    settings = app.settings,
                    onOpenRun = onOpenRun,
                    onSettings = onSettings,
                    autoStart = autoStartRun,
                    onAutoStarted = onAutoStarted,
                )
                Tab.LOG -> LogScreen(app.log, app.settings, onAdd = onAddLog, onOpenRun = onOpenRun, onSettings = onSettings)
            }
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(permission)
        }
    }
}
