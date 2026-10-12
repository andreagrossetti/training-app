package com.andreagrossetti.training.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramHistoryTest {
    private val warmup = Exercise("Riscaldamento", sets = 1, seconds = 300, rest = 0)
    private val push = Exercise("Push-up", sets = 4, reps = 8, rest = 90)
    private val dip = Exercise("Dip", reps = 8, rest = 90)
    private val pike = Exercise("Pike", reps = 8, rest = 90)
    private val old = Program(name = "A", exercises = listOf(warmup, push, dip, pike))

    @Test
    fun describesMoveAndTargets() {
        val new = old.copy(exercises = listOf(warmup, pike.copy(reps = 6, rest = 120, notes = "da fresco"), push, dip))
        assertEquals(
            listOf(
                "Spostato: Pike, ora dopo Riscaldamento",
                "Pike: 3 × 8 → 3 × 6",
                "Pike: recupero 1:30 → 2:00",
                "Pike: note aggiornate",
            ),
            programChanges(old, new),
        )
    }

    @Test
    fun addedAndRemoved() {
        val new = old.copy(exercises = listOf(warmup, push, pike, Exercise("Plank", seconds = 30)))
        assertEquals(listOf("Tolto: Dip", "Aggiunto: Plank (3 × 30 s)"), programChanges(old, new))
    }

    @Test
    fun unchangedHasNoChanges() {
        assertEquals(emptyList<String>(), programChanges(old, old.copy(exercises = old.exercises.map { it.copy() })))
    }
}
