package com.andreagrossetti.training.data

import com.andreagrossetti.training.ui.nextTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogicTest {
    private val pushups = Exercise(name = "Piegamenti", sets = 3, reps = 10, rest = 60)
    private val plank = Exercise(name = "Plank", sets = 2, seconds = 30, rest = 30)

    @Test
    fun setsModeRestsBetweenSetsAndExercises() {
        val steps = Program(name = "p", exercises = listOf(pushups, plank)).steps()
        assertEquals(5, steps.size)
        assertEquals(listOf(60, 60, 60, 30, 0), steps.map { it.restAfter })
        assertEquals(listOf(true, false, false, true, false), steps.map { it.readyBefore })
    }

    @Test
    fun circuitGoesRoundByRound() {
        val program = Program(name = "c", exercises = listOf(pushups.copy(rest = 0), plank.copy(rest = 20)), rounds = 3, roundRest = 90)
        val steps = program.steps()
        assertEquals(6, steps.size)
        assertEquals(listOf(0, 1, 0, 1, 0, 1), steps.map { it.exerciseIndex })
        assertEquals(listOf(0, 0, 1, 1, 2, 2), steps.map { it.round })
        // Last exercise of a round rests roundRest, except in the final round.
        assertEquals(listOf(0, 90, 0, 90, 0, 0), steps.map { it.restAfter })
        assertEquals(listOf(true, false, false, false, false, false), steps.map { it.readyBefore })
    }

    @Test
    fun bestEffortOverOneKm() {
        // Steady 5:00/km for 2.5 km, with a faster (4:00/km) km in the middle.
        val points = mutableListOf<RoutePoint>()
        var t = 0L
        var d = 0.0
        for (i in 0..250) {
            points += RoutePoint(0.0, 0.0, t, d)
            val pace = if (d in 750.0..1750.0) 240 else 300 // s/km
            d += 10
            t += pace * 10L // ms for 10 m
        }
        val best = bestEfforts(points)
        assertEquals(240, best[1000]!!, 3)
        assertNull(best[5000])
    }

    @Test
    fun suggestsMoreRepsOnlyWhenAllSetsMet() {
        val done = List(3) { SetRecord("Piegamenti", reps = 10) }
        assertEquals(11, nextTarget(pushups, done, 3))
        assertNull(nextTarget(pushups, done.dropLast(1) + SetRecord("Piegamenti", reps = 8), 3))
        assertNull(nextTarget(pushups, done.take(2), 3))
        assertEquals(35, nextTarget(plank, List(2) { SetRecord("Plank", seconds = 30) }, 2))
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        org.junit.Assert.assertTrue("expected $expected±$tolerance, was $actual", kotlin.math.abs(expected - actual) <= tolerance)
}
