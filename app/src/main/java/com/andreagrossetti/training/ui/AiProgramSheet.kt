package com.andreagrossetti.training.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.andreagrossetti.training.ai.ProgramGenerator
import com.andreagrossetti.training.data.Program
import kotlinx.coroutines.launch

/** Describe a workout by voice or text; Gemini Nano turns it into a draft for the editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProgramSheet(
    generator: ProgramGenerator,
    knownExercises: () -> List<String>,
    onDraft: (Program) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // While generating, don't let a stray swipe close the sheet and cancel the request.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy })

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) text = listOf(text.trim(), spoken.trim()).filter { it.isNotEmpty() }.joinToString(", ")
    }

    fun generate() {
        busy = true
        error = null
        scope.launch {
            runCatching { generator.generate(text, knownExercises()) }
                .onSuccess(onDraft)
                .onFailure {
                    Log.w("LocalAi", "Program generation failed", it)
                    error = "Non sono riuscito a creare il programma (${it.message}). Riprova o riformula."
                }
            busy = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Crea con l'AI", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Descrivi l'allenamento, per esempio: \"4 serie da 12 squat con un minuto di recupero, " +
                    "plank 3 volte 40 secondi\". Lo controlli nell'editor prima di salvarlo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppTextField(
                value = text,
                onValueChange = { text = it; error = null },
                label = "Allenamento",
                singleLine = false,
                minLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Descrivi l'allenamento")
                        try {
                            speech.launch(intent)
                        } catch (_: ActivityNotFoundException) {
                            error = "Riconoscimento vocale non disponibile: usa il microfono della tastiera."
                        }
                    },
                    modifier = Modifier.height(52.dp),
                ) {
                    Icon(Icons.Rounded.Mic, null)
                    Text("Detta", Modifier.padding(start = 6.dp))
                }
                Button(
                    enabled = !busy && text.isNotBlank(),
                    onClick = ::generate,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Creo il programma…", Modifier.padding(start = 10.dp))
                    } else {
                        Icon(Icons.Rounded.AutoAwesome, null)
                        Text("Crea", Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}
