package com.andreagrossetti.training.workout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log

/**
 * Exposes the workout as a media session. Samsung's Always On Display and the lock screen
 * show media controls for any app, while generic ongoing notifications are only shown there
 * for whitelisted apps. The "track" is the current phase: its progress bar advances during
 * timed phases, play/pause pauses the timer (or starts / completes a set), next skips.
 */
class WorkoutMedia(context: Context, private val engine: WorkoutEngine) {
    private val session = MediaSession(context, "workout").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = log("play") { engine.play() }
            override fun onPause() = log("pause") { engine.pause() }
            override fun onSkipToNext() = log("next") { engine.next() }
            override fun onSeekTo(pos: Long) = log("seek $pos") { engine.seekTo(pos) }
            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                Log.d(TAG, "session: $action")
                if (action == ACTION_ADD_TIME) engine.addTime(15_000)
            }
        })
        isActive = true
    }

    val token: MediaSession.Token get() = session.sessionToken

    fun update(state: WorkoutState) {
        val running = state.isTimed && !state.paused
        val playbackState = if (running) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val position = if (state.isTimed) state.durationMs - state.remainingMs else 0L

        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title(state))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle(state))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, state.program.name)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, if (state.isTimed) state.durationMs else 0L)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art(state))
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(playbackState, position, if (running) 1f else 0f, SystemClock.elapsedRealtime())
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SEEK_TO
                )
                .apply {
                    if (state.phase == Phase.REST) {
                        addCustomAction(ACTION_ADD_TIME, "+15 s", android.R.drawable.ic_input_add)
                    }
                }
                .build()
        )
    }

    fun release() = session.release()

    private inline fun log(command: String, action: () -> Unit) {
        Log.d(TAG, "session: $command")
        action()
    }

    /** Phase label, plus the remaining time for timed phases (refreshed every second). */
    fun title(state: WorkoutState): String =
        remaining(state)?.let { "${phaseLabel(state)} · $it" } ?: phaseLabel(state)

    private fun remaining(state: WorkoutState): String? {
        if (!state.isTimed) return null
        val s = state.remainingSeconds
        return if (s >= 60) "%d:%02d".format(s / 60, s % 60) else "$s"
    }

    private fun subtitle(state: WorkoutState): String {
        val ex = state.exercise
        val sets = state.roundLabel()
        return when (state.phase) {
            Phase.READY -> "${ex.name} · ${if (state.program.circuit) ex.amountText() else ex.summary()} · ▶ per iniziare"
            Phase.PREPARE -> "${ex.name} · $sets"
            Phase.WORK -> if (ex.timed) "${ex.name} · $sets" else "${ex.name} · ${state.reps} rip. · ▶ quando hai fatto"
            Phase.REST -> if (state.restBefore) "Poi: ${ex.name} · ${ex.summary()}" else state.nextStep?.let { next ->
                "Poi: ${state.program.exercises[next.exerciseIndex].name} · ${state.roundLabel(next)}"
            } ?: ""
            Phase.FINISHED -> ""
        } + if (state.paused) " · in pausa" else ""
    }

    /** Cover art: phase color with the phase label and, for timed phases, the big countdown. */
    private fun art(state: WorkoutState): Bitmap {
        val size = 256
        val label = phaseLabel(state)
        val time = remaining(state)
        val color = when (state.phase) {
            Phase.READY -> 0xFF2E1308
            Phase.PREPARE -> 0xFFF7A61E
            Phase.WORK -> 0xFF129A5B
            Phase.REST -> 0xFF3C5BD6
            Phase.FINISHED -> 0xFFBC44BB
        }.toInt()
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(color)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            fun drawCentered(text: String, centerY: Float, maxSize: Float) {
                paint.textSize = maxSize
                while (paint.measureText(text) > size * 0.8f) paint.textSize -= 2f
                canvas.drawText(text, size / 2f, centerY - (paint.descent() + paint.ascent()) / 2, paint)
            }
            if (time == null) {
                drawCentered(label, size / 2f, 48f)
            } else {
                drawCentered(label, size * 0.3f, 36f)
                drawCentered(time, size * 0.62f, 110f)
            }
        }
    }

    private companion object {
        const val ACTION_ADD_TIME = "add_time"
        const val TAG = "Workout"
    }
}

fun phaseLabel(state: WorkoutState): String = when (state.phase) {
    Phase.READY -> "PRONTO?"
    Phase.PREPARE -> "PREPARATI"
    Phase.WORK -> "VAI!"
    Phase.REST -> "RECUPERO"
    Phase.FINISHED -> "FINE"
}
