package com.andreagrossetti.training.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import com.andreagrossetti.training.data.LogEntry
import com.andreagrossetti.training.data.Place
import com.andreagrossetti.training.data.Program
import com.andreagrossetti.training.data.effortLabels
import com.andreagrossetti.training.data.steps

/** Big title shown at the top of a tab, with an optional overline and actions. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            overline?.let {
                Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.displaySmall)
        }
        actions()
    }
}

/** Top bar for full-screen forms: back arrow, title, "Salva" pill. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormTopBar(title: String, onClose: () -> Unit, onSave: () -> Unit, saveEnabled: Boolean) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
        },
        actions = {
            Button(onClick = onSave, enabled = saveEnabled, modifier = Modifier.padding(end = 8.dp)) { Text("Salva") }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    )
}

/** Icon on a soft tinted rounded square. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).background(tint.copy(alpha = 0.16f), RoundedCornerShape(size * 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

/** Small rounded label with an optional leading icon. */
@Composable
fun InfoPill(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Rounded surface used for list items and form sections. */
@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.large
    val color = MaterialTheme.colorScheme.surfaceContainer
    if (onClick == null) {
        Surface(modifier = modifier, shape = shape, color = color, content = content)
    } else {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, content = content)
    }
}

/** Circular progress with rounded ends; [progress] is 0..1, drawn clockwise from the top. */
@Composable
fun ProgressRing(progress: Float, color: Color, trackColor: Color, modifier: Modifier = Modifier, stroke: Dp = 14.dp) {
    Canvas(modifier) {
        val width = stroke.toPx()
        val inset = width / 2
        val arcSize = Size(size.width - width, size.height - width)
        val topLeft = Offset(inset, inset)
        drawArc(trackColor, 0f, 360f, false, topLeft, arcSize, style = Stroke(width))
        if (progress > 0f) {
            drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, topLeft, arcSize, style = Stroke(width, cap = StrokeCap.Round))
        }
    }
}

/** A labelled "− value +" control. The value can also be typed directly. */
@Composable
fun Stepper(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    min: Int = 0,
    suffix: String? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        val buttonColors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        FilledTonalIconButton(
            onClick = { onChange((value - step).coerceAtLeast(min)) },
            enabled = value > min,
            colors = buttonColors,
            modifier = Modifier.size(40.dp),
        ) { Icon(Icons.Rounded.Remove, "Meno") }
        Row(Modifier.width(76.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                // Blank rather than an invalid 0, so the field can be cleared while typing.
                value = if (value > 0 || min == 0) value.toString() else "",
                onValueChange = { input -> onChange(input.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.width(if (suffix == null) 64.dp else 48.dp),
            )
            suffix?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }
        FilledTonalIconButton(
            onClick = { onChange(value + step) },
            colors = buttonColors,
            modifier = Modifier.size(40.dp),
        ) { Icon(Icons.Rounded.Add, "Più") }
    }
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1) },
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        keyboardOptions = keyboardOptions,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier,
    )
}

/** Thin rounded bar split in equal segments, each filled by its own 0..1 fraction. */
@Composable
fun SegmentedProgress(fractions: List<Float>, color: Color, trackColor: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        fractions.forEach { fraction ->
            Box(Modifier.weight(1f).fillMaxWidth().height(6.dp).background(trackColor, CircleShape)) {
                if (fraction > 0f) {
                    Box(
                        Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(color, CircleShape)
                    )
                }
            }
        }
    }
}

/** Rough duration of a program: work + rest, ~3 s per repetition. */
fun Program.estimatedMinutes(): Int {
    val seconds = steps().sumOf { step ->
        val ex = exercises[step.exerciseIndex]
        (if (ex.timed) ex.amount else ex.amount * 3) + step.restAfter
    }
    return ((seconds + 59) / 60).coerceAtLeast(1)
}

/** Five-step perceived effort selector; tapping the selected value clears it. */
@Composable
fun EffortPicker(
    effort: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    idle: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    onColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { level ->
                val selected = effort == level
                Surface(
                    onClick = { onChange(if (selected) null else level) },
                    shape = CircleShape,
                    color = if (effort != null && level <= effort) color else idle,
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "$level",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (effort != null && level <= effort) onColor else LocalContentColor.current,
                        )
                    }
                }
            }
        }
        Text(
            effort?.let { effortLabels[it - 1] } ?: "Com'è andata?",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Bottom sheet to edit the effort and notes of a diary entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteSheet(
    entry: LogEntry,
    onSave: (effort: Int?, notes: String) -> Unit,
    onDismiss: () -> Unit,
    /** Applied right away; null hides the place row. */
    onPlace: ((Place?) -> Unit)? = null,
) {
    var effort by remember(entry.id) { mutableStateOf(entry.effort) }
    var notes by remember(entry.id) { mutableStateOf(entry.notes) }
    var place by remember(entry.id) { mutableStateOf(entry.place) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(entry.title, style = MaterialTheme.typography.headlineSmall)
            EffortPicker(effort, { effort = it })
            AppTextField(
                value = notes,
                onValueChange = { notes = it },
                label = "Note sulla sessione",
                singleLine = false,
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            if (onPlace != null) {
                PlaceButton(place, onChange = { place = it; onPlace(it) })
            }
            Button(onClick = { onSave(effort, notes.trim()) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Salva")
            }
        }
    }
}

@Composable
fun SettingsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Rounded.Settings, "Impostazioni") }
}

/** Labelled progress bar towards a goal. */
@Composable
fun GoalBar(label: String, value: Float, goal: Float, valueText: String, modifier: Modifier = Modifier) {
    val reached = value >= goal
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (reached) "$valueText ✓" else valueText,
                style = MaterialTheme.typography.titleSmall,
                color = if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            Modifier.padding(top = 8.dp).fillMaxWidth().height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
        ) {
            Box(
                Modifier.fillMaxWidth((value / goal).coerceIn(0f, 1f)).height(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

/**
 * GitHub-style calendar of the last year: one column per week (Monday on top), each day
 * colored by how many activities were logged. Scrolled to today.
 */
@Composable
fun YearHeatmap(counts: Map<LocalDate, Int>, today: LocalDate) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val firstMonday = today.with(DayOfWeek.MONDAY).minusWeeks(52)
    val weeks = (0..52).map { w -> firstMonday.plusWeeks(w.toLong()) }
    val active = counts.keys.count { !it.isBefore(firstMonday) }
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val accent = MaterialTheme.colorScheme.primary
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Row(Modifier.padding(horizontal = 16.dp)) {
                Text("ULTIMO ANNO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("$active giorni attivi", style = MaterialTheme.typography.labelMedium)
            }
            Row(
                Modifier.padding(top = 12.dp).horizontalScroll(scroll).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                weeks.forEach { monday ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        // Month label on the first week of each month.
                        Text(
                            if (monday.dayOfMonth <= 7) monday.month.getDisplayName(DateTextStyle.SHORT, Locale.ITALIAN) else "",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.width(12.dp).height(14.dp),
                        )
                        (0L..6L).forEach { d ->
                            val day = monday.plusDays(d)
                            val n = counts[day] ?: 0
                            Box(
                                Modifier.size(12.dp).background(
                                    when {
                                        day.isAfter(today) -> Color.Transparent
                                        n == 0 -> empty
                                        n == 1 -> accent.copy(alpha = 0.55f)
                                        else -> accent
                                    },
                                    RoundedCornerShape(3.dp),
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
