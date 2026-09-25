package com.andreagrossetti.training.hrv

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.andreagrossetti.training.data.HrvMeasurement
import com.andreagrossetti.training.data.HrvRepository
import com.andreagrossetti.training.data.RR_GAP
import com.andreagrossetti.training.data.cleanRr
import com.andreagrossetti.training.data.hrvMeasurement
import com.andreagrossetti.training.data.rmssd
import com.andreagrossetti.training.data.successivePairs
import com.andreagrossetti.training.workout.Cues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface HrvPhase {
    data object Idle : HrvPhase
    /** Looking for the sensor, or connected but no beats yet (finger not in). */
    data object Waiting : HrvPhase
    /** First seconds after the finger goes in: the signal settles, the data is thrown away. */
    data class Stabilizing(val secondsLeft: Int, val poorSignal: Boolean) : HrvPhase
    /**
     * [pairs] are the usable successive beats so far. Past [minSeconds] recording goes on until
     * [targetPairs] (or [maxSeconds]), so a reading with signal drop-outs still gets enough data.
     */
    data class Recording(
        val elapsed: Int,
        val minSeconds: Int,
        val maxSeconds: Int,
        val pairs: Int,
        val targetPairs: Int,
        val rmssd: Double?,
        val poorSignal: Boolean,
    ) : HrvPhase
    /** [saved] is false for an unreliable reading, which the user can still keep. */
    data class Done(val measurement: HrvMeasurement?, val saved: Boolean) : HrvPhase
}

/**
 * A morning HRV reading: waits for the sensor and the finger, drops the first [STABILIZE_S] seconds,
 * records at least [RECORD_S] seconds of beat-to-beat intervals (up to [MAX_RECORD_S] if the signal
 * kept dropping), then stores the result (if reliable) and beeps.
 */
class HrvSession(context: Context, private val repository: HrvRepository, private val cues: Cues) {
    private val main = Handler(Looper.getMainLooper())
    private val sensor = HeartRateSensor(context, ::onPacket, ::onDisconnect)
    val sensorStatus: StateFlow<SensorStatus> = sensor.status

    private val _phase = MutableStateFlow<HrvPhase>(HrvPhase.Idle)
    val phase: StateFlow<HrvPhase> = _phase.asStateFlow()

    private var firstBeatAt = 0L
    private var recordStartedAt = 0L
    private var lastPacketAt = 0L
    /** Last stale packet or lost stretch, to warn about the signal while it happens. */
    private var lastBadAt = 0L
    private val rr = mutableListOf<Int>()

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (active) main.postDelayed(this, 250)
        }
    }

    private val active: Boolean
        get() = _phase.value.let { it !is HrvPhase.Idle && it !is HrvPhase.Done }

    fun start() {
        if (active) return
        rr.clear()
        firstBeatAt = 0L
        recordStartedAt = 0L
        lastPacketAt = 0L
        lastBadAt = 0L
        _phase.value = HrvPhase.Waiting
        sensor.start()
        main.post(ticker)
    }

    /** Stops and discards a reading in progress, or closes the result. */
    fun cancel() {
        main.removeCallbacks(ticker)
        sensor.stop()
        _phase.value = HrvPhase.Idle
    }

    /** Keeps an unreliable reading anyway. */
    fun saveAnyway() {
        val done = _phase.value as? HrvPhase.Done ?: return
        val m = done.measurement ?: return
        if (!done.saved) {
            repository.add(m)
            _phase.value = done.copy(saved = true)
        }
    }

    private fun onPacket(packet: HeartRatePacket) {
        if (!active) return
        if (packet.contact == false) {
            // Finger out: before recording starts, the settling time starts over.
            if (recordStartedAt == 0L) firstBeatAt = 0L
            gap()
            return
        }
        val now = System.currentTimeMillis()
        if (packet.stale) {
            gap()
            lastPacketAt = now
            lastBadAt = now
            tick()
            return
        }
        if (packet.rrMs.isEmpty()) return
        // Notifications that never arrived took their beats with them: if more time passed than the new
        // intervals account for, they don't follow on from the previous ones.
        if (lastPacketAt != 0L && now - lastPacketAt > packet.rrMs.sum() + GAP_SLACK_MS) {
            gap()
            lastBadAt = now
        }
        lastPacketAt = now
        if (firstBeatAt == 0L) firstBeatAt = now
        if (recordStartedAt != 0L) rr += packet.rrMs
        tick()
    }

    private fun onDisconnect() = gap()

    private fun gap() {
        if (rr.isNotEmpty() && rr.last() != RR_GAP) rr += RR_GAP
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        // No packet for a while counts as a drop-out too.
        val poor = now - lastBadAt < POOR_SIGNAL_MS || (lastPacketAt != 0L && now - lastPacketAt > POOR_SIGNAL_MS)
        when {
            !active -> return
            firstBeatAt == 0L -> _phase.value = HrvPhase.Waiting
            recordStartedAt == 0L -> {
                val left = STABILIZE_S - ((now - firstBeatAt) / 1000).toInt()
                if (left <= 0) {
                    recordStartedAt = now
                    cues.tick()
                    _phase.value = recording(0, 0, null, poor)
                } else {
                    _phase.value = HrvPhase.Stabilizing(left, poor)
                }
            }
            else -> {
                val elapsed = ((now - recordStartedAt) / 1000).toInt()
                val clean = cleanRr(rr)
                val pairs = successivePairs(clean.series)
                if (elapsed >= MAX_RECORD_S || elapsed >= RECORD_S && pairs >= TARGET_PAIRS) finish()
                else _phase.value = recording(elapsed, pairs, rmssd(clean.series), poor)
            }
        }
    }

    private fun recording(elapsed: Int, pairs: Int, rmssd: Double?, poor: Boolean) =
        HrvPhase.Recording(elapsed, RECORD_S, MAX_RECORD_S, pairs, TARGET_PAIRS, rmssd, poor)

    private fun finish() {
        main.removeCallbacks(ticker)
        sensor.stop()
        val m = hrvMeasurement(recordStartedAt, System.currentTimeMillis() - recordStartedAt, rr.toList())
        val reliable = m?.reliable == true
        if (reliable) repository.add(m!!)
        cues.finish()
        _phase.value = HrvPhase.Done(m, saved = reliable)
    }

    companion object {
        const val STABILIZE_S = 30
        const val RECORD_S = 90
        const val MAX_RECORD_S = 180
        /** Usable successive beats to collect: about a minute of clean signal. */
        const val TARGET_PAIRS = 60
        private const val POOR_SIGNAL_MS = 5_000
        private const val GAP_SLACK_MS = 700
    }
}
