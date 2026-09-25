package com.andreagrossetti.training.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/**
 * An exercise made of [sets] identical sets. Exactly one of [reps] / [seconds] is set:
 * [reps] for repetition-based sets, [seconds] for timed sets. [rest] is the recovery
 * time in seconds between two sets.
 */
@Serializable
data class Exercise(
    val name: String,
    val sets: Int = 3,
    val reps: Int? = null,
    val seconds: Int? = null,
    val rest: Int = 60,
    val notes: String = "",
    // UI-only identity, regenerated on every load.
    @Transient val key: String = newId(),
) {
    val timed: Boolean get() = seconds != null
    val amount: Int get() = seconds ?: reps ?: 0

    fun withTimed(timed: Boolean) =
        if (timed) copy(seconds = amount, reps = null) else copy(reps = amount, seconds = null)

    fun withAmount(value: Int) = if (timed) copy(seconds = value) else copy(reps = value)

    fun summary(): String = if (timed) "$sets × ${formatDuration(amount)}" else "$sets × $amount"

    /** Target of a single set: "10" or "30 s". */
    fun amountText(): String = if (timed) formatDuration(amount) else "$amount"

    /** Target of a single set, spelled out for the voice. */
    fun amountSpoken(): String = if (timed) "$amount secondi" else "$amount ripetizioni"
}

/**
 * With [rounds] null the exercises are done one after the other, each with its own sets.
 * With [rounds] set it's a circuit: every round goes through all exercises once
 * ([Exercise.sets] is ignored, [Exercise.rest] is the rest after that exercise), with
 * [roundRest] seconds between rounds.
 */
@Serializable
data class Program(
    val id: String = newId(),
    val name: String,
    val exercises: List<Exercise> = emptyList(),
    val rounds: Int? = null,
    val roundRest: Int = 60,
) {
    val circuit: Boolean get() = rounds != null
}

/** One set to perform: [round] is the set number, or the round of a circuit (0-based). */
data class Step(val exerciseIndex: Int, val round: Int, val restAfter: Int, val readyBefore: Boolean)

/**
 * The sequence of sets of a program, with the rest after each one. Outside circuits the
 * exercise's rest also follows its last set, before moving on to the next exercise.
 */
fun Program.steps(): List<Step> {
    val rounds = rounds
    if (rounds == null) {
        return exercises.flatMapIndexed { i, ex ->
            (0 until ex.sets).map { s ->
                val last = s == ex.sets - 1 && i == exercises.lastIndex
                Step(i, s, restAfter = if (last) 0 else ex.rest, readyBefore = s == 0)
            }
        }
    }
    return (0 until rounds).flatMap { round ->
        exercises.indices.map { i ->
            val rest = when {
                i < exercises.lastIndex -> exercises[i].rest
                round < rounds - 1 -> roundRest
                else -> 0
            }
            Step(i, round, restAfter = rest, readyBefore = round == 0 && i == 0)
        }
    }
}

/** A set as actually performed. */
@Serializable
data class SetRecord(val exercise: String, val reps: Int? = null, val seconds: Int? = null)

fun formatDuration(seconds: Int): String =
    if (seconds < 60) "$seconds s" else "%d:%02d".format(seconds / 60, seconds % 60)

val samplePrograms = listOf(
    Program(
        name = "Esempio",
        exercises = listOf(
            Exercise(name = "Tenute alla sbarra", sets = 3, seconds = 30, rest = 60),
            Exercise(name = "Piegamenti", sets = 3, reps = 10, rest = 90),
        ),
    ),
)
