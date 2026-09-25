package com.andreagrossetti.training.workout

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andreagrossetti.training.data.Exercise
import com.andreagrossetti.training.data.Program
import com.andreagrossetti.training.data.SetRecord
import com.andreagrossetti.training.data.Step
import com.andreagrossetti.training.data.steps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Phase {
    /** Waiting for the user to start the current exercise. */
    READY,
    /** Short countdown before a timed set that doesn't follow a rest. */
    PREPARE,
    WORK,
    REST,
    FINISHED,
}

data class WorkoutState(
    val program: Program,
    val steps: List<Step> = program.steps(),
    val stepIndex: Int = 0,
    val phase: Phase = Phase.READY,
    val remainingMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = false,
    /** Every completed set, as performed. */
    val results: List<SetRecord> = emptyList(),
    /** Repetitions to log for the current rep-based set; starts at the target, adjustable. */
    val reps: Int = 0,
    /** Bumped on every seek so observers (the media session) republish the position. */
    val seekCount: Int = 0,
    /** Epoch millis when the workout was started. */
    val startedAt: Long = System.currentTimeMillis(),
    /** Diary entry written when the workout finished. */
    val logEntryId: String? = null,
    /** The current REST comes before [step] (between two exercises), not after it. */
    val restBefore: Boolean = false,
) {
    val step: Step get() = steps[stepIndex]
    val exerciseIndex: Int get() = step.exerciseIndex
    val setIndex: Int get() = step.round
    val exercise: Exercise get() = program.exercises[exerciseIndex]
    val setsDone: Int get() = results.size
    val setsTotal: Int get() = steps.size
    /** Sets of the current exercise, or rounds of the circuit. */
    val roundsTotal: Int get() = program.rounds ?: exercise.sets
    val nextStep: Step? get() = steps.getOrNull(stepIndex + 1)
    /** The exercise of the next set, when it's a different one. */
    val nextExercise: Exercise?
        get() = nextStep?.takeIf { it.exerciseIndex != exerciseIndex }?.let { program.exercises[it.exerciseIndex] }
    /** Whether no other exercise block follows: skipping it ends the workout. */
    val isLastBlock: Boolean get() = steps.drop(stepIndex + 1).none { it.readyBefore }
    val isTimed: Boolean
        get() = phase == Phase.PREPARE || phase == Phase.REST || (phase == Phase.WORK && exercise.timed)
    val remainingSeconds: Int get() = ((remainingMs + 999) / 1000).toInt()
    val progress: Float get() = if (durationMs > 0) remainingMs.toFloat() / durationMs else 0f

    /** Whether step [i] has been completed. */
    fun isDone(i: Int): Boolean =
        phase == Phase.FINISHED || i < stepIndex || (i == stepIndex && phase == Phase.REST && !restBefore)

    /** "Serie 2/3" or "Giro 2/3" for [step]. */
    fun roundLabel(step: Step = this.step): String {
        val total = program.rounds ?: program.exercises[step.exerciseIndex].sets
        return "${if (program.circuit) "Giro" else "Serie"} ${step.round + 1}/$total"
    }
}

/**
 * Drives a workout through the program's [steps]. All mutations happen on the main thread;
 * a foreground service keeps the process (and the ticker) alive while the screen is off.
 */
