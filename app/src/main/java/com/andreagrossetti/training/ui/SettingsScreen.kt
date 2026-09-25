package com.andreagrossetti.training.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andreagrossetti.training.TrainingApp
import androidx.health.connect.client.PermissionController
import androidx.activity.result.IntentSenderRequest
import com.andreagrossetti.training.drive.DriveConsentNeeded
import com.andreagrossetti.training.health.HealthSync
import com.google.android.gms.auth.api.identity.Identity
import com.andreagrossetti.training.workout.RemoteButton
import com.andreagrossetti.training.data.KmCue
import com.andreagrossetti.training.data.PLAN_RUN
import com.andreagrossetti.training.data.Settings
import com.andreagrossetti.training.data.exportBackup
import com.andreagrossetti.training.data.importBackup
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: TrainingApp, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val programs by app.repository.programs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pickingTime by remember { mutableStateOf(false) }
    var restoreText by remember { mutableStateOf<String?>(null) }
    fun update(transform: (Settings) -> Settings) = app.settings.update(transform)
    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use {
                it.write(exportBackup(app.repository, app.log, app.settings, app.hrv))
            }
        }.onSuccess { message("Backup salvato") }.onFailure { message("Backup fallito: ${it.message}") }
    }
    val remotePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) update { it.copy(remote = true) }
        else message("Serve il permesso Bluetooth per il telecomando")
    }
    val healthStatus by app.health.status.collectAsStateWithLifecycle()
    var confirmHealthRemoval by remember { mutableStateOf(false) }
    val healthPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(HealthSync.PERMISSIONS)) update { it.copy(healthConnect = true) }
        else message("Servono tutti i permessi di Health Connect")
    }
    val driveStatus by app.drive.status.collectAsStateWithLifecycle()
    var uploading by remember { mutableStateOf(false) }
    fun driveUpload() = scope.launch {
        uploading = true
        runCatching { app.drive.upload(app) }
            .onSuccess { message("Backup caricato su Drive") }
            .onFailure { if (it !is DriveConsentNeeded) message("Caricamento fallito: ${it.message}") }
        uploading = false
    }
    val driveConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val granted = runCatching {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data).accessToken != null
        }.getOrDefault(false)
        if (granted) {
            update { it.copy(driveBackup = true) }
            driveUpload()
        } else {
            message("Accesso a Drive non concesso")
        }
    }
    // Enabling (or a failed upload) goes through Google's consent screen first when needed.
    fun enableDrive() = scope.launch {
        runCatching { app.drive.authorize() }
            .onSuccess { auth ->
                if (auth.hasResolution()) {
                    driveConsent.launch(IntentSenderRequest.Builder(auth.pendingIntent!!.intentSender).build())
                } else {
                    update { it.copy(driveBackup = true) }
                    driveUpload()
                }
            }
            .onFailure { message("Google Drive non disponibile: ${it.message}") }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
            .onSuccess { restoreText = it }
            .onFailure { message("Lettura fallita: ${it.message}") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Impostazioni", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") } },
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
            item { SectionTitle("Audio") }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Avviso a ogni km durante la corsa", style = MaterialTheme.typography.titleSmall)
                        val options = listOf(KmCue.OFF to "Nessuno", KmCue.BEEP to "Beep", KmCue.VOICE to "Voce")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            options.forEachIndexed { i, (cue, label) ->
                                SegmentedButton(
                                    selected = settings.kmCue == cue,
                                    onClick = { update { it.copy(kmCue = cue) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                ) { Text(label) }
                            }
                        }
                        Text(
                            when (settings.kmCue) {
                                KmCue.OFF -> "Nessun suono né vibrazione. Le ripetute guidate usano comunque i beep."
                                KmCue.BEEP -> "Un beep e una vibrazione a ogni km."
                                KmCue.VOICE -> "La voce dice km e passo, abbassando la musica solo mentre parla. " +
                                    "Guida anche le ripetute (\"Recupero\", \"Vai!\")."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SwitchRow(
                            title = "Voce durante gli allenamenti",
                            subtitle = "Annuncia il prossimo esercizio e le ripetizioni",
                            checked = settings.workoutVoice,
                            onChange = { on -> update { it.copy(workoutVoice = on) } },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SwitchRow(
                            title = "Volume per \"serie fatta\"",
                            subtitle = "Durante una serie a ripetizioni, volume su o giù (anche dalle cuffie) la segna come fatta, senza cambiare il volume",
                            checked = settings.volumeDone,
                            onChange = { on -> update { it.copy(volumeDone = on) } },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SwitchRow(
                            title = "Telecomando Bluetooth",
                            subtitle = "Pulsante fai-da-te: clic per iniziare, serie fatta o saltare il recupero; " +
                                "pressione lunga per la pausa. Si collega da solo durante gli allenamenti",
                            checked = settings.remote,
                            onChange = { on ->
                                when {
                                    !on -> update { it.copy(remote = false) }
                                    RemoteButton.hasPermissions(context) -> update { it.copy(remote = true) }
                                    else -> remotePermission.launch(RemoteButton.permissions)
                                }
                            },
                        )
                        TextButton(onClick = { app.voice.speak("Un chilometro. Passo 6 e 15") }, contentPadding = PaddingValues(0.dp)) {
                            Text("Prova la voce")
                        }
                    }
                }
            }

            item { SectionTitle("Obiettivi") }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stepper(
                            "Giorni attivi a settimana",
                            settings.weeklyGoalDays ?: 0,
                            { v -> update { it.copy(weeklyGoalDays = v.coerceAtMost(7).takeIf { d -> d > 0 }) } },
                        )
                        Stepper(
                            "Km di corsa al mese",
                            settings.monthlyGoalKm ?: 0,
                            { v -> update { it.copy(monthlyGoalKm = v.takeIf { k -> k > 0 }) } },
                            step = 5,
                        )
                        Text("0 = nessun obiettivo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item { SectionTitle("Piano settimanale") }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            PlanRow(
                                day = day,
                                value = settings.plan[day.value],
                                options = listOf(null to "Riposo", PLAN_RUN to "Corsa") + programs.map { it.id to it.name },
                                onChange = { v ->
                                    update { s -> s.copy(plan = if (v == null) s.plan - day.value else s.plan + (day.value to v)) }
                                },
                            )
                        }
                    }
                }
            }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        SwitchRow(
                            title = "Promemoria",
                            subtitle = settings.reminderMinutes?.let { "Ogni giorno alle %02d:%02d, se c'è qualcosa in programma".format(it / 60, it % 60) }
                                ?: "Una notifica nei giorni in cui hai qualcosa in programma",
                            checked = settings.reminderMinutes != null,
                            onChange = { on -> update { it.copy(reminderMinutes = if (on) 18 * 60 else null) } },
                        )
                        if (settings.reminderMinutes != null) {
                            TextButton(onClick = { pickingTime = true }, contentPadding = PaddingValues(0.dp)) { Text("Cambia orario") }
                        }
                    }
                }
            }

            item { SectionTitle("Salute") }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwitchRow(
                            title = "Health Connect e Samsung Health",
                            subtitle = if (app.health.available) {
                                "Allenamenti, corse con percorso, HRV e battito a riposo. Samsung Health li legge da Health Connect"
                            } else {
                                "Health Connect non è disponibile su questo telefono"
                            },
                            checked = settings.healthConnect,
                            onChange = { on ->
                                when {
                                    !on -> update { it.copy(healthConnect = false) }
                                    !app.health.available -> message("Health Connect non è disponibile")
                                    else -> scope.launch {
                                        if (app.health.hasPermissions()) update { it.copy(healthConnect = true) }
                                        else healthPermission.launch(HealthSync.PERMISSIONS)
                                    }
                                }
                            },
                        )
                        val status = healthStatus
                        when {
                            status.error != null -> Text(
                                "Ultimo invio non riuscito: ${status.error}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            status.lastSync != null -> Text(
                                "Aggiornato alle %tR · %d dati".format(status.lastSync, status.records),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (settings.healthConnect || status.records > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (settings.healthConnect) {
                                    TextButton(
                                        onClick = { scope.launch { app.health.sync(app.log.entries.value, app.hrv.measurements.value) } },
                                        contentPadding = PaddingValues(0.dp),
                                    ) { Text("Invia ora") }
                                }
                                if (status.records > 0) {
                                    TextButton(onClick = { confirmHealthRemoval = true }) { Text("Rimuovi da Health Connect") }
                                }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("AI sul telefono (prova)") }
            item { Panel(Modifier.fillMaxWidth()) { AiTestPanel() } }

            item { SectionTitle("Backup") }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Un unico file con programmi, diario, percorsi, misure HRV e impostazioni. Salvalo su Drive o sul PC.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { exportLauncher.launch("allenamento-backup-${LocalDate.now()}.json") },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.FileUpload, null)
                                Text("Esporta", Modifier.padding(start = 6.dp))
                            }
                            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.FileDownload, null)
                                Text("Ripristina", Modifier.padding(start = 6.dp))
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SwitchRow(
                            title = "Backup settimanale su Drive",
                            subtitle = "Una volta a settimana, col telefono in carica e sul Wi-Fi, nella cartella \"Allenamento\" " +
                                "del tuo Drive. Tiene gli ultimi 8. L'app vede solo i file che crea lei",
                            checked = settings.driveBackup,
                            onChange = { on -> if (on) enableDrive() else update { it.copy(driveBackup = false) } },
                        )
                        val drive = driveStatus
                        when {
                            drive.error != null -> Text(
                                "Ultimo caricamento non riuscito: ${drive.error}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            drive.lastUpload != null -> Text(
                                "Ultimo backup su Drive: %1\$te %1\$tb alle %1\$tR".format(Locale.ITALIAN, drive.lastUpload),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (settings.driveBackup) {
                            TextButton(onClick = { enableDrive() }, enabled = !uploading, contentPadding = PaddingValues(0.dp)) {
                                Text(if (uploading) "Caricamento…" else "Carica ora")
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickingTime) {
        val current = settings.reminderMinutes ?: (18 * 60)
        val state = rememberTimePickerState(current / 60, current % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    update { it.copy(reminderMinutes = state.hour * 60 + state.minute) }
                    pickingTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Annulla") } },
            text = { TimePicker(state) },
        )
    }

    if (confirmHealthRemoval) {
        AlertDialog(
            onDismissRequest = { confirmHealthRemoval = false },
            title = { Text("Rimuovere i dati da Health Connect?") },
            text = {
                Text(
                    "Vengono cancellati da Health Connect (e quindi da Samsung Health) gli allenamenti, le corse e le misure HRV " +
                        "inviati da questa app. Nell'app restano tutti. L'invio automatico viene disattivato."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmHealthRemoval = false
                    update { it.copy(healthConnect = false) }
                    scope.launch {
                        app.health.removeAll()
                        message("Dati rimossi da Health Connect")
                    }
                }) { Text("Rimuovi") }
            },
            dismissButton = { TextButton(onClick = { confirmHealthRemoval = false }) { Text("Annulla") } },
        )
    }

    restoreText?.let { text ->
        AlertDialog(
            onDismissRequest = { restoreText = null },
            title = { Text("Ripristinare il backup?") },
            text = { Text("Programmi, diario, percorsi, misure HRV e impostazioni attuali verranno sostituiti da quelli del file.") },
            confirmButton = {
                TextButton(onClick = {
                    restoreText = null
                    runCatching { importBackup(text, app.repository, app.log, app.settings, app.hrv) }
                        .onSuccess { message("Ripristinate $it attività") }
                        .onFailure { message("File non valido: ${it.message}") }
                }) { Text("Ripristina") }
            },
            dismissButton = { TextButton(onClick = { restoreText = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(Locale.ITALIAN),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PlanRow(day: DayOfWeek, value: String?, options: List<Pair<String?, String>>, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = options.find { it.first == value }?.second ?: "Riposo"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            day.getDisplayName(TextStyle.FULL, Locale.ITALIAN).replaceFirstChar { it.titlecase(Locale.ITALIAN) },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(110.dp),
        )
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    label,
                    color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Icon(Icons.Rounded.ExpandMore, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onChange(id) })
                }
            }
        }
    }
}
