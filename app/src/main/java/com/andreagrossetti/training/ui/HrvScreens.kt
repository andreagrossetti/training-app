package com.andreagrossetti.training.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andreagrossetti.training.data.BASELINE_DAYS
import com.andreagrossetti.training.data.DailyAdvice
import com.andreagrossetti.training.data.LogRepository
import com.andreagrossetti.training.data.dailyAdvice
import com.andreagrossetti.training.data.HrvMeasurement
import com.andreagrossetti.training.data.HrvMetrics
import com.andreagrossetti.training.data.HrvPosition
import com.andreagrossetti.training.data.HrvRepository
import com.andreagrossetti.training.data.MAX_ARTIFACTS
import com.andreagrossetti.training.data.Readiness
import com.andreagrossetti.training.data.ReadinessResult
import com.andreagrossetti.training.data.SettingsRepository
import com.andreagrossetti.training.data.dailyHrv
import com.andreagrossetti.training.data.date
import com.andreagrossetti.training.data.hrvMetrics
import com.andreagrossetti.training.data.readiness
import com.andreagrossetti.training.hrv.HrvPhase
import com.andreagrossetti.training.hrv.HrvSession
import com.andreagrossetti.training.hrv.SensorStatus
import com.andreagrossetti.training.workout.RemoteButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

private val Go = Color(0xFF3DDC84)
private val Stop = Color(0xFFFF5A5A)
private val historyFormat = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ITALIAN)

private val sleepLabels = listOf("Pessimo", "Scarso", "Discreto", "Buono", "Ottimo")
private val moodLabels = listOf("Giù", "Così così", "Normale", "Bene", "Alla grande")

private fun HrvPosition.label(): String = when (this) {
    HrvPosition.LYING -> "Disteso"
    HrvPosition.SITTING -> "Seduto"
    HrvPosition.STANDING -> "In piedi"
}

private fun HrvPosition.instructions(): String = when (this) {
    HrvPosition.LYING -> "Appena sveglio, disteso a letto."
    HrvPosition.SITTING -> "Seduto, schiena appoggiata e piedi a terra."
    HrvPosition.STANDING -> "In piedi, fermo e rilassato."
}

private fun formatTime(timestamp: Long): String =
    historyFormat.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())).replaceFirstChar { it.titlecase(Locale.ITALIAN) }

private fun Readiness.color(): Color = when (this) {
    Readiness.GREEN -> Go
    Readiness.YELLOW -> Amber
    Readiness.RED -> Stop
}

private fun Readiness.title(): String = when (this) {
    Readiness.GREEN -> "Via libera"
    Readiness.YELLOW -> "Vai piano"
    Readiness.RED -> "Giornata di recupero"
}

private fun Readiness.advice(): String = when (this) {
    Readiness.GREEN -> "Sei recuperato: allenati come da programma."
    Readiness.YELLOW -> "Recupero incompleto: sì a corsa facile o forza leggera, niente intensità."
    Readiness.RED -> "Il corpo è sotto stress: meglio riposo, mobilità o una camminata."
}

/** Short hint next to today's planned activity. */
private fun Readiness.planHint(): String = when (this) {
    Readiness.GREEN -> "come da programma"
    Readiness.YELLOW -> "versione leggera"
    Readiness.RED -> "meglio rimandare"
}

private fun calibratingText(r: ReadinessResult.Calibrating): String {
    val left = r.needed - r.days
    return "Sto imparando i tuoi valori: ancora $left ${if (left == 1) "mattina" else "mattine"} e arriva il primo consiglio."
}

