package com.andreagrossetti.training.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.andreagrossetti.training.data.HrvMeasurement
import com.andreagrossetti.training.data.HrvRepository
import com.andreagrossetti.training.data.MAX_ARTIFACTS
import com.andreagrossetti.training.data.Readiness
import com.andreagrossetti.training.data.ReadinessResult
import com.andreagrossetti.training.data.dailyHrv
import com.andreagrossetti.training.data.date
import com.andreagrossetti.training.data.readiness
import com.andreagrossetti.training.hrv.HrvPhase
import com.andreagrossetti.training.hrv.HrvSession
import com.andreagrossetti.training.hrv.SensorStatus
import com.andreagrossetti.training.workout.RemoteButton
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
    Readiness.YELLOW -> "Recupero incompleto: alleggerisci, per esempio una serie in meno o recuperi più lunghi."
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
    return "Sto imparando i tuoi valori: ancora $left ${if (left == 1) "mattina" else "mattine"} e arriva il semaforo."
}

/** Home card: today's readiness, or a nudge to take the morning reading. */
@Composable
fun ReadinessCard(hrv: HrvRepository, plannedTitle: String?, onOpen: () -> Unit) {
    val measurements by hrv.measurements.collectAsStateWithLifecycle()
    val result = remember(measurements) { readiness(measurements) }
    val ready = result as? ReadinessResult.Ready
    val accent = ready?.readiness?.color() ?: MaterialTheme.colorScheme.secondary
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
                        Text(result.readiness.title(), style = MaterialTheme.typography.titleLarge)
                        Text(
                            plannedTitle?.let { "$it: ${result.readiness.planHint()}" } ?: result.readiness.advice(),
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
fun HrvScreen(session: HrvSession, hrv: HrvRepository, onClose: () -> Unit) {
    val phase by session.phase.collectAsStateWithLifecycle()
    val sensor by session.sensorStatus.collectAsStateWithLifecycle()
    val measurements by hrv.measurements.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var toDelete by remember { mutableStateOf<HrvMeasurement?>(null) }
    val active = phase !is HrvPhase.Idle && phase !is HrvPhase.Done
    val close = {
        if (phase !is HrvPhase.Idle) session.cancel()
        onClose()
    }
    BackHandler(onBack = close)
    if (active) KeepScreenOn()

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) session.start()
        else scope.launch { snackbar.showSnackbar("Serve il permesso Bluetooth per il sensore") }
    }
    val start = {
        if (RemoteButton.hasPermissions(context)) session.start() else permission.launch(RemoteButton.permissions)
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
                        onStart = start,
                        onCancel = session::cancel,
                        onSaveAnyway = session::saveAnyway,
                    )
                }
            }
            if (!active) {
                val result = readiness(measurements)
                result?.let { item { Panel(Modifier.fillMaxWidth()) { ReadinessSummary(it) } } }
                val daily = dailyHrv(measurements)
                if (daily.size >= 2) item { Panel(Modifier.fillMaxWidth()) { TrendChart(daily, result as? ReadinessResult.Ready) } }
                if (measurements.isNotEmpty()) {
                    item {
                        Text(
                            "MISURE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                        )
                    }
                    items(measurements.take(30), key = { it.id }) { m -> MeasurementRow(m, onDelete = { toDelete = m }) }
                }
            }
        }
    }

    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminare la misura?") },
            text = { Text(historyFormat.format(Instant.ofEpochMilli(m.timestamp).atZone(ZoneId.systemDefault())) + " · ${m.rmssd.roundToInt()} ms") },
            confirmButton = { TextButton(onClick = { hrv.delete(m.id); toDelete = null }) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun MeasurePanel(
    phase: HrvPhase,
    sensor: SensorStatus,
    hasToday: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onSaveAnyway: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (phase) {
            HrvPhase.Idle -> {
                Text(
                    "Appena sveglio, disteso a letto. Metti il dito nel sensore, respira normalmente e lascia il telefono: " +
                        "dura circa 2 minuti e un suono ti avvisa alla fine.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (hasToday) "Misura di nuovo" else "Inizia misura", style = MaterialTheme.typography.titleMedium)
                }
            }
            HrvPhase.Waiting -> {
                PhaseRing(null, "…", sensorText(sensor))
                Text(
                    if (sensor.connected) "Tieni il dito fermo nel sensore" else "Accendi il CorSense e chiudi l'app Elite HRV, se è aperta",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onCancel) { Text("Annulla") }
            }
            is HrvPhase.Stabilizing -> {
                val done = HrvSession.STABILIZE_S - phase.secondsLeft
                PhaseRing(done.toFloat() / HrvSession.STABILIZE_S, "${phase.secondsLeft}", "Stabilizzazione")
                LiveLine(sensor, pairs = null, rmssd = null)
                SignalWarning(sensor, phase.poorSignal)
                TextButton(onClick = onCancel) { Text("Annulla") }
            }
            is HrvPhase.Recording -> {
                val extended = phase.elapsed >= phase.minSeconds
                val progress = if (extended) phase.pairs.toFloat() / phase.targetPairs else phase.elapsed.toFloat() / phase.minSeconds
                val left = phase.minSeconds - phase.elapsed
                PhaseRing(
                    progress = progress,
                    center = if (extended) "${phase.pairs}/${phase.targetPairs}" else "%d:%02d".format(left / 60, left % 60),
                    label = if (extended) "Raccolgo altri battiti" else "Registrazione",
                )
                LiveLine(sensor, phase.pairs, phase.rmssd)
                SignalWarning(sensor, phase.poorSignal)
                if (extended) {
                    Text(
                        "Il segnale si è interrotto: continuo finché ho abbastanza battiti buoni (al massimo ${phase.maxSeconds / 60} minuti).",
                        style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.Center,
                    )
                }
                TextButton(onClick = onCancel) { Text("Annulla") }
            }
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
                            "RMSSD · ${m.meanHr.roundToInt()} bpm · ${m.beats} battiti",
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
private fun PhaseRing(progress: Float?, center: String, label: String) {
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        ProgressRing(
            progress = progress ?: 0f,
            color = MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxSize(),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, style = MaterialTheme.typography.displayMedium)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LiveLine(sensor: SensorStatus, pairs: Int?, rmssd: Double?) {
    val parts = listOfNotNull(
        sensor.bpm?.let { "♥ $it bpm" },
        pairs?.let { "$it battiti buoni" },
        rmssd?.let { "RMSSD ${it.roundToInt()} ms" },
        sensor.battery?.let { "batteria $it%" },
    )
    Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SignalWarning(sensor: SensorStatus, poor: Boolean) {
    when {
        sensor.contact == false -> Text("Dito non rilevato", color = Stop, style = MaterialTheme.typography.titleSmall)
        poor -> Text("Segnale scarso: tieni fermo il dito", color = Amber, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ReadinessSummary(result: ReadinessResult) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        when (result) {
            is ReadinessResult.Calibrating -> {
                IconBadge(Icons.Rounded.MonitorHeart, MaterialTheme.colorScheme.secondary)
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Calibrazione ${result.days}/${result.needed}", style = MaterialTheme.typography.titleMedium)
                    Text(calibratingText(result), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is ReadinessResult.Ready -> {
                val color = result.readiness.color()
                Box(Modifier.size(44.dp).background(color, CircleShape))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(result.readiness.title(), style = MaterialTheme.typography.titleLarge)
                    val hr = if (result.highHr) " Il battito a riposo è più alto del solito." else ""
                    Text(result.readiness.advice() + hr, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Oggi ${result.today.rmssd.roundToInt()} ms · normale ${result.low.roundToInt()}–${result.high.roundToInt()} ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Morning RMSSD over the last [BASELINE_DAYS] days, with the normal range as a band. */
@Composable
private fun TrendChart(daily: List<HrvMeasurement>, ready: ReadinessResult.Ready?) {
    val today = LocalDate.now()
    val points = daily.filter { ChronoUnit.DAYS.between(it.date(), today) in 0..BASELINE_DAYS.toLong() }
    val line = MaterialTheme.colorScheme.secondary
    val band = Go.copy(alpha = 0.14f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val values = points.map { it.rmssd } + listOfNotNull(ready?.low, ready?.high)
    val min = (values.minOrNull() ?: 0.0) * 0.9
    val max = (values.maxOrNull() ?: 1.0) * 1.1
    Column(Modifier.padding(16.dp)) {
        Text("Ultimi $BASELINE_DAYS giorni", style = MaterialTheme.typography.titleSmall)
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
private fun MeasurementRow(m: HrvMeasurement, onDelete: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    historyFormat.format(Instant.ofEpochMilli(m.timestamp).atZone(ZoneId.systemDefault()))
                        .replaceFirstChar { it.titlecase(Locale.ITALIAN) },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "${m.meanHr.roundToInt()} bpm · ${m.beats} battiti" + if (!m.reliable) " · poco affidabile" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (m.reliable) muted else Amber,
                )
            }
            Text("${m.rmssd.roundToInt()} ms", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Elimina", tint = muted) }
        }
    }
}
