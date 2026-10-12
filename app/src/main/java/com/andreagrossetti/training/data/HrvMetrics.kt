package com.andreagrossetti.training.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Everything that can be computed from a reading's raw intervals, as in Elite HRV's "Data details". */
data class HrvMetrics(
    val rmssd: Double,
    val lnRmssd: Double,
    /** Standard deviation of the kept intervals, ms. */
    val sdnn: Double,
    /** Share of successive differences above 50 ms, 0..1. */
    val pnn50: Double,
    val meanRr: Double,
    val meanHr: Double,
    val minHr: Double,
    val maxHr: Double,
    /** Beat-by-beat heart rate: seconds from the start to bpm, artifacts left out. */
    val hrSeries: List<Pair<Double, Double>>,
    /** Null when the recording is too short or too broken up for a meaningful spectrum. */
    val spectrum: HrvSpectrum?,
)

/** Band powers in ms², peaks in Hz. LF 0.04–0.15 Hz, HF 0.15–0.40 Hz; total is LF + HF. */
data class HrvSpectrum(val lf: Double, val hf: Double, val lfPeak: Double, val hfPeak: Double) {
    val total: Double get() = lf + hf
    val lfHf: Double get() = if (hf == 0.0) 0.0 else lf / hf
}

fun hrvMetrics(rr: List<Int>): HrvMetrics? {
    val clean = cleanRr(rr)
    val series = clean.series
    val kept = series.filter { it != RR_GAP }
    val rmssd = rmssd(series)?.takeIf { it > 0 } ?: return null
    val diffs = series.zipWithNext().filter { (a, b) -> a != RR_GAP && b != RR_GAP }.map { (a, b) -> b - a }
    val meanRr = kept.average()
    val sdnn = sqrt(kept.sumOf { (it - meanRr) * (it - meanRr) } / (kept.size - 1).coerceAtLeast(1))

    // Time runs over every received interval, so dropped beats leave a hole instead of shifting the rest.
    val hr = ArrayList<Pair<Double, Double>>()
    var t = 0.0
    for (i in rr.indices) {
        if (rr[i] == RR_GAP) continue
        t += rr[i] / 1000.0
        if (isKept(rr, i)) hr += t to 60_000.0 / rr[i]
    }

    // Too little of the signal left and the spectrum is mostly made up by the interpolation.
    val spectrum = if (clean.artifacts <= MAX_ARTIFACTS && kept.sum() >= MIN_SPECTRUM_MS) spectrum(kept) else null
    return HrvMetrics(
        rmssd = rmssd,
        lnRmssd = ln(rmssd),
        sdnn = sdnn,
        pnn50 = diffs.count { kotlin.math.abs(it) > 50 }.toDouble() / diffs.size,
        meanRr = meanRr,
        meanHr = 60_000.0 / meanRr,
        minHr = hr.minOf { it.second },
        maxHr = hr.maxOf { it.second },
        hrSeries = hr,
        spectrum = spectrum,
    )
}

/** A good minute of beats: LF needs a few of its slow (up to 25 s) cycles. */
private const val MIN_SPECTRUM_MS = 75_000
private const val FS = 4.0

/**
 * Power spectrum of the beat intervals: resampled evenly at 4 Hz with a cubic spline (straight lines
 * between beats would flatten the fast, breathing-paced swings), detrended, Hann-windowed periodogram
 * (a plain DFT: only the bins up to 0.4 Hz are needed).
 */
internal fun spectrum(intervals: List<Int>): HrvSpectrum {
    // Beat times and their interval values.
    val times = DoubleArray(intervals.size)
    var acc = 0.0
    intervals.forEachIndexed { i, v -> acc += v / 1000.0; times[i] = acc }
    val values = DoubleArray(intervals.size) { intervals[it].toDouble() }
    val m = naturalSplineMoments(times, values)
    val n = ((times.last() - times.first()) * FS).toInt()
    val x = DoubleArray(n)
    var j = 0
    for (k in 0 until n) {
        val t = times.first() + k / FS
        while (j < times.size - 2 && times[j + 1] < t) j++
        val h = times[j + 1] - times[j]
        val a = (times[j + 1] - t) / h
        val b = (t - times[j]) / h
        x[k] = a * values[j] + b * values[j + 1] + ((a * a * a - a) * m[j] + (b * b * b - b) * m[j + 1]) * h * h / 6
    }
    // Least-squares linear detrend.
    val mk = (n - 1) / 2.0
    val mx = x.average()
    var num = 0.0
    var den = 0.0
    for (k in 0 until n) { num += (k - mk) * (x[k] - mx); den += (k - mk) * (k - mk) }
    val slope = num / den
    val w = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
    val y = DoubleArray(n) { (x[it] - mx - slope * (it - mk)) * w[it] }
    val wPower = w.sumOf { it * it }

    // Zero-padded for a finer grid, so the peaks are located better.
    var nfft = 1024
    while (nfft < n) nfft *= 2
    val df = FS / nfft
    var lf = 0.0
    var hf = 0.0
    var lfPeak = 0.0 to 0.0
    var hfPeak = 0.0 to 0.0
    for (bin in 1..(0.4 / df).toInt()) {
        val f = bin * df
        if (f < 0.04) continue
        var re = 0.0
        var im = 0.0
        for (k in 0 until n) {
            val a = 2 * PI * bin * k / nfft
            re += y[k] * cos(a)
            im -= y[k] * sin(a)
        }
        // One-sided power spectral density, ms²/Hz.
        val psd = 2 * (re * re + im * im) / (FS * wPower)
        if (f < 0.15) {
            lf += psd * df
            if (psd > lfPeak.second) lfPeak = f to psd
        } else {
            hf += psd * df
            if (psd > hfPeak.second) hfPeak = f to psd
        }
    }
    return HrvSpectrum(lf, hf, lfPeak.first, hfPeak.first)
}

/** Second derivatives at the knots of the natural cubic spline through ([x], [y]) (tridiagonal solve). */
private fun naturalSplineMoments(x: DoubleArray, y: DoubleArray): DoubleArray {
    val n = x.size
    val m = DoubleArray(n)
    if (n < 3) return m
    val c = DoubleArray(n)
    val d = DoubleArray(n)
    for (i in 1 until n - 1) {
        val h0 = x[i] - x[i - 1]
        val h1 = x[i + 1] - x[i]
        val rhs = 6 * ((y[i + 1] - y[i]) / h1 - (y[i] - y[i - 1]) / h0)
        val diag = 2 * (h0 + h1) - h0 * c[i - 1]
        c[i] = h1 / diag
        d[i] = (rhs - h0 * d[i - 1]) / diag
    }
    for (i in n - 2 downTo 1) m[i] = d[i] - c[i] * m[i + 1]
    return m
}
