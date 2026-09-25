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
        val list = (0..3).map { reading(today.minusDays(it.toLong()), 50.0) }
        val r = readiness(list, today) as ReadinessResult.Calibrating
        assertEquals(3, r.days)
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

    private fun reading(date: LocalDate, rmssd: Double) = HrvMeasurement(
        timestamp = date.atTime(7, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        rmssd = rmssd,
        meanHr = 55.0,
        beats = 80,
        artifacts = 0.02,
    )
}
