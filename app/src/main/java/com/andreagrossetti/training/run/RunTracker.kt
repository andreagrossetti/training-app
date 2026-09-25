package com.andreagrossetti.training.run

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.andreagrossetti.training.data.IntervalPlan
import com.andreagrossetti.training.data.IntervalRecord
import com.andreagrossetti.training.data.KmCue
import com.andreagrossetti.training.data.RoutePoint
import com.andreagrossetti.training.workout.Cues
import com.andreagrossetti.training.workout.Voice
import com.andreagrossetti.training.workout.spokenPace
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

enum class RunPhase {
    /** GPS on, waiting for a good fix; nothing is recorded yet. */
    ACQUIRING,
    RUNNING,
    PAUSED,
}

enum class IntervalPhase { WORK, RECOVERY, DONE }

data class RunState(
    val phase: RunPhase = RunPhase.ACQUIRING,
    /** Epoch millis of the start ("VIA"), 0 while acquiring. */
    val startedAt: Long = 0,
    /** Moving time accumulated up to the last pause. */
    val movingMsBeforeResume: Long = 0,
    /** elapsedRealtime of the last start/resume. */
    val resumedAt: Long = 0,
    val distanceM: Double = 0.0,
    val points: List<RoutePoint> = emptyList(),
    /** Moving time of each full kilometre, ms. */
    val splitsMs: List<Long> = emptyList(),
    /** Accuracy of the latest fix, whether or not it was used. */
    val accuracyM: Float? = null,
    /** Pace over the last few seconds, s/km. */
    val paceSecPerKm: Int? = null,
    /** elapsedRealtime of the latest fix that advanced the distance. */
    val lastProgressAt: Long = 0,
    /** Guided intervals, or null for a free run. */
    val plan: IntervalPlan? = null,
    /** Current repeat, 0-based. */
    val interval: Int = 0,
    val intervalPhase: IntervalPhase? = null,
    /** Moving time and distance when the current interval phase began. */
    val phaseStartMs: Long = 0,
    val phaseStartM: Double = 0.0,
    val intervalResults: List<IntervalRecord> = emptyList(),
) {
    fun movingMs(now: Long = SystemClock.elapsedRealtime()): Long =
        movingMsBeforeResume + if (phase == RunPhase.RUNNING) now - resumedAt else 0

    fun averagePace(now: Long = SystemClock.elapsedRealtime()): Int? =
        if (distanceM < MIN_PACE_DISTANCE_M) null else (movingMs(now) / distanceM).toInt()

    /** Current pace, hidden when standing still (no progress for a while). */
    fun currentPace(now: Long = SystemClock.elapsedRealtime()): Int? =
        paceSecPerKm?.takeIf { phase == RunPhase.RUNNING && now - lastProgressAt < STALE_PACE_MS }

    val gpsReady: Boolean get() = accuracyM?.let { it <= MAX_ACCURACY_M } == true

    /** Time (ms) or distance (m) left in the current interval phase; null when not applicable. */
    fun intervalRemaining(now: Long = SystemClock.elapsedRealtime()): Pair<Long?, Double?>? {
        val plan = plan ?: return null
        val elapsed = movingMs(now) - phaseStartMs
        return when (intervalPhase) {
            IntervalPhase.WORK -> plan.workMeters?.let { null to (it - (distanceM - phaseStartM)).coerceAtLeast(0.0) }
                ?: ((plan.workSeconds ?: 0) * 1000L - elapsed).coerceAtLeast(0) to null
            IntervalPhase.RECOVERY -> (plan.recoverySeconds * 1000L - elapsed).coerceAtLeast(0) to null
            else -> null
        }
    }

    private companion object {
        const val MIN_PACE_DISTANCE_M = 50.0
        const val STALE_PACE_MS = 10_000L
    }
}

/** Accuracy (m) above which fixes are ignored. */
const val MAX_ACCURACY_M = 20f

/**
 * Records a run with the phone's GPS. Distance is accumulated between "anchor" fixes that
 * are at least a few metres apart, which removes most of the jitter of a phone standing
 * still, and fixes implying impossible speeds are dropped. Optionally guides an interval
 * session, switching between work and recovery by moving time or distance.
 */
