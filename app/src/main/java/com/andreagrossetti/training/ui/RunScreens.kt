package com.andreagrossetti.training.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andreagrossetti.training.data.LogEntry
import com.andreagrossetti.training.data.LogKind
import com.andreagrossetti.training.data.IntervalPlan
import com.andreagrossetti.training.data.IntervalRecord
import com.andreagrossetti.training.data.LogRepository
import com.andreagrossetti.training.data.SettingsRepository
import com.andreagrossetti.training.data.date
import com.andreagrossetti.training.data.effortLabels
import com.andreagrossetti.training.data.intervalPresets
import com.andreagrossetti.training.data.recordDistances
import com.andreagrossetti.training.data.recordLabel
import com.andreagrossetti.training.data.RoutePoint
import com.andreagrossetti.training.data.formatKm
import com.andreagrossetti.training.data.formatPace
import com.andreagrossetti.training.run.IntervalPhase
import com.andreagrossetti.training.run.MAX_ACCURACY_M
import com.andreagrossetti.training.run.RunPhase
import com.andreagrossetti.training.run.RunState
import com.andreagrossetti.training.run.RunTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min

private val NumberStyle = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
private val detailDateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy · HH:mm", Locale.ITALIAN)

fun formatClock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ---------------------------------------------------------------------------------------------
// Tab "Corsa"

@Composable
fun RunHomeScreen(
    log: LogRepository,
    tracker: RunTracker,
    settings: SettingsRepository,
    onOpenRun: (String) -> Unit,
    onSettings: () -> Unit,
    /** Set by the widget's "Corsa" button: start a free run right away. */
    autoStart: Boolean = false,
    onAutoStarted: () -> Unit = {},
) {
    val context = LocalContext.current
    val entries by log.entries.collectAsStateWithLifecycle()
    val prefs by settings.settings.collectAsStateWithLifecycle()
    val runs = entries.filter { it.kind == LogKind.RUN }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var denied by remember { mutableStateOf(false) }
    var batteryOptimized by remember { mutableStateOf(false) }
    var choosingIntervals by remember { mutableStateOf(false) }
    // Plan waiting for the permission dialog.
    var pendingPlan by remember { mutableStateOf<IntervalPlan?>(null) }

    LifecycleResumeEffect(Unit) {
        val power = context.getSystemService(PowerManager::class.java)
        batteryOptimized = !power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose {}
    }

    @SuppressLint("MissingPermission") // only called once the permission is granted
    fun open(plan: IntervalPlan?) {
        if (!tracker.locationEnabled) {
            scope.launch { snackbar.showSnackbar("Attiva la localizzazione per registrare la corsa") }
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } else {
            tracker.open(plan)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            denied = false
            open(pendingPlan)
        } else {
            denied = true
        }
    }
    fun start(plan: IntervalPlan?) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            open(plan)
        } else {
            pendingPlan = plan
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    LaunchedEffect(autoStart) {
        if (autoStart) {
            onAutoStarted()
            start(null)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ScreenHeader("Corsa", overline = "GPS del telefono") { SettingsButton(onSettings) } }
            item { StartCard(onFree = { start(null) }, onIntervals = { choosingIntervals = true }) }
            if (denied) {
                item {
                    HintCard(
                        icon = Icons.Rounded.LocationOff,
                        text = "Serve il permesso di posizione precisa per misurare percorso e distanza.",
                        action = "Impostazioni",
                        onAction = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            )
                        },
                    )
                }
            }
            if (batteryOptimized) {
                item {
                    HintCard(
                        icon = Icons.Rounded.BatteryAlert,
                        text = "Per un GPS affidabile a schermo spento, escludi l'app dall'ottimizzazione della batteria.",
                        action = "Escludi",
                        onAction = {
                            @SuppressLint("BatteryLife") // personal app, the user asked for it
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            )
                            context.startActivity(intent)
                        },
                    )
                }
            }
            if (runs.isNotEmpty()) {
                item { RunStats(runs, prefs.monthlyGoalKm) }
                val records = personalRecords(runs)
                if (records.isNotEmpty()) item { RecordsCard(records, onOpenRun) }
                item {
                    Text(
                        "ULTIME CORSE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                    )
                }
                items(runs.take(5), key = { it.id }) { run ->
                    RunRow(run, onClick = if (run.hasRoute) ({ onOpenRun(run.id) }) else null)
                }
            }
        }
    }

    if (choosingIntervals) {
        IntervalSheet(
            last = prefs.lastInterval,
            onStart = { plan ->
                choosingIntervals = false
                settings.update { it.copy(lastInterval = plan) }
                start(plan)
            },
            onDismiss = { choosingIntervals = false },
        )
    }
}

