package com.andreagrossetti.training.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenDurationsTest {
    private fun n(text: String) = SpokenDurations.normalize(text)

    @Test
    fun minutesAndHalves() {
        assertEquals("con 90 secondi di recupero", n("con un minuto e mezzo di recupero"))
        assertEquals("150 secondi", n("due minuti e mezzo"))
        assertEquals("90 secondi", n("1 e mezzo minuti"))
        assertEquals("30 secondi", n("mezzo minuto"))
        assertEquals("75 secondi", n("un minuto e un quarto"))
        assertEquals("120 secondi", n("Due Minuti"))
        assertEquals("90 secondi", n("1:30 minuti"))
    }

    @Test
    fun minutesPlusSeconds() {
        assertEquals("80 secondi", n("un minuto e venti secondi"))
        assertEquals("80 secondi", n("1 minuto e 20"))
    }

    @Test
    fun wordSeconds() {
        assertEquals("45 secondi", n("quarantacinque secondi"))
        assertEquals("21 secondi", n("ventuno secondi"))
        assertEquals("38 secondi", n("trentotto secondi"))
        assertEquals("23 secondi", n("ventitré secondi"))
        assertEquals("30 secondi di pausa", n("30 secondi di pausa"))
    }

    @Test
    fun leavesTheRestAlone() {
        val text = "4 serie da 12 squat, 3 affondi da 10 per gamba, plank 3 volte"
        assertEquals(text, n(text))
        assertEquals("tre volte 45 secondi", n("tre volte quarantacinque secondi"))
    }

    @Test
    fun presetSentence() {
        assertEquals(
            "4 serie da 12 squat con 90 secondi di recupero, 3 affondi da 10 per gamba, " +
                "poi plank 3 volte 45 secondi con 30 secondi di pausa",
            n("4 serie da 12 squat con un minuto e mezzo di recupero, 3 affondi da 10 per gamba, " +
                "poi plank 3 volte 45 secondi con 30 secondi di pausa"),
        )
    }
}
