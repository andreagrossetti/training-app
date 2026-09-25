package com.andreagrossetti.training.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File

val programJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    explicitNulls = false
}

/** Stores all programs as a single JSON file in the app's private storage. */
class ProgramRepository(context: Context) {
    private val file = File(context.filesDir, "programs.json")
    private val _programs = MutableStateFlow(load())
    val programs: StateFlow<List<Program>> = _programs.asStateFlow()

    fun get(id: String): Program? = _programs.value.find { it.id == id }

    fun save(program: Program) {
        val list = _programs.value
        val index = list.indexOfFirst { it.id == program.id }
        update(if (index >= 0) list.toMutableList().apply { set(index, program) } else list + program)
    }

    fun delete(id: String) = update(_programs.value.filterNot { it.id == id })

    /** Imports a single program, an array of programs or `{"programs": [...]}`. Returns the count. */
    fun import(text: String): Int {
        val imported = parsePrograms(text).map { it.copy(id = newId()) }
        update(_programs.value + imported)
        return imported.size
    }

    fun replaceAll(programs: List<Program>) = update(programs)

    fun exportJson(): String = programJson.encodeToString(_programs.value)

    private fun update(list: List<Program>) {
        _programs.value = list
        file.writeAtomically(programJson.encodeToString(list))
    }

    private fun load(): List<Program> {
        if (!file.exists()) return samplePrograms
        return runCatching { programJson.decodeFromString<List<Program>>(file.readText()) }
            .getOrDefault(emptyList())
    }
}

fun parsePrograms(text: String): List<Program> {
    val programs = when (val root = programJson.parseToJsonElement(text)) {
        is JsonArray -> programJson.decodeFromJsonElement<List<Program>>(root)
        is JsonObject if "programs" in root ->
            programJson.decodeFromJsonElement<List<Program>>(root.getValue("programs"))
        is JsonObject -> listOf(programJson.decodeFromJsonElement<Program>(root))
        else -> throw IllegalArgumentException("Il JSON deve essere un oggetto o un array")
    }
    for (program in programs) {
        for (ex in program.exercises) {
            require((ex.reps == null) != (ex.seconds == null)) {
                "\"${ex.name}\": specifica \"reps\" oppure \"seconds\""
            }
            require(ex.sets > 0 && ex.amount > 0 && ex.rest >= 0) {
                "\"${ex.name}\": valori non validi"
            }
        }
    }
    return programs
}
