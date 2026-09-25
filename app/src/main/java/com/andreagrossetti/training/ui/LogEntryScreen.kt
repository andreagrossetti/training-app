package com.andreagrossetti.training.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.andreagrossetti.training.data.LogEntry
import com.andreagrossetti.training.data.LogKind
import com.andreagrossetti.training.data.Program
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val DAY_MS = 86_400_000L
private val dateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ITALIAN)

/** Manual log entry: "today I did program X", a run, or anything else. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogEntryScreen(programs: List<Program>, onSave: (LogEntry) -> Unit, onClose: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(if (programs.isEmpty()) LogKind.RUN else LogKind.PROGRAM) }
    var programName by rememberSaveable { mutableStateOf(programs.firstOrNull()?.name ?: "") }
    var otherTitle by rememberSaveable { mutableStateOf("") }
    var epochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var minutes by rememberSaveable { mutableIntStateOf(0) }
    var distance by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    val title = when (kind) {
        LogKind.PROGRAM -> programName
        LogKind.RUN -> "Corsa"
        LogKind.OTHER -> otherTitle.trim()
    }
    val km = distance.replace(',', '.').toDoubleOrNull()

    fun save() {
        val date = LocalDate.ofEpochDay(epochDay)
        val time = if (date == LocalDate.now()) LocalTime.now() else LocalTime.NOON
        onSave(
            LogEntry(
                timestamp = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                kind = kind,
                title = title,
                durationSeconds = minutes.takeIf { it > 0 }?.times(60),
                distanceKm = km?.takeIf { it > 0 && kind != LogKind.PROGRAM },
                notes = notes.trim(),
            )
        )
    }

    Scaffold(
        topBar = { FormTopBar("Registra attività", onClose, ::save, saveEnabled = title.isNotBlank()) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section("Tipo") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val kinds = listOf(LogKind.PROGRAM to "Programma", LogKind.RUN to "Corsa", LogKind.OTHER to "Altro")
                    kinds.filter { it.first != LogKind.PROGRAM || programs.isNotEmpty() }.forEach { (k, label) ->
                        KindOption(k, label, selected = kind == k, onClick = { kind = k }, Modifier.weight(1f))
                    }
                }
            }

            when (kind) {
                LogKind.PROGRAM -> Section("Quale programma?") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        programs.forEach { p ->
                            FilterChip(programName == p.name, { programName = p.name }, { Text(p.name) })
                        }
                    }
                }
                LogKind.OTHER -> AppTextField(
                    value = otherTitle,
                    onValueChange = { otherTitle = it },
                    label = "Attività (es. Nuoto, Bici)",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                LogKind.RUN -> Unit
            }

            Section("Quando") {
                Panel(Modifier.fillMaxWidth(), onClick = { pickingDate = true }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.CalendarMonth, MaterialTheme.colorScheme.primary, size = 40.dp)
                        Text(
                            dateFormat.format(LocalDate.ofEpochDay(epochDay)).replaceFirstChar { it.titlecase(Locale.ITALIAN) },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(start = 14.dp),
                        )
                        Text("Cambia", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Section("Dettagli") {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stepper("Durata", minutes, { minutes = it }, step = 5, suffix = "min")
                        if (kind != LogKind.PROGRAM) {
                            AppTextField(
                                value = distance,
                                onValueChange = { input ->
                                    distance = input.filter { it.isDigit() || it == ',' || it == '.' }.take(6)
                                },
                                label = "Distanza (km)",
                                isError = distance.isNotEmpty() && km == null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            AppTextField(
                value = notes,
                onValueChange = { notes = it },
                label = "Note",
                singleLine = false,
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (pickingDate) {
        val today = LocalDate.now().toEpochDay()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = epochDay * DAY_MS,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / DAY_MS <= today
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { epochDay = it / DAY_MS }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Annulla") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title.uppercase(Locale.ITALIAN),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun KindOption(kind: LogKind, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val (icon, tint) = kindStyle(kind)
    val border by animateColorAsState(if (selected) tint else Color.Transparent, label = "border")
    Panel(modifier.border(2.dp, border, MaterialTheme.shapes.large), onClick = onClick) {
        Column(
            Modifier.padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, tint = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
