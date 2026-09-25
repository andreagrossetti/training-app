package com.andreagrossetti.training.ai

import android.util.Log
import com.andreagrossetti.training.data.Exercise
import com.andreagrossetti.training.data.LogEntry
import com.andreagrossetti.training.data.Program
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Turns a spoken or typed description of a workout into a draft [Program], with Gemini Nano.
 * The draft always goes through the editor before being saved.
 */
class ProgramGenerator(private val ai: LocalAi) {

    /** [knownExercises] are the names already in use, so the history stays linked to them. */
    suspend fun generate(description: String, knownExercises: List<String>): Program {
        val raw = ai.generate(prompt(description), system(knownExercises), temperature = 0.1f)
        Log.d("LocalAi", "Program draft: $raw")
        return parse(raw, knownExercises)
    }

    companion object {
        /** Keeps the prompt well within Nano's input limit. */
        private const val MAX_KNOWN = 80

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Common names for the same exercise, Italian and English: Nano doesn't match these reliably. */
        private val synonyms = listOf(
            setOf("push-up", "push up", "pushup", "piegamenti", "flessioni"),
            setOf("pull-up", "pull up", "pullup", "trazioni"),
            setOf("squat", "squats", "accosciate"),
            setOf("affondi", "lunges", "lunge"),
            setOf("dip", "dips"),
            setOf("burpee", "burpees"),
            setOf("crunch", "addominali"),
            setOf("jumping jack", "jumping jacks"),
            setOf("mountain climber", "mountain climbers", "scalatore"),
        ).flatMap { group -> group.map { it to group } }.toMap()

        /** The user's own spelling of [name], if they already have that exercise under this or a synonym. */
        private fun matchKnown(name: String, known: Map<String, String>): String? {
            val key = name.lowercase().trim()
            known[key]?.let { return it }
            return synonyms[key]?.firstNotNullOfOrNull { known[it] }
        }

        @Serializable
        private data class Draft(
            val name: String? = null,
            val rounds: Int? = null,
            val roundRest: Int? = null,
            val exercises: List<DraftExercise> = emptyList(),
        )

        @Serializable
        private data class DraftExercise(
            val name: String = "",
            val sets: Int? = null,
            /** Nano sometimes writes "2 volte" as rounds on the exercise: read it as sets. */
            val rounds: Int? = null,
            val reps: Int? = null,
            val seconds: Int? = null,
            val rest: Int? = null,
            val notes: String? = null,
        )

        fun prompt(description: String) = "Testo: ${SpokenDurations.normalize(description.trim())}\nJSON:"

        fun system(knownExercises: List<String>): String {
            val rules = """
                Converti la descrizione di un allenamento in JSON. Rispondi SOLO con il JSON, senza testo né ```.
                Formato: {"name": string, "rounds": int, "roundRest": int, "exercises": [{"name": string, "sets": int, "reps": int oppure "seconds": int, "rest": int, "notes": string}]}
                - "sets" è il numero di serie: "3 serie", "3 volte" e "3x" significano tutti "sets": 3.
                - "reps" per serie a ripetizioni, "seconds" per serie a tempo: mai entrambi.
                - "rest" è il recupero in secondi, solo se il testo lo dice con "recupero", "pausa" o "riposo". Altrimenti ometti "rest": non inventarlo e non copiare la durata dell'esercizio.
                - "notes" solo per indicazioni che non stanno negli altri campi (es. "per gamba", "per lato", "con manubri"); altrimenti omettilo.
                - Solo per un circuito (giri che ripetono tutti gli esercizi): "rounds" è il numero di giri, "roundRest" il recupero tra i giri, e "sets" si omette. Altrimenti ometti "rounds" e "roundRest".
                - Se il testo non dà un nome all'allenamento, inventane uno breve (max 3 parole).
                - Nomi degli esercizi in italiano, con l'iniziale maiuscola.
            """.trimIndent()
            val known = knownExercises.take(MAX_KNOWN)
            val names = if (known.isEmpty()) "" else
                "\n- Esercizi che l'utente usa già: ${known.joinToString(", ")}. Se il testo parla di uno di questi, " +
                    "anche al plurale, con un sinonimo o tradotto (es. piegamenti = Push-up, trazioni = Pull-up), " +
                    "usa ESATTAMENTE quel nome."
            val examples = """
                Esempi:
                Testo: Spalle: 3 serie da 8 lento avanti con 120 secondi di recupero, 2 serie da 15 alzate laterali per braccio, wall sit 2 volte 45 secondi
                JSON: {"name": "Spalle", "exercises": [{"name": "Lento avanti", "sets": 3, "reps": 8, "rest": 120}, {"name": "Alzate laterali", "sets": 2, "reps": 15, "notes": "per braccio"}, {"name": "Wall sit", "sets": 2, "seconds": 45}]}
                Testo: circuito da 4 giri con 60 secondi tra i giri: 20 jumping jack, 30 secondi di mountain climber, 10 burpees
                JSON: {"name": "Circuito", "rounds": 4, "roundRest": 60, "exercises": [{"name": "Jumping jack", "reps": 20}, {"name": "Mountain climber", "seconds": 30}, {"name": "Burpees", "reps": 10}]}
            """.trimIndent()
            return "$rules$names\n$examples"
        }

        /** Exercise names from programs and past workouts, most recently used first. */
        fun knownExercises(programs: List<Program>, entries: List<LogEntry>): List<String> =
            (entries.sortedByDescending { it.timestamp }.flatMap { e -> e.sets.orEmpty().map { it.exercise } } +
                programs.flatMap { p -> p.exercises.map { it.name } })
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }

        /**
         * Parses Nano's answer into a valid program: missing or out-of-range values get the
         * editor's defaults, and names matching a known exercise take its exact spelling.
         */
        fun parse(raw: String, knownExercises: List<String>): Program {
            val text = LocalAi.stripCodeFence(raw)
            // Tolerate a sentence before or after the object.
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            require(start >= 0 && end > start) { "La risposta non contiene un programma" }
            val draft = json.decodeFromString<Draft>(text.substring(start, end + 1))

            val known = knownExercises.associateBy { it.lowercase().trim() }
            val exercises = draft.exercises.filter { it.name.isNotBlank() }.map { d ->
                val name = d.name.trim().let { matchKnown(it, known) ?: it.replaceFirstChar(Char::uppercase) }
                val seconds = d.seconds?.takeIf { it > 0 }
                Exercise(
                    name = name,
                    sets = (d.sets ?: d.rounds)?.takeIf { it in 1..20 } ?: 3,
                    // A timed exercise wins over reps if Nano sets both; neither -> 10 reps.
                    reps = if (seconds == null) d.reps?.takeIf { it > 0 } ?: 10 else null,
                    seconds = seconds,
                    rest = d.rest?.takeIf { it in 0..900 } ?: 60,
                    notes = d.notes?.trim().orEmpty(),
                )
            }
            require(exercises.isNotEmpty()) { "Non ho trovato esercizi nella descrizione" }
            val rounds = draft.rounds?.takeIf { it in 1..50 }
            return Program(
                name = draft.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Programma",
                exercises = exercises,
                rounds = rounds,
                roundRest = draft.roundRest?.takeIf { rounds != null && it in 0..900 } ?: 60,
            )
        }
    }
}