class WorkoutEngine(
    private val context: Context,
    private val cues: Cues,
    private val voice: Voice,
    private val voiceEnabled: () -> Boolean,
    /** Whether the headphones' volume buttons complete rep-based sets. */
    private val volumeDoneEnabled: () -> Boolean,
    /** Whether to connect to the Bluetooth remote during workouts. */
    private val remoteEnabled: () -> Boolean,
    /** Logs a workout that ended (finished, or stopped with "save"); returns the diary entry id. */
    private val onLog: (WorkoutState) -> String,
) {
    private val scope: CoroutineScope = MainScope()
    private val _state = MutableStateFlow<WorkoutState?>(null)
    val state: StateFlow<WorkoutState?> = _state.asStateFlow()

    private var endAt = 0L
    private var lastTick = -1
    private var ticker: Job? = null
    /** State just before the last "skip exercise", for undoing it. */
    private var beforeSkip: WorkoutState? = null
    private val remote = RemoteButton(context, onClick = ::remoteClick, onLongPress = ::remoteLongPress)
    val remoteStatus: StateFlow<RemoteStatus> = remote.status
    private val volumeTrigger = VolumeTrigger(context) {
        val s = _state.value
        val consume = volumeDoneEnabled() && s != null && s.phase == Phase.WORK && !s.exercise.timed
        if (consume) skip()
        consume
    }

    fun start(program: Program) {
        if (program.exercises.isEmpty()) return
        val state = WorkoutState(program).atStep(0)
        _state.value = state
        announceBlock(state)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
        volumeTrigger.start()
        if (remoteEnabled()) remote.start()
        ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java))
    }

    /** Ends the workout; with [save], logs what was done so far. Returns the diary entry id, if any. */
    fun stop(save: Boolean = false): String? {
        val id = _state.value?.takeIf { save && it.setsDone > 0 && it.phase != Phase.FINISHED }?.let(onLog)
        ticker?.cancel()
        ticker = null
        volumeTrigger.stop()
        remote.stop()
        _state.value = null
        return id
    }

    /** Starts the current exercise from the READY screen. */
    fun begin() {
        val s = _state.value ?: return
        if (s.phase != Phase.READY) return
        if (s.exercise.timed) enterPhase(Phase.PREPARE, PREPARE_MS) else enterWork()
    }

    /** Ends the current phase early (also "Fatto" for rep-based sets). */
    fun skip() {
        when (_state.value?.phase) {
            Phase.PREPARE -> enterWork()
            Phase.WORK -> completeSet()
            Phase.REST -> endRest()
            else -> Unit
        }
    }

    /** Changes the repetitions logged for the current rep-based set. */
    fun adjustReps(delta: Int) {
        val s = _state.value ?: return
        if (s.phase == Phase.WORK && !s.exercise.timed) _state.value = s.copy(reps = (s.reps + delta).coerceAtLeast(0))
    }

    /** Media "play": start the exercise, resume the timer, or complete a rep-based set. */
    fun play() {
        val s = _state.value ?: return
        when {
            s.phase == Phase.READY -> begin()
            s.paused -> togglePause()
            s.phase == Phase.WORK && !s.exercise.timed -> skip()
        }
    }

    /** Media "pause": pauses a running timer. */
    fun pause() {
        val s = _state.value ?: return
        if (s.isTimed && !s.paused) togglePause()
    }

    /** Media "next": skips the current phase, or starts the exercise from READY. */
    fun next() {
        if (_state.value?.phase == Phase.READY) begin() else skip()
    }

    /**
     * Drops the rest of the current exercise (or, while resting before it, the whole of it) and
     * moves to the next one; from the last exercise, ends the workout.
     */
    fun skipExercise() {
        val s = _state.value ?: return
        if (s.phase == Phase.FINISHED) return
        beforeSkip = s.copy(remainingMs = currentRemaining(s))
        val next = (s.stepIndex + 1 until s.steps.size).firstOrNull { s.steps[it].readyBefore }
        if (next == null) {
            finish()
            return
        }
        val moved = s.atStep(next)
        when {
            s.phase == Phase.READY -> {
                _state.value = moved
                enterPhase(Phase.READY)
            }
            // Keep the rest running, now before the exercise after.
            s.phase == Phase.REST && s.restBefore -> _state.value = moved.copy(restBefore = true)
            else -> {
                _state.value = moved
                startExercise()
            }
        }
        announceBlock(_state.value!!)
    }

    /** Goes back to where the last [skipExercise] was called; not once the workout has ended. */
    fun undoSkip() {
        val previous = beforeSkip ?: return
        if (_state.value?.phase == Phase.FINISHED) return
        beforeSkip = null
        endAt = now() + previous.remainingMs
        lastTick = -1
        _state.value = previous
    }

    private fun currentRemaining(s: WorkoutState): Long =
        if (s.isTimed && !s.paused) (endAt - now()).coerceAtLeast(0) else s.remainingMs

    /**
     * Remote click: always "go on" (start, set done, skip the rest or the countdown), except
     * during a timed set, where an accidental press would cut it short.
     */
    private fun remoteClick() {
        val s = _state.value ?: return
        when {
            s.paused -> togglePause()
            s.phase == Phase.READY -> begin()
            s.phase == Phase.WORK && s.exercise.timed -> Unit
            else -> skip()
        }
    }

    /** Remote long press: pause or resume the timer. */
    private fun remoteLongPress() = togglePause()

    fun togglePause() {
        val s = _state.value ?: return
        if (!s.isTimed) return
        if (s.paused) {
            endAt = now() + s.remainingMs
            _state.value = s.copy(paused = false)
        } else {
            _state.value = s.copy(paused = true, remainingMs = (endAt - now()).coerceAtLeast(0))
        }
    }

    /** Moves the current timed phase to [positionMs] from its start (media progress bar scrubbing). */
    fun seekTo(positionMs: Long) {
        val s = _state.value ?: return
        if (!s.isTimed) return
        val remaining = (s.durationMs - positionMs).coerceIn(0, s.durationMs)
        endAt = now() + remaining
        lastTick = -1
        _state.value = s.copy(remainingMs = remaining, seekCount = s.seekCount + 1)
    }

    fun addTime(ms: Long) {
        val s = _state.value ?: return
        if (!s.isTimed) return
        endAt += ms
        _state.value = s.copy(remainingMs = s.remainingMs + ms, durationMs = s.durationMs + ms)
    }

    private fun tick() {
        val s = _state.value ?: return
        if (!s.isTimed || s.paused) return
        val remaining = (endAt - now()).coerceAtLeast(0)
        val seconds = ((remaining + 999) / 1000).toInt()
        // Skip the tick that would coincide with the start of the phase.
        if (seconds != lastTick && seconds * 1000L < s.durationMs) {
            if (seconds in 1..COUNTDOWN_SECONDS) {
                lastTick = seconds
                cues.tick()
            } else if (seconds == WARNING_SECONDS && s.phase != Phase.WORK) {
                // Early heads-up before a set starts, to get in position.
                lastTick = seconds
                cues.warning()
            }
        }
        if (remaining == 0L) {
            when (s.phase) {
                Phase.PREPARE -> enterWork()
                Phase.WORK -> completeSet()
                Phase.REST -> endRest()
                else -> Unit
            }
        } else {
            _state.value = s.copy(remainingMs = remaining)
        }
    }

    private fun enterPhase(phase: Phase, durationMs: Long = 0) {
        val s = _state.value ?: return
        endAt = now() + durationMs
        lastTick = -1
        _state.value = s.copy(
            phase = phase,
            remainingMs = durationMs,
            durationMs = durationMs,
            paused = false,
            restBefore = phase == Phase.REST && s.restBefore,
        )
        when (phase) {
            Phase.WORK -> cues.work()
            Phase.REST -> cues.rest()
            Phase.FINISHED -> cues.finish()
            else -> Unit
        }
    }

    private fun enterWork() {
        val ex = _state.value?.exercise ?: return
        enterPhase(Phase.WORK, if (ex.timed) ex.amount * 1000L else 0)
    }

    private fun completeSet() {
        val s = _state.value ?: return
        val record = if (s.exercise.timed) {
            SetRecord(s.exercise.name, seconds = ((s.durationMs - s.remainingMs + 500) / 1000).toInt())
        } else {
            SetRecord(s.exercise.name, reps = s.reps)
        }
        _state.value = s.copy(results = s.results + record)
        val rest = s.step.restAfter
        val next = s.nextStep
        if (rest > 0 && next != null && next.readyBefore) {
            // Between exercises: move on now and rest before the next one, so the screen
            // already shows what's coming; it starts by itself when the rest is over.
            _state.value = _state.value!!.atStep(s.stepIndex + 1).copy(restBefore = true)
            enterPhase(Phase.REST, rest * 1000L)
            announceBlock(_state.value!!)
        } else if (rest > 0) {
            enterPhase(Phase.REST, rest * 1000L)
            // In a circuit, say what's coming while resting.
            val next = _state.value?.nextStep
            if (next != null && !next.readyBefore && next.exerciseIndex != s.exerciseIndex) {
                val ex = s.program.exercises[next.exerciseIndex]
                say("Poi: ${ex.name}, ${ex.amountSpoken()}")
            }
        } else {
            advance(fromRest = false)
        }
    }

    private fun advance(fromRest: Boolean) {
        val s = _state.value ?: return
        val nextIndex = s.stepIndex + 1
        if (nextIndex >= s.steps.size) {
            finish()
            return
        }
        val next = s.atStep(nextIndex)
        _state.value = next
        val newExercise = next.exerciseIndex != s.exerciseIndex
        when {
            // A new exercise with no rest before it (e.g. after the warm-up).
            next.step.readyBefore -> {
                announceBlock(next)
                startExercise()
            }
            // Switching exercise without a rest: a few seconds to get in position.
            newExercise && next.exercise.timed && !fromRest -> {
                enterPhase(Phase.PREPARE, PREPARE_MS)
                say(next.exercise.name)
            }
            else -> {
                if (newExercise && !fromRest) say("${next.exercise.name}, ${next.exercise.amountSpoken()}")
                enterWork()
            }
        }
    }

    private fun endRest() {
        if (_state.value?.restBefore == true) enterWork() else advance(fromRest = true)
    }

    /** Starts the current exercise without a rest: a countdown to get in position for timed sets. */
    private fun startExercise() {
        val ex = _state.value?.exercise ?: return
        if (ex.timed) enterPhase(Phase.PREPARE, PREPARE_MS) else enterWork()
    }

    private fun finish() {
        enterPhase(Phase.FINISHED)
        _state.value?.takeIf { it.setsDone > 0 }?.let { finished ->
            _state.value = finished.copy(logEntryId = onLog(finished))
        }
        say("Allenamento completato!")
    }

    /** Voice intro of the exercise (or circuit) about to start. */
    private fun announceBlock(s: WorkoutState) {
        val ex = s.exercise
        if (s.program.circuit && s.stepIndex == 0) {
            say("Circuito da ${s.program.rounds} giri. Primo esercizio: ${ex.name}, ${ex.amountSpoken()}")
        } else {
            say("Prossimo esercizio: ${ex.name}, ${ex.sets} serie da ${ex.amountSpoken()}")
        }
    }

    private fun say(text: String) {
        if (voiceEnabled()) voice.speak(text)
    }

    private fun WorkoutState.atStep(index: Int): WorkoutState {
        val moved = copy(stepIndex = index, restBefore = false)
        return moved.copy(reps = moved.exercise.reps ?: 0)
    }

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val TICK_MS = 50L
        const val PREPARE_MS = 15_000L
        const val COUNTDOWN_SECONDS = 3
        const val WARNING_SECONDS = 10
    }
}