class RunTracker(
    private val context: Context,
    private val cues: Cues,
    private val voice: Voice,
    private val kmCue: () -> KmCue,
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val scope = MainScope()
    private val _state = MutableStateFlow<RunState?>(null)
    val state: StateFlow<RunState?> = _state.asStateFlow()

    private var anchor: Location? = null
    private var rejectedInARow = 0
    /** Recent (elapsedRealtime ms, distance m) samples for the current pace. */
    private val recent = ArrayDeque<Pair<Long, Double>>()
    private var ticker: Job? = null
    private var lastCountdown = -1

    private val listener = LocationListener(::onLocation)

    val locationEnabled: Boolean get() = LocationManagerCompat.isLocationEnabled(locationManager)

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun open(plan: IntervalPlan? = null) {
        if (_state.value != null) return
        _state.value = RunState(plan = plan)
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, UPDATE_MS, 0f, listener, Looper.getMainLooper(),
        )
        ContextCompat.startForegroundService(context, Intent(context, RunService::class.java))
    }

    /** "VIA": starts recording. */
    fun go() {
        val s = _state.value ?: return
        if (s.phase != RunPhase.ACQUIRING) return
        anchor = null
        _state.value = s.copy(
            phase = RunPhase.RUNNING,
            startedAt = System.currentTimeMillis(),
            resumedAt = SystemClock.elapsedRealtime(),
            intervalPhase = if (s.plan != null) IntervalPhase.WORK else null,
        )
        cues.work()
        if (s.plan != null) {
            say("Ripetuta 1 di ${s.plan.repeats}, vai!")
            ticker = scope.launch {
                while (isActive) {
                    _state.value?.let { _state.value = checkInterval(it) }
                    delay(TICK_MS)
                }
            }
        }
    }

    fun togglePause() {
        val s = _state.value ?: return
        val now = SystemClock.elapsedRealtime()
        _state.value = when (s.phase) {
            RunPhase.RUNNING -> s.copy(phase = RunPhase.PAUSED, movingMsBeforeResume = s.movingMs(now), paceSecPerKm = null)
            RunPhase.PAUSED -> {
                // Don't count the distance between where we paused and where we resume.
                anchor = null
                recent.clear()
                s.copy(phase = RunPhase.RUNNING, resumedAt = now)
            }
            RunPhase.ACQUIRING -> s
        }
    }

    /** Stops the GPS and returns the final state (null if nothing was running). */
    fun stop(): RunState? {
        val s = _state.value ?: return null
        locationManager.removeUpdates(listener)
        ticker?.cancel()
        ticker = null
        anchor = null
        recent.clear()
        _state.value = null
        return s.copy(movingMsBeforeResume = s.movingMs(), phase = RunPhase.PAUSED)
    }

    private fun onLocation(location: Location) {
        val s = _state.value ?: return
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        var next = s.copy(accuracyM = accuracy)
        if (s.phase == RunPhase.RUNNING && accuracy <= MAX_ACCURACY_M) next = checkInterval(record(next, location))
        _state.value = next
    }

    private fun record(s: RunState, location: Location): RunState {
        val now = SystemClock.elapsedRealtime()
        val last = anchor
        if (last == null) {
            anchor = location
            recent.addLast(now to s.distanceM)
            return s.copy(points = s.points + point(location, s.movingMs(now), s.distanceM))
        }
        val meters = last.distanceTo(location).toDouble()
        val seconds = (location.elapsedRealtimeNanos - last.elapsedRealtimeNanos) / 1e9
        if (seconds <= 0) return s
        if (meters / seconds > MAX_SPEED_MS) {
            // Either this fix or the anchor is a glitch; after a few in a row, trust the new ones.
            if (++rejectedInARow >= MAX_REJECTED) {
                anchor = location
                rejectedInARow = 0
            }
            return s
        }
        rejectedInARow = 0
        if (meters < max(MIN_STEP_M, location.accuracy * 0.5)) return s

        anchor = location
        val distance = s.distanceM + meters
        val moving = s.movingMs(now)
        val splits = if ((distance / 1000).toInt() > s.splitsMs.size) {
            val split = moving - s.splitsMs.sum()
            announceKm(s.splitsMs.size + 1, (split / 1000).toInt())
            s.splitsMs + split
        } else s.splitsMs

        recent.addLast(now to distance)
        while (recent.size > 2 && now - recent.first().first > PACE_WINDOW_MS) recent.removeFirst()
        val (fromTime, fromDistance) = recent.first()
        val windowMeters = distance - fromDistance
        val pace = if (windowMeters >= MIN_PACE_WINDOW_M) ((now - fromTime) / windowMeters).toInt() else s.paceSecPerKm

        return s.copy(
            distanceM = distance,
            points = s.points + point(location, moving, distance),
            splitsMs = splits,
            paceSecPerKm = pace,
            lastProgressAt = now,
        )
    }

    private fun point(location: Location, movingMs: Long, distance: Double) =
        RoutePoint(location.latitude, location.longitude, movingMs, distance, location.accuracy)

    private fun announceKm(km: Int, splitSeconds: Int) {
        // During intervals the work/recovery cues matter more; don't mix in km cues.
        if (_state.value?.plan != null) return
        when (kmCue()) {
            KmCue.OFF -> Unit
            KmCue.BEEP -> cues.split()
            KmCue.VOICE -> {
                cues.vibrateSplit()
                voice.speak("${if (km == 1) "Un chilometro" else "$km chilometri"}. Passo ${spokenPace(splitSeconds)}")
            }
        }
    }

    private fun checkInterval(s: RunState): RunState {
        val plan = s.plan ?: return s
        if (s.phase != RunPhase.RUNNING) return s
        val now = SystemClock.elapsedRealtime()
        val moving = s.movingMs(now)
        val (remainingMs, _) = s.intervalRemaining(now) ?: return s
        // Beep the last seconds of time-based phases.
        remainingMs?.let {
            val seconds = ((it + 999) / 1000).toInt()
            if (seconds in 1..COUNTDOWN_SECONDS && seconds != lastCountdown) {
                lastCountdown = seconds
                cues.tick()
            }
        }
        return when (s.intervalPhase) {
            IntervalPhase.WORK -> {
                val done = plan.workMeters?.let { s.distanceM - s.phaseStartM >= it } ?: (remainingMs == 0L)
                if (!done) return s
                val record = IntervalRecord(s.distanceM - s.phaseStartM, ((moving - s.phaseStartMs) / 1000).toInt())
                val last = s.interval >= plan.repeats - 1
                lastCountdown = -1
                if (last) {
                    cues.finish()
                    say("Ripetute completate, ottimo lavoro!")
                } else {
                    cues.rest()
                    say("Recupero")
                }
                s.copy(
                    intervalPhase = if (last) IntervalPhase.DONE else IntervalPhase.RECOVERY,
                    intervalResults = s.intervalResults + record,
                    phaseStartMs = moving,
                    phaseStartM = s.distanceM,
                )
            }
            IntervalPhase.RECOVERY -> {
                if (remainingMs != 0L) return s
                lastCountdown = -1
                cues.work()
                say("Ripetuta ${s.interval + 2} di ${plan.repeats}, vai!")
                s.copy(
                    intervalPhase = IntervalPhase.WORK,
                    interval = s.interval + 1,
                    phaseStartMs = moving,
                    phaseStartM = s.distanceM,
                )
            }
            else -> s
        }
    }

    /** Interval cues use the voice only when the user opted into voice announcements. */
    private fun say(text: String) {
        if (kmCue() == KmCue.VOICE) voice.speak(text)
    }

    private companion object {
        const val UPDATE_MS = 1000L
        const val TICK_MS = 250L
        const val COUNTDOWN_SECONDS = 3
        /** ~43 km/h: anything faster on foot is a GPS jump. */
        const val MAX_SPEED_MS = 12.0
        const val MAX_REJECTED = 5
        const val MIN_STEP_M = 5.0
        const val PACE_WINDOW_MS = 20_000L
        const val MIN_PACE_WINDOW_M = 15.0
    }
}
