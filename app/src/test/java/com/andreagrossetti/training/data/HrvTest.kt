package com.andreagrossetti.training.data

import com.andreagrossetti.training.hrv.HeartRateSensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp

class HrvTest {
    @Test
    fun parsesHeartRateWithRrIntervals() {
        // Flags: 8-bit HR, contact supported + detected, RR present. HR 60, RR 1024 and 512 (1/1024 s).
        val bytes = byteArrayOf(0x16, 60, 0x00, 0x04, 0x00, 0x02)
        val p = HeartRateSensor.parseHeartRate(bytes)!!
        assertEquals(60, p.bpm)
        assertEquals(true, p.contact)
        assertEquals(listOf(1000, 500), p.rrMs)
    }

    @Test
    fun parsesSixteenBitHeartRateAndSkipsEnergy() {
        // Flags: 16-bit HR, energy expended present, RR present; no contact info.
        val bytes = byteArrayOf(0x19, 0x48, 0x00, 0x10, 0x00, 0x00, 0x04)
        val p = HeartRateSensor.parseHeartRate(bytes)!!
        assertEquals(72, p.bpm)
        assertEquals(16, p.energy)
        assertNull(p.contact)
        assertEquals(listOf(1000), p.rrMs)
    }

    @Test
    fun readsCorSenseSignalField() {
        // Real CorSense packets: 0x18 = RR + "energy" present. 0x0006 = fresh beats, 0x0000 = stale repeats.
        val fresh = HeartRateSensor.parseHeartRate(byteArrayOf(0x18, 0x3b, 0x06, 0x00, 0x08, 0x04, 0xd8.toByte(), 0x03))!!
        assertEquals(6, fresh.energy)
        assertEquals(listOf(1008, 961), fresh.rrMs)
        val stale = HeartRateSensor.parseHeartRate(byteArrayOf(0x18, 0x3b, 0x00, 0x00, 0xe4.toByte(), 0x03, 0xd8.toByte(), 0x03))!!
        assertEquals(0, stale.energy)
    }

    @Test
    fun tooFewSuccessivePairsIsUnreliable() {
        // 60 beats, but only in pairs split by gaps: 30 usable differences.
        val rr = (1..30).flatMap { listOf(1000, 1020, RR_GAP) }
        val m = hrvMeasurement(0, 90_000, rr)!!
        assertEquals(60, m.beats)
        assertEquals(30, m.pairs)
        assertEquals(false, m.reliable)
    }

    @Test
    fun rmssdOfAlternatingIntervals() {
        // Successive differences all ±50 ms → RMSSD 50.
        assertEquals(50.0, rmssd(listOf(1000, 1050, 1000, 1050, 1000))!!, 1e-9)
    }

    @Test
    fun artifactIsDroppedAndNotBridged() {
        // A missed beat shows up as a double interval.
        val raw = listOf(1000, 1020, 1000, 1020, 2020, 1000, 1020, 1000, 1020)
        val clean = cleanRr(raw)
        assertEquals(8, clean.beats)
        assertTrue(RR_GAP in clean.series)
        // Only the ±20 ms differences either side of the gap count, never the jump to or from the artifact.
        assertEquals(20.0, rmssd(clean.series)!!, 1e-9)
    }

    @Test
    fun calibratesUntilEnoughMornings() {
        val today = LocalDate.of(2026, 9, 25)
        val list = (0..2).map { reading(today.minusDays(it.toLong()), 50.0) }
        val r = readiness(list, today) as ReadinessResult.Calibrating
        assertEquals(2, r.days)
    }

    @Test
    fun lowMorningIsRed() {
        val today = LocalDate.of(2026, 9, 25)
        // Baseline alternating 45/55 ms, today far below.
        val baseline = (1..14).map { reading(today.minusDays(it.toLong()), if (it % 2 == 0) 45.0 else 55.0) }
        val green = readiness(baseline + reading(today, 52.0), today) as ReadinessResult.Ready
        assertEquals(Readiness.GREEN, green.readiness)
        val red = readiness(baseline + reading(today, 25.0), today) as ReadinessResult.Ready
        assertEquals(Readiness.RED, red.readiness)
        assertEquals(exp((kotlin.math.ln(45.0) + kotlin.math.ln(55.0)) / 2), red.baselineRmssd, 0.01)
    }

    @Test
    fun unreliableReadingsAreIgnored() {
        val today = LocalDate.of(2026, 9, 25)
        assertNull(readiness(listOf(reading(today, 50.0).copy(artifacts = 0.4)), today))
    }

