package com.andreagrossetti.training.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.util.Log
import com.andreagrossetti.training.ai.LocalAi
import com.andreagrossetti.training.ai.SpokenDurations
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Preset(
    val label: String,
    val system: String?,
    val prompt: String,
    /** Turns what the user typed into what Nano gets. */
    val prepare: (String) -> String = { it },
    /** The answer must be JSON: strip any ``` fence Nano adds around it. */
    val json: Boolean = false,
)

private val presets = listOf(
    Preset(
        "Programma",
        system = """
            Converti la descrizione di un allenamento in JSON. Rispondi SOLO con il JSON, senza testo né ```.
            Formato: {"name": string, "exercises": [{"name": string, "sets": int, "reps": int oppure "seconds": int, "rest": int, "notes": string}]}
            - "reps" per serie a ripetizioni, "seconds" per serie a tempo: mai entrambi.
            - "rest" è il recupero in secondi, copiato dal testo. Se il recupero non è indicato, ometti "rest": non inventarlo.
            - "notes" solo per indicazioni che non stanno negli altri campi (es. "per gamba", "per lato", "con manubri"); altrimenti omettilo.
            - Nomi degli esercizi in italiano, con l'iniziale maiuscola.
            Esempio:
            Testo: Spalle: 3 serie da 8 lento avanti con 120 secondi di recupero, 2 serie da 15 alzate laterali per braccio
            JSON: {"name": "Spalle", "exercises": [{"name": "Lento avanti", "sets": 3, "reps": 8, "rest": 120}, {"name": "Alzate laterali", "sets": 2, "reps": 15, "notes": "per braccio"}]}
        """.trimIndent(),
        prompt = "Allenamento gambe: 4 serie da 12 squat con un minuto e mezzo di recupero, " +
            "3 affondi da 10 per gamba, poi plank 3 volte 45 secondi con 30 secondi di pausa",
        prepare = { "Testo: ${SpokenDurations.normalize(it)}\nJSON:" },
        json = true,
    ),
    Preset(
        "Badge",
        system = """
            Sei il coach di un'app di allenamento. Inventa un badge per il traguardo indicato.
            Rispondi SOLO con JSON: {"title": string (max 4 parole), "text": string (una frase, max 15 parole), "icon": una tra "fire","trophy","bolt","mountain","star"}
            - Scrivi solo in italiano: niente parole inglesi come "Power", "Master" o "Boss".
            - Se ci sono più dati (record, costanza, numero di allenamenti), usali insieme nel testo.
            Esempio:
            Traguardo: 10 km di corsa per la prima volta. Terza uscita della settimana.
            JSON: {"title": "Muro dei dieci", "text": "Primi 10 km, e alla terza uscita della settimana: gambe d'acciaio!", "icon": "mountain"}
        """.trimIndent(),
        prompt = "Traguardo: record personale di piegamenti, 25 in una serie (prima 20). Quarto allenamento della settimana.",
        prepare = { "$it\nJSON:" },
        json = true,
    ),
    Preset(
        "Riepilogo",
        system = "Sei un coach amichevole. Scrivi in italiano un riepilogo settimanale di 3-4 frasi, " +
            "usando solo i dati forniti, senza inventare numeri.",
        prompt = """
            Settimana 15-21 settembre: 4 attività (obiettivo 4 giorni: raggiunto).
            Programmi: Giornata A x2, Giornata B x1, totale 58 serie, 2 h 10 min.
            Corsa: 1 uscita, 6,2 km, passo medio 5:48/km (settimana prima 6:05/km).
            Sforzo medio: Impegnativo. Record: Piegamenti 25 ripetizioni.
        """.trimIndent(),
    ),
)

private fun describe(e: Throwable): String {
    Log.w("LocalAi", "AI error", e)
    val code = (e as? GenAiException)?.errorCode?.let { " [codice $it]" }.orEmpty()
    return "${e.message}$code"
}

/**
 * The panel's state lives outside the composition: Settings is a LazyColumn, so scrolling the panel
 * off screen disposes it, and a rememberCoroutineScope would cancel a generation halfway through.
 */
private object AiTest {
    val ai = LocalAi()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var status by mutableStateOf<LocalAi.Status?>(null)
    var info by mutableStateOf("")
    var preset by mutableStateOf(presets[0])
    var prompt by mutableStateOf(presets[0].prompt)
    var output by mutableStateOf("")
    var busy by mutableStateOf(false)

    suspend fun refresh() {
        status = runCatching { ai.status() }.getOrElse { output = "Errore: ${describe(it)}"; LocalAi.Status.UNAVAILABLE }
        if (status == LocalAi.Status.AVAILABLE) info = runCatching { ai.info() }.getOrDefault("")
    }

    fun download() {
        busy = true
        scope.launch {
            output = "Richiesta inviata…"
            runCatching { ai.download().collect { bytes -> if (bytes > 0) output = "Scaricati ${bytes / 1_000_000} MB…" } }
                .onSuccess { output = "Download completato" }
                .onFailure { output = "Download fallito: ${describe(it)}" }
            refresh()
            busy = false
        }
    }

    fun generate() {
        busy = true
        val sent = preset.prepare(prompt)
        // Show what Nano actually got when preprocessing changed it.
        val header = if (sent != prompt) "Inviato:\n$sent\n\n" else ""
        output = header + "Genero…"
        scope.launch {
            val start = System.currentTimeMillis()
            output = header + runCatching { ai.generate(sent, preset.system).let { if (preset.json) LocalAi.stripCodeFence(it) else it } }
                .fold({ it }, { "Errore: ${describe(it)}" }) +
                "\n\n(${"%.1f".format((System.currentTimeMillis() - start) / 1000.0)} s)"
            busy = false
        }
    }
}

/** Settings panel to try Gemini Nano on this phone. */
@Composable
fun AiTestPanel() = with(AiTest) {
    LaunchedEffect(Unit) { if (status == null) refresh() }
    // AICore finishes big downloads in the background (Wi-Fi only): poll until it's done.
    LaunchedEffect(status) {
        while (status == LocalAi.Status.DOWNLOADING) {
            delay(5_000)
            refresh()
        }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Stato: " + when (status) {
                null -> "controllo…"
                LocalAi.Status.AVAILABLE -> "pronto"
                LocalAi.Status.DOWNLOADABLE -> "modello da scaricare"
                LocalAi.Status.DOWNLOADING -> "download in corso"
                LocalAi.Status.UNAVAILABLE -> "non disponibile su questo telefono"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        if (info.isNotEmpty()) {
            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (status == LocalAi.Status.DOWNLOADING) {
            Text(
                "Lo scarica il sistema in background, solo col Wi-Fi. Lo stato si aggiorna da solo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status == LocalAi.Status.DOWNLOADABLE || status == LocalAi.Status.DOWNLOADING) {
            OutlinedButton(enabled = !busy, onClick = ::download) {
                Text(if (status == LocalAi.Status.DOWNLOADING) "Riprendi download" else "Scarica il modello")
            }
        }
        if (status == LocalAi.Status.AVAILABLE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { p ->
                    FilterChip(selected = preset == p, onClick = { preset = p; prompt = p.prompt; output = "" }, label = { Text(p.label) })
                }
            }
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), minLines = 3)
            Button(enabled = !busy && prompt.isNotBlank(), onClick = ::generate) { Text("Genera") }
        }
        if (output.isNotEmpty()) {
            SelectionContainer {
                Text(output, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}