@Composable
private fun StartCard(onFree: () -> Unit, onIntervals: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Panel(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.25f), accent.copy(alpha = 0.03f))))
                .padding(20.dp)
        ) {
            IconBadge(Icons.AutoMirrored.Rounded.DirectionsRun, accent, size = 56.dp)
            Text("Pronto a correre?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
            Text(
                "Corsa libera, oppure ripetute guidate: alternando corsa e recupero si migliora prima.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val padding = PaddingValues(horizontal = 12.dp)
                Button(onClick = onFree, modifier = Modifier.weight(1f).height(56.dp), contentPadding = padding) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                    Text("Corsa libera", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                OutlinedButton(onClick = onIntervals, modifier = Modifier.weight(1f).height(56.dp), contentPadding = padding) {
                    Icon(Icons.Rounded.Repeat, null, Modifier.size(20.dp))
                    Text("Ripetute", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalSheet(last: IntervalPlan?, onStart: (IntervalPlan) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(last ?: IntervalPlan(5, workSeconds = 120, recoverySeconds = 60)) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text("Ripetute guidate", style = MaterialTheme.typography.headlineSmall) }
            item {
                Text(
                    "Un segnale ti dice quando correre e quando recuperare (camminando o corricchiando).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(intervalPresets) { plan ->
                Panel(Modifier.fillMaxWidth(), onClick = { onStart(plan) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plan.name, style = MaterialTheme.typography.titleMedium)
                            Text(plan.label(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                Text(
                    "PERSONALIZZATE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val byDistance = custom.workMeters != null
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = !byDistance,
                                onClick = { custom = custom.copy(workMeters = null, workSeconds = custom.workSeconds ?: 120) },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                            ) { Text("A tempo") }
                            SegmentedButton(
                                selected = byDistance,
                                onClick = { custom = custom.copy(workMeters = custom.workMeters ?: 400, workSeconds = null) },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                            ) { Text("A distanza") }
                        }
                        Stepper("Ripetizioni", custom.repeats, { custom = custom.copy(repeats = it) }, min = 1)
                        if (byDistance) {
                            Stepper("Corsa", custom.workMeters ?: 0, { custom = custom.copy(workMeters = it) }, step = 100, min = 100, suffix = "m")
                        } else {
                            Stepper("Corsa", custom.workSeconds ?: 0, { custom = custom.copy(workSeconds = it) }, step = 15, min = 15, suffix = "s")
                        }
                        Stepper("Recupero", custom.recoverySeconds, { custom = custom.copy(recoverySeconds = it) }, step = 15, min = 15, suffix = "s")
                        Button(
                            onClick = { onStart(custom.copy(name = "Ripetute ${custom.repeats} × ${custom.workText()}")) },
                            enabled = custom.repeats > 0 && (custom.workMeters ?: custom.workSeconds ?: 0) > 0 && custom.recoverySeconds > 0,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("Inizia ${custom.label()}") }
                    }
                }
            }
        }
    }
}

/** Best time over each record distance across [runs], with the run it came from. */
private fun personalRecords(runs: List<LogEntry>): List<Triple<Int, Int, LogEntry>> =
    recordDistances.mapNotNull { d ->
        runs.mapNotNull { run -> run.bestEfforts?.get(d)?.let { it to run } }.minByOrNull { it.first }
            ?.let { (seconds, run) -> Triple(d, seconds, run) }
    }

@Composable
private fun RecordsCard(records: List<Triple<Int, Int, LogEntry>>, onOpenRun: (String) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.EmojiEvents, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                Text("RECORD PERSONALI", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
            }
            records.forEach { (distance, seconds, run) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(recordLabel(distance), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(72.dp))
                    Text(
                        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ITALIAN).format(run.date()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onOpenRun(run.id) }, enabled = run.hasRoute) {
                        Text(formatClock(seconds * 1000L), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
                    }
                }
            }
        }
    }
}

@Composable
private fun HintCard(icon: ImageVector, text: String, action: String, onAction: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp, end = 16.dp)) {
            IconBadge(icon, MaterialTheme.colorScheme.tertiary, size = 40.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(action) }
            }
        }
    }
}