    @Test
    fun baselineOnlyUsesTheSamePosition() {
        val today = LocalDate.of(2026, 9, 25)
        // Two weeks lying down around 80 ms; a first seated morning at 40 ms has no seated baseline yet.
        val lying = (1..14).map { reading(today.minusDays(it.toLong()), if (it % 2 == 0) 75.0 else 85.0) }
        val seated = reading(today, 40.0).copy(position = HrvPosition.SITTING)
        assertTrue(readiness(lying + seated, today) is ReadinessResult.Calibrating)
    }

    @Test
    fun timeDomainMetrics() {
        val rr = (1..60).flatMap { listOf(1000, 1060) }
        val m = hrvMetrics(rr)!!
        assertEquals(60.0, m.rmssd, 1e-9)
        assertEquals(1.0, m.pnn50, 1e-9)
        assertEquals(1030.0, m.meanRr, 1e-9)
        assertEquals(60_000.0 / 1060, m.minHr, 1e-9)
        assertEquals(60.0, m.maxHr, 1e-9)
        assertEquals(30.1, m.sdnn, 0.1)
    }

    @Test
    fun breathingAtFifteenPerMinuteShowsUpAsHf() {
        // Intervals swinging ±50 ms at 0.25 Hz for 2 minutes: variance 50²/2 = 1250 ms², all in HF.
        val rr = mutableListOf<Int>()
        var t = 0.0
        while (t < 120) {
            val v = (1000 + 50 * kotlin.math.sin(2 * Math.PI * 0.25 * t)).toInt()
            rr += v
            t += v / 1000.0
        }
        val s = hrvMetrics(rr)!!.spectrum!!
        assertEquals(0.25, s.hfPeak, 0.01)
        assertEquals(1250.0, s.hf, 150.0)
        assertTrue(s.lf < s.hf / 10)
    }

    @Test
    fun shortReadingHasNoSpectrum() {
        assertNull(hrvMetrics(List(50) { 1000 + (it % 2) * 30 })!!.spectrum)
    }

    @Test
    fun provisionalVerdictFromThreeMornings() {
        // The real 26–29 Sep mornings: 83, 97, 106 ms around 44.6 bpm, then 62 ms at 53 bpm.
        val today = LocalDate.of(2026, 9, 29)
        val past = listOf(83.4, 97.5, 106.2).mapIndexed { i, v -> reading(today.minusDays(3L - i), v).copy(meanHr = 44.6) }
        val r = readiness(past + reading(today, 62.3).copy(meanHr = 53.3), today) as ReadinessResult.Ready
        assertTrue(r.provisional)
        assertEquals(Readiness.YELLOW, r.readiness)
        assertTrue(r.highHr)
        val advice = dailyAdvice(r, emptyList(), today)
        assertEquals(Readiness.YELLOW, advice.readiness)
        assertTrue(advice.reasons.first().startsWith("HRV 62 ms: 35% sotto"))
    }

    @Test
    fun badSleepTurnsGreenIntoYellow() {
        val today = LocalDate.of(2026, 9, 29)
        val past = (1..3).map { reading(today.minusDays(it.toLong()), 80.0) }
        val r = readiness(past + reading(today, 82.0).copy(sleep = 1), today) as ReadinessResult.Ready
        assertEquals(Readiness.GREEN, r.readiness)
        assertEquals(Readiness.YELLOW, dailyAdvice(r, emptyList(), today).readiness)
    }

    @Test
    fun repeatedMorningReadingsAreAveraged() {
        val today = LocalDate.of(2026, 9, 27)
        val first = reading(today, 65.0)
        val second = first.copy(id = "b", timestamp = first.timestamp + 8 * 60_000, rmssd = 97.5)
        val evening = first.copy(id = "c", timestamp = first.timestamp + 10 * 3_600_000, rmssd = 30.0)
        val day = dailyHrv(listOf(first, second, evening)).single()
        assertEquals(exp((kotlin.math.ln(65.0) + kotlin.math.ln(97.5)) / 2), day.rmssd, 1e-6)
        assertEquals(first.id, day.id)
    }

    private fun reading(date: LocalDate, rmssd: Double) = HrvMeasurement(
        timestamp = date.atTime(7, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        rmssd = rmssd,
        meanHr = 55.0,
        beats = 80,
        artifacts = 0.02,
    )
}
