package com.andreagrossetti.training.ai

import com.andreagrossetti.training.data.Exercise
import com.andreagrossetti.training.data.LogEntry
import com.andreagrossetti.training.data.LogKind
import com.andreagrossetti.training.data.Program
import com.andreagrossetti.training.data.SetRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramGeneratorTest {
    private fun parse(raw: String, known: List<String> = emptyList()) = ProgramGenerator.parse(raw, known)

    @Test
    fun parsesTheSpikeAnswer() {
        val p = parse(
            """{"name": "Allenamento gambe", "exercises": [{"name": "Squat", "sets": 4, "reps": 12, "rest": 90},
            {"name": "Affondi", "sets": 3, "reps": 10, "notes": "per gamba"}, {"name": "Plank", "sets": 3, "seconds": 45, "rest": 30}]}""",
        )
        assertEquals("Allenamento gambe", p.name)
        assertNull(p.rounds)
        assertEquals(listOf("Squat", "Affondi", "Plank"), p.exercises.map { it.name })
        assertEquals(listOf(90, 60, 30), p.exercises.map { it.rest })
        assertEquals("per gamba", p.exercises[1].notes)
        assertTrue(p.exercises[2].timed)
        assertEquals(45, p.exercises[2].amount)
    }

    @Test
    fun toleratesFencesAndChatter() {
        val p = parse("Ecco il programma:\n```json\n{\"name\": \"X\", \"exercises\": [{\"name\": \"Trazioni\", \"sets\": 5, \"reps\": 5}]}\n```")
        assertEquals("Trazioni", p.exercises.single().name)
    }

    @Test
    fun fixesInvalidValues() {
        val ex = parse("""{"exercises": [{"name": "plank", "reps": 10, "seconds": 30, "sets": 0, "rest": -5}, {"name": "Squat"}]}""").exercises
        // Both reps and seconds: timed wins. Out-of-range sets/rest get the defaults.
        assertEquals(Exercise(name = "Plank", sets = 3, seconds = 30, rest = 60, key = ex[0].key), ex[0])
        assertEquals(10, ex[1].reps)
        assertFalse(ex[1].timed)
    }

    @Test
    fun circuit() {
        val p = parse("""{"name": "C", "rounds": 4, "roundRest": 90, "exercises": [{"name": "Burpees", "reps": 10}]}""")
        assertEquals(4, p.rounds)
        assertEquals(90, p.roundRest)
    }

    @Test
    fun usesKnownSpelling() {
        val p = parse("""{"name": "A", "exercises": [{"name": "piegamenti", "reps": 10}]}""", known = listOf("Piegamenti"))
        assertEquals("Piegamenti", p.exercises.single().name)
    }

    @Test
    fun matchesSynonymsOfKnownExercises() {
        val p = parse(
            """{"name": "A", "exercises": [{"name": "Piegamenti", "reps": 12}, {"name": "Affondi", "reps": 10}]}""",
            known = listOf("Push-up", "Plank"),
        )
        // Push-up is known under its English name; Affondi has no known synonym and stays as is.
        assertEquals(listOf("Push-up", "Affondi"), p.exercises.map { it.name })
    }

    @Test
    fun exerciseRoundsAreSets() {
        // Real answer from the phone: "plank 2 volte 40 secondi" came back with rounds instead of sets.
        val p = parse("""{"name": "S", "exercises": [{"name": "Plank", "rounds": 2, "seconds": 40}]}""")
        assertEquals(2, p.exercises.single().sets)
        assertEquals(60, p.exercises.single().rest)
        assertNull(p.rounds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmpty() {
        parse("""{"name": "Vuoto", "exercises": []}""")
    }

    @Test
    fun knownExercisesMostRecentFirst() {
        val programs = listOf(Program(name = "P", exercises = listOf(Exercise(name = "Squat", reps = 5), Exercise(name = "Plank", seconds = 30))))
        val entries = listOf(
            LogEntry(timestamp = 1, kind = LogKind.PROGRAM, title = "a", sets = listOf(SetRecord("Trazioni", reps = 5))),
            LogEntry(timestamp = 2, kind = LogKind.PROGRAM, title = "b", sets = listOf(SetRecord("plank", seconds = 30))),
        )
        assertEquals(listOf("plank", "Trazioni", "Squat"), ProgramGenerator.knownExercises(programs, entries))
    }

    @Test
    fun promptNormalizesDurations() {
        assertEquals("Testo: squat con 90 secondi di recupero\nJSON:", ProgramGenerator.prompt(" squat con un minuto e mezzo di recupero "))
        assertTrue(ProgramGenerator.system(listOf("Piegamenti")).contains("Piegamenti"))
    }
}