@Composable
private fun RunStats(runs: List<LogEntry>, monthlyGoal: Int?) {
    val today = LocalDate.now()
    val monday = today.with(DayOfWeek.MONDAY)
    fun km(filter: (LocalDate) -> Boolean) = runs.filter { filter(it.date()) }.sumOf { it.distanceKm ?: 0.0 }
    val week = km { !it.isBefore(monday) }
    val month = km { it.year == today.year && it.month == today.month }
    val total = runs.sumOf { it.distanceKm ?: 0.0 }
    Column {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(kmText(week), "settimana", Modifier.weight(1f), unit = "km")
        StatTile(kmText(month), "mese", Modifier.weight(1f), unit = "km")
        StatTile(kmText(total), "in totale", Modifier.weight(1f), unit = "km")
    }
    if (monthlyGoal != null) {
        Panel(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            GoalBar(
                label = "Obiettivo: $monthlyGoal km al mese",
                value = month.toFloat(),
                goal = monthlyGoal.toFloat(),
                valueText = "%.1f/%d km".format(Locale.ITALIAN, month, monthlyGoal),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier, unit: String? = null) {
    Panel(modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Text(
                buildAnnotatedString {
                    append(value)
                    unit?.let { withStyle(SpanStyle(fontSize = 14.sp)) { append(" $it") } }
                },
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RunRow(run: LogEntry, onClick: (() -> Unit)?) {
    Panel(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.AutoMirrored.Rounded.DirectionsRun, MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    "%.2f km".format(Locale.ITALIAN, run.distanceKm ?: 0.0),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN).format(run.date()) +
                        (run.durationSeconds?.let { " · " + formatClock(it * 1000L) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatPace(run.averagePace()), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
                Text("/km", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "0,8", or "123" once decimals stop mattering. */
private fun kmText(km: Double): String = (if (km < 100) "%.1f" else "%.0f").format(Locale.ITALIAN, km)

/** Average pace of a logged run, s/km. */
fun LogEntry.averagePace(): Int? {
    val km = distanceKm?.takeIf { it > 0.05 } ?: return null
    return durationSeconds?.let { (it / km).toInt() }
}

// ---------------------------------------------------------------------------------------------
// Live run

private fun runGradient(phase: RunPhase): Pair<Color, Color> = when (phase) {
    RunPhase.ACQUIRING -> Color(0xFF2E1308) to Color(0xFF0A0C0F)
    RunPhase.RUNNING -> Color(0xFF3A1406) to Color(0xFF0A0C0F)
    RunPhase.PAUSED -> Color(0xFF3D2A06) to Color(0xFF0A0C0F)
}

@Composable
fun RunScreen(state: RunState, tracker: RunTracker, onSave: (RunState) -> Unit) {
    var confirmStop by remember { mutableStateOf(false) }
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val cancel = { tracker.stop(); Unit }
    BackHandler {
        when (state.phase) {
            RunPhase.ACQUIRING -> cancel()
            RunPhase.RUNNING -> Unit // back must never lose a run
            RunPhase.PAUSED -> confirmStop = true
        }
    }

    val (top, bottom) = runGradient(state.phase)
    val topColor by animateColorAsState(top, tween(600), label = "top")
    val bottomColor by animateColorAsState(bottom, tween(600), label = "bottom")
    val accent = if (state.phase == RunPhase.PAUSED) Color(0xFFFFB547) else Brand

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CORSA", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                    Text(
                        when (state.phase) {
                            RunPhase.ACQUIRING -> "Preparati"
                            RunPhase.RUNNING -> "In corso"
                            RunPhase.PAUSED -> "In pausa"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                GpsChip(state.accuracyM)
                if (state.phase == RunPhase.ACQUIRING) {
                    IconButton(
                        onClick = cancel,
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.16f)),
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Icon(Icons.Rounded.Close, "Annulla") }
                }
            }

            state.plan?.let { IntervalBanner(state, it, now, Modifier.padding(top = 16.dp)) }

            Column(
                Modifier.fillMaxWidth().padding(top = if (state.plan != null) 12.dp else 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(formatKm(state.distanceM), style = NumberStyle, fontSize = if (state.plan != null) 72.sp else 96.sp, color = accent)
                Text("chilometri", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f))
            }

            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveStat(formatClock(state.movingMs(now)), "tempo", Modifier.weight(1f))
                LiveStat(formatPace(state.currentPace(now)), "passo /km", Modifier.weight(1f))
                LiveStat(formatPace(state.averagePace(now)), "medio /km", Modifier.weight(1f))
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .background(Color.White.copy(alpha = 0.06f), MaterialTheme.shapes.large),
                contentAlignment = Alignment.Center,
            ) {
                if (state.points.size >= 2) {
                    RouteView(state.points, accent, Modifier.fillMaxSize().padding(20.dp))
                } else {
                    Text(
                        if (state.phase == RunPhase.ACQUIRING) {
                            if (state.accuracyM?.let { it <= MAX_ACCURACY_M } == true) "GPS pronto: quando vuoi, VIA!"
                            else "Ricerca del segnale GPS…\nMeglio all'aperto, lontano dagli edifici."
                        } else "Il percorso comparirà qui",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            val big = Modifier.fillMaxWidth().height(68.dp)
            val brand = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand)
            val white = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0A0C0F))
            when (state.phase) {
                RunPhase.ACQUIRING -> RunButton("VIA", Icons.Rounded.PlayArrow, tracker::go, big, brand)
                RunPhase.RUNNING -> RunButton("PAUSA", Icons.Rounded.Pause, tracker::togglePause, big, white)
                RunPhase.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RunButton("RIPRENDI", Icons.Rounded.PlayArrow, tracker::togglePause, Modifier.weight(1f).height(68.dp), brand)
                    RunButton("FINE", Icons.Rounded.Flag, { confirmStop = true }, Modifier.weight(1f).height(68.dp), white)
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Terminare la corsa?") },
            text = { Text("${formatKm(state.distanceM)} km in ${formatClock(state.movingMs(now))}") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    tracker.stop()?.let(onSave)
                }) { Text("Salva") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmStop = false; tracker.stop() }) { Text("Scarta") }
                    TextButton(onClick = { confirmStop = false }) { Text("Annulla") }
                }
            },
        )
    }
}

/** Current repeat, phase and what's left of it, colored by phase. */
@Composable
private fun IntervalBanner(state: RunState, plan: IntervalPlan, now: Long, modifier: Modifier = Modifier) {
    val phase = state.intervalPhase
    val color by animateColorAsState(
        when (phase) {
            IntervalPhase.WORK -> Brand
            IntervalPhase.RECOVERY -> Color(0xFF3D86F5)
            IntervalPhase.DONE -> Color(0xFFA254F2)
            null -> Color.White.copy(alpha = 0.12f)
        },
        label = "interval",
    )
    Row(
        modifier.fillMaxWidth().background(color, MaterialTheme.shapes.large).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                when (phase) {
                    null -> plan.name.uppercase()
                    IntervalPhase.DONE -> "RIPETUTE COMPLETATE"
                    else -> "RIPETUTA ${state.interval + 1}/${plan.repeats}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(
                when (phase) {
                    null -> plan.label()
                    IntervalPhase.WORK -> "CORRI"
                    IntervalPhase.RECOVERY -> "RECUPERO"
                    IntervalPhase.DONE -> "Defatica con calma"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        state.intervalRemaining(now)?.let { (ms, meters) ->
            Text(
                ms?.let { formatClock(it + 999) } ?: "${meters?.toInt()} m",
                style = NumberStyle,
                fontSize = 40.sp,
            )
        }
    }
}

@Composable
private fun GpsChip(accuracy: Float?) {
    val color = when {
        accuracy == null -> Color(0xFFFF6B6B)
        accuracy <= 10f -> Color(0xFF4ADE80)
        accuracy <= MAX_ACCURACY_M -> Color(0xFFFFD34D)
        else -> Color(0xFFFF6B6B)
    }
    Row(
        Modifier.background(Color.White.copy(alpha = 0.12f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.GpsFixed, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(
            accuracy?.takeIf { it < 1000 }?.let { "±${it.toInt()} m" } ?: "GPS…",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun LiveStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color.White.copy(alpha = 0.08f), MaterialTheme.shapes.large).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = NumberStyle, fontSize = 28.sp)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f))
    }
}

@Composable
private fun RunButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier, colors: ButtonColors) {
    Button(onClick = onClick, modifier = modifier, colors = colors, shape = CircleShape) {
        Icon(icon, null, Modifier.size(26.dp))
        Text(text, style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 1.sp), modifier = Modifier.padding(start = 8.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Route drawing

/**
 * Draws the route as a line fitted to the available space. Longitude is scaled by
 * cos(latitude), which is plenty accurate at the scale of a run.
 */
@Composable
fun RouteView(points: List<RoutePoint>, color: Color, modifier: Modifier = Modifier) {
    val startColor = MaterialTheme.colorScheme.secondary
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val midLat = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2
        val kx = cos(Math.toRadians(midLat))
        val xs = points.map { it.lon * kx }
        val ys = points.map { -it.lat }
        val minX = xs.min()
        val minY = ys.min()
        val spanX = (xs.max() - minX).coerceAtLeast(1e-9)
        val spanY = (ys.max() - minY).coerceAtLeast(1e-9)
        val scale = min(size.width / spanX, size.height / spanY)
        // Center the route in the canvas.
        val dx = (size.width - spanX * scale) / 2
        val dy = (size.height - spanY * scale) / 2
        fun at(i: Int) = Offset((dx + (xs[i] - minX) * scale).toFloat(), (dy + (ys[i] - minY) * scale).toFloat())

        val path = Path().apply {
            moveTo(at(0).x, at(0).y)
            for (i in 1 until points.size) lineTo(at(i).x, at(i).y)
        }
        drawPath(path, color.copy(alpha = 0.25f), style = Stroke(12.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, color, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Color.White, 7.dp.toPx(), at(0))
        drawCircle(startColor, 5.dp.toPx(), at(0))
        drawCircle(Color.White, 7.dp.toPx(), at(points.lastIndex))
        drawCircle(color, 5.dp.toPx(), at(points.lastIndex))
    }
}

// ---------------------------------------------------------------------------------------------
// Run detail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailScreen(
    entry: LogEntry,
    route: List<RoutePoint>,
    allEntries: List<LogEntry>,
    onUpdate: (effort: Int?, notes: String) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val splits = entry.splitsSeconds.orEmpty()
    var editingNote by remember { mutableStateOf(false) }
    // Records this run set: faster than every earlier run over that distance.
    val newRecords = entry.bestEfforts.orEmpty().filter { (distance, seconds) ->
        allEntries.none { other ->
            other.timestamp < entry.timestamp && (other.bestEfforts?.get(distance) ?: Int.MAX_VALUE) <= seconds
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(entry.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            detailDateFormat.format(Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.systemDefault()))
                                .replaceFirstChar { it.titlecase(Locale.ITALIAN) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            if (newRecords.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.large)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.EmojiEvents, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(32.dp))
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(
                                if (newRecords.size == 1) "Nuovo record personale!" else "Nuovi record personali!",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(
                                newRecords.entries.joinToString(" · ") { (d, sec) -> "${recordLabel(d)} in ${formatClock(sec * 1000L)}" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
            if (route.size >= 2) {
                item {
                    Panel(Modifier.fillMaxWidth().height(340.dp)) {
                        RouteMap(route, Modifier.fillMaxSize())
                    }
                }
            }
            item {
                val fastest = splits.minOrNull()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("%.2f".format(Locale.ITALIAN, entry.distanceKm ?: 0.0), "km", Modifier.weight(1f))
                        StatTile(formatClock((entry.durationSeconds ?: 0) * 1000L), "tempo", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(formatPace(entry.averagePace()), "passo medio /km", Modifier.weight(1f))
                        StatTile(formatPace(fastest), "km più veloce", Modifier.weight(1f))
                    }
                }
            }
            item {
                Panel(Modifier.fillMaxWidth(), onClick = { editingNote = true }) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Com'è andata?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.EditNote, "Modifica", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            listOfNotNull(
                                entry.effort?.let { "Fatica: ${effortLabels[it - 1]}" },
                                entry.notes.takeIf { it.isNotBlank() },
                            ).joinToString("\n").ifEmpty { "Aggiungi fatica e note" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            entry.intervals?.takeIf { it.isNotEmpty() }?.let { intervals ->
                item {
                    Text(
                        "RIPETUTE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                    )
                }
                item { Intervals(intervals) }
            }
            if (splits.isNotEmpty()) {
                item {
                    Text(
                        "PARZIALI",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                    )
                }
                item { Splits(entry, splits) }
            }
        }
    }

    if (editingNote) {
        NoteSheet(
            entry = entry,
            onSave = { effort, notes -> onUpdate(effort, notes); editingNote = false },
            onDismiss = { editingNote = false },
        )
    }
}

@Composable
private fun Intervals(intervals: List<IntervalRecord>) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            intervals.forEachIndexed { i, rep ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(32.dp))
                    Text(
                        "${rep.distanceM.toInt()} m in ${formatClock(rep.seconds * 1000L)}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatPace(if (rep.distanceM > 20) (rep.seconds * 1000 / rep.distanceM).toInt() else null) + " /km",
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    )
                }
            }
        }
    }
}

@Composable
private fun Splits(entry: LogEntry, splits: List<Int>) {
    // The last, partial kilometre, shown with its extrapolated pace.
    val restKm = (entry.distanceKm ?: 0.0) - splits.size
    val restSeconds = (entry.durationSeconds ?: 0) - splits.sum()
    val rows = splits.mapIndexed { i, s -> "${i + 1}" to s } +
        if (restKm >= 0.05 && restSeconds > 0) listOf("%.2f".format(Locale.ITALIAN, restKm) to (restSeconds / restKm).toInt()) else emptyList()
    val fastest = rows.minOf { it.second }
    val slowest = rows.maxOf { it.second }
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { (label, pace) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(44.dp))
                    // Faster kilometres get longer bars.
                    val fraction = if (slowest == fastest) 1f else 0.35f + 0.65f * (slowest - pace).toFloat() / (slowest - fastest)
                    Box(Modifier.weight(1f).height(10.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(10.dp)
                                .background(
                                    if (pace == fastest) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                    CircleShape,
                                )
                        )
                    }
                    Text(
                        formatPace(pace),
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(56.dp),
                    )
                }
            }
        }
    }
}