/** Home card: today's readiness, or a nudge to take the morning reading. */
@Composable
fun ReadinessCard(hrv: HrvRepository, log: LogRepository, plannedTitle: String?, onOpen: () -> Unit) {
    val measurements by hrv.measurements.collectAsStateWithLifecycle()
    val entries by log.entries.collectAsStateWithLifecycle()
    val result = remember(measurements) { readiness(measurements) }
    val advice = remember(result, entries) { (result as? ReadinessResult.Ready)?.let { dailyAdvice(it, entries) } }
    val accent = advice?.readiness?.color() ?: MaterialTheme.colorScheme.secondary
    Panel(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.MonitorHeart, accent)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("RECUPERO", style = MaterialTheme.typography.labelSmall, color = accent)
                when (result) {
                    null -> Text("Misura l'HRV di stamattina", style = MaterialTheme.typography.titleMedium)
                    is ReadinessResult.Calibrating -> {
                        Text("Misura fatta", style = MaterialTheme.typography.titleMedium)
                        Text(calibratingText(result), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is ReadinessResult.Ready -> {
                        val level = advice?.readiness ?: result.readiness
                        Text(level.title(), style = MaterialTheme.typography.titleLarge)
                        Text(
                            plannedTitle?.let { "$it: ${level.planHint()}" } ?: level.advice(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (result == null) {
                Button(onClick = onOpen) { Text("Misura") }
            } else {
                val today = (result as? ReadinessResult.Ready)?.today ?: dailyHrv(measurements).firstOrNull()
                today?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${it.rmssd.roundToInt()}", style = MaterialTheme.typography.headlineSmall)
                        Text("ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HrvScreen(session: HrvSession, hrv: HrvRepository, settings: SettingsRepository, log: LogRepository, onClose: () -> Unit) {
    val phase by session.phase.collectAsStateWithLifecycle()
    val sensor by session.sensorStatus.collectAsStateWithLifecycle()
    val measurements by hrv.measurements.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var toDelete by remember { mutableStateOf<HrvMeasurement?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    // Most people always measure the same way: start from the last reading's position.
    var position by rememberSaveable { mutableStateOf(measurements.firstOrNull()?.position ?: HrvPosition.LYING) }
    val active = phase !is HrvPhase.Idle && phase !is HrvPhase.Done
    val close = {
        if (phase !is HrvPhase.Idle) session.cancel()
        onClose()
    }
    if (active) KeepScreenOn()

    val detail = detailId?.let { id -> measurements.find { it.id == id } }
    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminare la misura?") },
            text = { Text(formatTime(m.timestamp) + " · ${m.rmssd.roundToInt()} ms") },
            confirmButton = { TextButton(onClick = { hrv.delete(m.id); toDelete = null; detailId = null }) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } },
        )
    }
    if (detail != null) {
        HrvDetailScreen(detail, onBack = { detailId = null }, onEdit = { edit -> hrv.measurements.value.find { it.id == detail.id }?.let { hrv.save(edit(it)) } }, onDelete = { toDelete = detail })
        return
    }
    BackHandler(onBack = close)

    val prefs by settings.settings.collectAsStateWithLifecycle()
    val pace = prefs.hrvBreathPace.takeIf { prefs.hrvBreathGuide }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) session.start(position)
        else scope.launch { snackbar.showSnackbar("Serve il permesso Bluetooth per il sensore") }
    }
    val start = {
        if (RemoteButton.hasPermissions(context)) session.start(position) else permission.launch(RemoteButton.permissions)
    }

    if (active) {
        val liveHr by session.liveHr.collectAsStateWithLifecycle()
        ReadingScreen(phase = phase, sensor = sensor, liveHr = liveHr, pace = pace, onStop = session::cancel)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recupero · HRV", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    MeasurePanel(
                        phase = phase,
                        sensor = sensor,
                        hasToday = measurements.any { it.date() == LocalDate.now() },
                        position = position,
                        onPosition = { position = it },
                        guide = prefs.hrvBreathGuide,
                        pace = prefs.hrvBreathPace,
                        onGuide = { on -> settings.update { it.copy(hrvBreathGuide = on) } },
                        onPace = { p -> settings.update { it.copy(hrvBreathPace = p.coerceIn(MIN_PACE, MAX_PACE)) } },
                        onStart = start,
                        onCancel = session::cancel,
                        onSaveAnyway = session::saveAnyway,
                        onEdit = session::edit,
                    )
                }
            }
            if (!active) {
                val result = readiness(measurements)
                val advice = (result as? ReadinessResult.Ready)?.let { dailyAdvice(it, log.entries.value) }
                result?.let { item { Panel(Modifier.fillMaxWidth()) { ReadinessSummary(it, advice) } } }
                // The trend follows the latest reading's position: mixing them would make a meaningless line.
                val trendPosition = dailyHrv(measurements).firstOrNull()?.position
                val daily = dailyHrv(measurements, trendPosition)
                if (daily.size >= 2) {
                    item { Panel(Modifier.fillMaxWidth()) { TrendChart(daily, result as? ReadinessResult.Ready, trendPosition) } }
                }
                if (measurements.isNotEmpty()) {
                    item {
                        Text(
                            "MISURE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                        )
                    }
                    items(measurements.take(30), key = { it.id }) { m -> MeasurementRow(m, onClick = { detailId = m.id }) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeasurePanel(
    phase: HrvPhase,
    sensor: SensorStatus,
    hasToday: Boolean,
    position: HrvPosition,
    onPosition: (HrvPosition) -> Unit,
    guide: Boolean,
    pace: Int,
    onGuide: (Boolean) -> Unit,
    onPace: (Int) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onSaveAnyway: () -> Unit,
    onEdit: ((HrvMeasurement) -> HrvMeasurement) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (phase) {
            HrvPhase.Idle -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                    HrvPosition.entries.forEach { p -> FilterChip(position == p, { onPosition(p) }, { Text(p.label()) }) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Guida respiro", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Facoltativa: un ritmo lento da seguire col fiore",
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                    }
                    Switch(guide, onGuide)
                }
                if (guide) Stepper("Atti al minuto", pace, onPace, min = MIN_PACE)
                Text(
                    position.instructions() + " Metti il dito nel sensore e " +
                        (if (guide) "respira seguendo il fiore" else "respira normalmente") +
                        ": dura circa 2 minuti e mezzo e un suono ti avvisa alla fine.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (hasToday) "Misura di nuovo" else "Inizia misura", style = MaterialTheme.typography.titleMedium)
                }
            }
            // Shown full screen by ReadingScreen.
            HrvPhase.Waiting, is HrvPhase.Stabilizing, is HrvPhase.Recording -> Unit
            is HrvPhase.Done -> {
                val m = phase.measurement
                when {
                    m == null -> {
                        Text("Dati insufficienti", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Il sensore non ha mandato abbastanza battiti. Controlla che il dito sia ben inserito e fermo.",
                            style = MaterialTheme.typography.bodyMedium, color = muted, textAlign = TextAlign.Center,
                        )
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${m.rmssd.roundToInt()}", style = MaterialTheme.typography.displayMedium)
                            Text(" ms", style = MaterialTheme.typography.titleMedium, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Text(
                            "RMSSD · ${m.meanHr.roundToInt()} bpm · ${m.beats} battiti · ${m.position.label().lowercase()}",
                            style = MaterialTheme.typography.bodyMedium, color = muted,
                        )
                        if (!phase.saved) {
                            val lost = ((1 - m.coverage) * 100).roundToInt()
                            val why = if (m.artifacts > MAX_ARTIFACTS) "${(m.artifacts * 100).roundToInt()}% dei battiti scartati"
                            else "il sensore ha perso il segnale per il $lost% del tempo"
                            Text(
                                "Misura poco affidabile: $why. Meglio ripeterla tenendo il dito fermo.",
                                style = MaterialTheme.typography.bodyMedium, color = Amber, textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                if (m != null) TagEditor(m, onEdit)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (m != null && !phase.saved) {
                        OutlinedButton(onClick = onSaveAnyway) { Text("Salva comunque") }
                        Button(onClick = onStart) { Text("Ripeti") }
                    } else if (m == null) {
                        Button(onClick = onStart) { Text("Riprova") }
                    } else {
                        OutlinedButton(onClick = onCancel) { Text("Chiudi") }
                    }
                }
            }
        }
    }
}

private fun sensorText(sensor: SensorStatus): String = when {
    !sensor.connected && sensor.name != null -> "Collegamento…"
    !sensor.connected -> "Cerco il sensore"
    sensor.contact == false -> "Metti il dito"
    else -> "In attesa del battito"
}

@Composable
private fun SignalWarning(sensor: SensorStatus, poor: Boolean) {
    when {
        sensor.contact == false -> Text("Dito non rilevato", color = Stop, style = MaterialTheme.typography.titleSmall)
        poor -> Text("Segnale scarso: tieni fermo il dito", color = Amber, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ReadinessSummary(result: ReadinessResult, advice: DailyAdvice?) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
        when (result) {
            is ReadinessResult.Calibrating -> {
                IconBadge(Icons.Rounded.MonitorHeart, MaterialTheme.colorScheme.secondary)
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Calibrazione ${result.days}/${result.needed}", style = MaterialTheme.typography.titleMedium)
                    Text(calibratingText(result), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is ReadinessResult.Ready -> {
                val level = advice?.readiness ?: result.readiness
                Box(Modifier.size(44.dp).background(level.color(), CircleShape))
                Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(level.title(), style = MaterialTheme.typography.titleLarge)
                    Text(advice?.suggestion ?: level.advice(), style = MaterialTheme.typography.bodyMedium)
                    advice?.reasons?.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Morning RMSSD over the last [BASELINE_DAYS] days, with the normal range as a band. */
@Composable
private fun TrendChart(daily: List<HrvMeasurement>, ready: ReadinessResult.Ready?, position: HrvPosition?) {
    val today = LocalDate.now()
    val points = daily.filter { ChronoUnit.DAYS.between(it.date(), today) in 0..BASELINE_DAYS.toLong() }
    val line = MaterialTheme.colorScheme.secondary
    val band = Go.copy(alpha = 0.14f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val values = points.map { it.rmssd } + listOfNotNull(ready?.low, ready?.high)
    val min = (values.minOrNull() ?: 0.0) * 0.9
    val max = (values.maxOrNull() ?: 1.0) * 1.1
    Column(Modifier.padding(16.dp)) {
        Text(
            "Ultimi $BASELINE_DAYS giorni" + (position?.takeIf { it != HrvPosition.LYING }?.let { " · ${it.label().lowercase()}" } ?: ""),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            ready?.let { "Fascia verde: il tuo normale (${it.low.roundToInt()}–${it.high.roundToInt()} ms)" } ?: "RMSSD del mattino, ms",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            fun x(m: HrvMeasurement) = size.width * (1f - ChronoUnit.DAYS.between(m.date(), today).toFloat() / BASELINE_DAYS)
            fun y(v: Double) = size.height * (1f - ((v - min) / (max - min)).toFloat())
            ready?.let {
                drawRect(band, Offset(0f, y(it.high)), androidx.compose.ui.geometry.Size(size.width, y(it.low) - y(it.high)))
            }
            val ordered = points.sortedBy { it.timestamp }
            val path = Path()
            ordered.forEachIndexed { i, m -> if (i == 0) path.moveTo(x(m), y(m.rmssd)) else path.lineTo(x(m), y(m.rmssd)) }
            drawPath(path, line, style = Stroke(2.dp.toPx()))
            ordered.forEach { drawCircle(line, 4.dp.toPx(), Offset(x(it), y(it.rmssd))) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("$BASELINE_DAYS gg fa", style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.weight(1f))
            Text("oggi", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

@Composable
private fun MeasurementRow(m: HrvMeasurement, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Panel(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(formatTime(m.timestamp), style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        "${m.meanHr.roundToInt()} bpm",
                        m.position.takeIf { it != HrvPosition.LYING }?.label()?.lowercase(),
                        m.sleep?.let { "sonno ${sleepLabels[it - 1].lowercase()}" },
                        m.mood?.let { "umore ${moodLabels[it - 1].lowercase()}" },
                        "poco affidabile".takeIf { !m.reliable },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (m.reliable) muted else Amber,
                )
                if (m.notes.isNotBlank()) {
                    Text(m.notes, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
                }
            }
            Text("${m.rmssd.roundToInt()} ms", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Sleep, mood and notes of a reading; notes are written a moment after typing stops. */
@Composable
private fun TagEditor(m: HrvMeasurement, onEdit: ((HrvMeasurement) -> HrvMeasurement) -> Unit) {
    var notes by rememberSaveable(m.id) { mutableStateOf(m.notes) }
    val latestNotes by rememberUpdatedState(notes)
    val latestEdit by rememberUpdatedState(onEdit)
    LaunchedEffect(notes) {
        delay(800)
        if (notes != m.notes) onEdit { it.copy(notes = notes) }
    }
    DisposableEffect(m.id) {
        onDispose { latestEdit { if (it.notes != latestNotes) it.copy(notes = latestNotes) else it } }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("SONNO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        EffortPicker(m.sleep, { v -> onEdit { it.copy(sleep = v) } }, labels = sleepLabels, placeholder = "Come hai dormito?")
        Text("UMORE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        EffortPicker(m.mood, { v -> onEdit { it.copy(mood = v) } }, labels = moodLabels, placeholder = "Come ti senti?")
        AppTextField(notes, { notes = it }, "Note", Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
    }
}

/** Everything about one reading, recomputed from its raw beat intervals. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HrvDetailScreen(
    m: HrvMeasurement,
    onBack: () -> Unit,
    onEdit: ((HrvMeasurement) -> HrvMeasurement) -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val metrics = remember(m.id, m.rr) { hrvMetrics(m.rr) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(formatTime(m.timestamp), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") } },
                actions = { IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Elimina", tint = muted) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("POSIZIONE", style = MaterialTheme.typography.labelSmall, color = muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HrvPosition.entries.forEach { p ->
                                FilterChip(m.position == p, { onEdit { it.copy(position = p) } }, { Text(p.label()) })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TagEditor(m, onEdit)
                    }
                }
            }
            if (metrics == null) {
                item {
                    Text(
                        "I battiti di questa misura non sono stati salvati: ci sono solo i valori riassuntivi.",
                        style = MaterialTheme.typography.bodyMedium, color = muted,
                    )
                }
            } else {
                item { Panel(Modifier.fillMaxWidth()) { HeartRateChart(metrics) } }
                item { Panel(Modifier.fillMaxWidth()) { SignalQuality(m) } }
                item { SectionLabel("DOMINIO DEL TEMPO") }
                item {
                    MetricGrid(
                        listOf(
                            Triple("%.1f".format(metrics.rmssd), "ms", "RMSSD"),
                            Triple("%.1f".format(metrics.sdnn), "ms", "SDNN"),
                            Triple("%.2f".format(metrics.lnRmssd), "", "ln RMSSD"),
                            Triple("${(metrics.pnn50 * 100).roundToInt()}", "%", "pNN50"),
                            Triple("${metrics.meanRr.roundToInt()}", "ms", "RR medio"),
                        ),
                    )
                }
                item { SectionLabel("DOMINIO DELLA FREQUENZA") }
                val s = metrics.spectrum
                if (s == null) {
                    item {
                        Text(
                            "Serve almeno un minuto e mezzo di segnale pulito per calcolare le bande di frequenza.",
                            style = MaterialTheme.typography.bodyMedium, color = muted,
                        )
                    }
                } else {
                    item {
                        MetricGrid(
                            listOf(
                                Triple("${s.total.roundToInt()}", "ms²", "Potenza totale"),
                                Triple("%.2f".format(s.lfHf), "", "LF/HF"),
                                Triple("${s.lf.roundToInt()}", "ms²", "Potenza LF"),
                                Triple("${s.hf.roundToInt()}", "ms²", "Potenza HF"),
                                Triple("%.3f".format(s.lfPeak), "Hz", "Picco LF"),
                                Triple("%.3f".format(s.hfPeak), "Hz", "Picco HF"),
                            ),
                        )
                    }
                    item {
                        Text(
                            "LF/HF dipende molto da come respiri (col respiro lento sale anche da rilassato): meglio non leggerlo come stress.",
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun HeartRateChart(metrics: HrvMetrics) {
    val line = Stop
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val points = metrics.hrSeries
    val lo = metrics.minHr - 2
    val hi = metrics.maxHr + 2
    val end = points.last().first
    Column(Modifier.padding(16.dp)) {
        Text("Battito", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            fun x(t: Double) = (size.width * t / end).toFloat()
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            val path = Path()
            points.forEachIndexed { i, (t, bpm) -> if (i == 0) path.moveTo(x(t), y(bpm)) else path.lineTo(x(t), y(bpm)) }
            drawPath(path, line, style = Stroke(2.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("0:00", style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.weight(1f))
            Text("%d:%02d".format(end.toInt() / 60, end.toInt() % 60), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            listOf("Minimo" to metrics.minHr, "Massimo" to metrics.maxHr, "Medio" to metrics.meanHr).forEach { (label, v) ->
                Column(Modifier.weight(1f)) {
                    Text("${v.roundToInt()}", style = MaterialTheme.typography.headlineSmall)
                    Text("$label bpm", style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
    }
}

@Composable
private fun SignalQuality(m: HrvMeasurement) {
    val (label, color) = when {
        !m.reliable -> "scarso" to Amber
        m.artifacts > 0.05 || m.coverage < 0.9 -> "discreto" to MaterialTheme.colorScheme.secondary
        else -> "buono" to Go
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text("Qualità del segnale: ", style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.titleMedium, color = color)
        }
        Text(
            "${m.beats} battiti · ${(m.artifacts * 100).roundToInt()}% scartati come artefatti · " +
                "${((1 - m.coverage) * 100).roundToInt()}% del tempo senza segnale",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Two-column tiles of (value, unit, label). */
@Composable
private fun MetricGrid(tiles: List<Triple<String, String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (value, unit, label) ->
                    Panel(Modifier.weight(1f)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(value, style = MaterialTheme.typography.headlineSmall)
                                if (unit.isNotEmpty()) {
                                    Text(
                                        " $unit",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 3.dp),
                                    )
                                }
                            }
                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private val HeartRed = Color(0xFFE5486A)

/** Range of the guide's pace, breaths a minute. */
private const val MIN_PACE = 5
private const val MAX_PACE = 12

/**
 * The reading in progress, full screen: a breathing guide (or a flower pulsing with the heart), live heart
 * rate and HRV, the heart rate trace, and a stop button ringed by the progress.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingScreen(
    phase: HrvPhase,
    sensor: SensorStatus,
    liveHr: List<Pair<Float, Float>>,
    /** Breaths a minute of the guide; null with the guide off. */
    pace: Int?,
    onStop: () -> Unit,
) {
    BackHandler(onBack = onStop)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val total = HrvSession.STABILIZE_S + HrvSession.RECORD_S
    // Seconds since the finger went in, and how far along the reading is.
    val (title, now, progress, timer) = when (phase) {
        is HrvPhase.Stabilizing -> {
            val t = HrvSession.STABILIZE_S - phase.secondsLeft
            ReadingHeader("Stabilizzazione", t, t.toFloat() / total, clock(total - t))
        }
        is HrvPhase.Recording -> {
            val t = HrvSession.STABILIZE_S + phase.elapsed
            if (phase.elapsed >= phase.minSeconds) {
                ReadingHeader("Raccolgo altri battiti", t, phase.pairs.toFloat() / phase.targetPairs, "${phase.pairs}/${phase.targetPairs}")
            } else {
                ReadingHeader("Registrazione", t, t.toFloat() / total, clock(total - t))
            }
        }
        else -> ReadingHeader(sensorText(sensor), 0, 0f, "--:--")
    }
    val poor = (phase as? HrvPhase.Stabilizing)?.poorSignal ?: (phase as? HrvPhase.Recording)?.poorSignal ?: false
    val rmssd = (phase as? HrvPhase.Recording)?.rmssd

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Timer, null, tint = muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(timer, style = MaterialTheme.typography.headlineSmall)
        }
        Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
            when {
                phase is HrvPhase.Waiting -> Text(
                    if (sensor.connected) "Tieni il dito fermo nel sensore" else "Accendi il CorSense e chiudi l'app Elite HRV, se è aperta",
                    style = MaterialTheme.typography.bodyMedium, color = muted,
                )
                else -> SignalWarning(sensor, poor)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            BreathFlower(pace = pace, bpm = sensor.bpm, active = phase !is HrvPhase.Waiting)
        }
        Text(pace?.let { "Guida: $it atti al minuto" } ?: " ", style = MaterialTheme.typography.titleSmall, color = muted)

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
            LiveValue("♥", HeartRed, sensor.bpm?.toString() ?: "–", "HR", Modifier.weight(1f))
            LiveValue("♥", Amber, rmssd?.roundToInt()?.toString() ?: "–", "HRV", Modifier.weight(1f))
        }
        LiveHeartChart(liveHr, now.toFloat(), Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp))

        Box(Modifier.padding(top = 16.dp).size(104.dp), contentAlignment = Alignment.Center) {
            ProgressRing(
                progress = progress,
                color = HeartRed,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxSize(),
                stroke = 4.dp,
            )
            Surface(onClick = onStop, shape = CircleShape, color = HeartRed, modifier = Modifier.size(84.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("Stop", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            }
        }
    }
}

private data class ReadingHeader(val title: String, val now: Int, val progress: Float, val timer: String)

private fun clock(seconds: Int): String = "%02d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)

@Composable
private fun LiveValue(icon: String, color: Color, value: String, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(icon, color = color, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
    }
}

/**
 * Six overlapping petals. With the guide they open on the in-breath and close on the out-breath; without
 * it they beat softly with the heart, so there's something calm to look at without pacing the breath.
 */
@Composable
private fun BreathFlower(pace: Int?, bpm: Int?, active: Boolean) {
    val guide = pace != null
    val transition = rememberInfiniteTransition(label = "flower")
    val period = if (pace != null) 60_000 / pace else 60_000 / (bpm?.coerceIn(35, 150) ?: 60)
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing)), label = "t")
    val open = when {
        !active -> 0.55f
        guide -> FastOutSlowInEasing.transform(if (t < 0.5f) t * 2 else 2 - t * 2).let { 0.35f + 0.65f * it }
        // A quick swell at each beat, then rest.
        else -> 0.8f + 0.06f * if (t < 0.2f) kotlin.math.sin(Math.PI * t / 0.2f).toFloat() else 0f
    }
    val alpha = if (active) 0.32f else 0.15f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(240.dp)) {
            val r = size.minDimension / 2
            val petal = r * (0.3f + 0.25f * open)
            val offset = r * 0.45f * open
            for (i in 0 until 6) {
                val a = Math.PI / 3 * i - Math.PI / 2
                drawCircle(
                    HeartRed.copy(alpha = alpha),
                    petal,
                    Offset(center.x + offset * kotlin.math.cos(a).toFloat(), center.y + offset * kotlin.math.sin(a).toFloat()),
                )
            }
        }
        Text(
            when {
                !active -> " "
                guide -> if (t < 0.5f) "Inspira" else "Espira"
                else -> "Respira normalmente"
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** The last minute of heart rate, smoothed, with a cursor at [now] (seconds since the finger went in). */
@Composable
private fun LiveHeartChart(points: List<Pair<Float, Float>>, now: Float, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = muted)
    val window = 60f
    val start = (now - window + 10f).coerceAtLeast(0f)
    val shown = points.filter { it.first >= start - 2 }
    val lo = (shown.minOfOrNull { it.second } ?: 50f) - 3
    val hi = maxOf((shown.maxOfOrNull { it.second } ?: 70f) + 3, lo + 12)
    Canvas(modifier) {
        val axis = 24.dp.toPx()
        val h = size.height - axis
        fun x(s: Float) = (s - start) / window * size.width
        fun y(v: Float) = h * (1 - (v - lo) / (hi - lo))

        if (shown.size >= 2) {
            val path = Path()
            shown.forEachIndexed { i, (s, v) ->
                if (i == 0) path.moveTo(x(s), y(v))
                else {
                    val (ps, pv) = shown[i - 1]
                    path.quadraticTo(x(ps), y(pv), (x(ps) + x(s)) / 2, (y(pv) + y(v)) / 2)
                }
            }
            val last = shown.last()
            path.lineTo(x(last.first), y(last.second))
            val fill = Path().apply {
                addPath(path)
                lineTo(x(last.first), h)
                lineTo(x(shown.first().first), h)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(HeartRed.copy(alpha = 0.25f), Color.Transparent), 0f, h))
            drawPath(path, HeartRed, style = Stroke(2.dp.toPx()))
            drawCircle(HeartRed, 5.dp.toPx(), Offset(x(last.first), y(last.second)))
        }
        drawLine(muted.copy(alpha = 0.5f), Offset(x(now), 0f), Offset(x(now), h), 1.dp.toPx())

        // Time axis: a dot every 2 s, a tick and label every 10 s.
        var s = kotlin.math.ceil(start / 2).toInt() * 2
        while (s <= start + window) {
            val px = x(s.toFloat())
            if (s % 10 == 0) {
                drawLine(muted, Offset(px, h + 2.dp.toPx()), Offset(px, h + 8.dp.toPx()), 1.dp.toPx())
                val label = measurer.measure(if (s % 60 == 0 && s > 0) "${s / 60}m" else if (s >= 60) "${s / 60}m${s % 60}" else "${s}s", labelStyle)
                drawText(label, topLeft = Offset((px - label.size.width / 2).coerceIn(0f, size.width - label.size.width), h + 9.dp.toPx()))
            } else {
                drawCircle(muted.copy(alpha = 0.6f), 1.dp.toPx(), Offset(px, h + 5.dp.toPx()))
            }
            s += 2
        }
    }
}
