package com.andreagrossetti.training.workout

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Synthesized beeps + vibration. Sounds use the media stream, so they mix with music
 * and follow the media volume.
 */
class Cues(context: Context) {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val tick = track(tone(880.0, 150))
    private val warning = track(tone(880.0, 120) + silence(80) + tone(880.0, 120))
    private val go = track(tone(1320.0, 700))
    private val rest = track(tone(660.0, 220) + silence(90) + tone(660.0, 220))
    private val split = track(tone(1100.0, 140) + silence(70) + tone(1100.0, 140) + silence(70) + tone(1320.0, 300))
    private val finish = track(tone(880.0, 180) + silence(60) + tone(1100.0, 180) + silence(60) + tone(1320.0, 500))

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** Countdown tick during the last seconds of a phase. */
    fun tick() = play(tick)

    /** Heads-up a few seconds before the countdown, to get in position. */
    fun warning() {
        play(warning)
        vibrate(longArrayOf(0, 150, 100, 150))
    }

    fun work() {
        play(go)
        vibrate(longArrayOf(0, 600))
    }

    fun rest() {
        play(rest)
        vibrate(longArrayOf(0, 200, 120, 200))
    }

    /** A kilometre completed during a run. */
    fun split() {
        play(split)
        vibrate(longArrayOf(0, 300, 150, 300))
    }

    /** Vibration only, for when the kilometre is announced by voice. */
    fun vibrateSplit() = vibrate(longArrayOf(0, 300, 150, 300))

    fun finish() {
        play(finish)
        vibrate(longArrayOf(0, 200, 100, 200, 100, 500))
    }

    private fun play(track: AudioTrack) {
        runCatching {
            track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    private fun vibrate(pattern: LongArray) {
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun track(samples: ShortArray): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
            .also { it.write(samples, 0, samples.size) }

    private fun tone(frequency: Double, millis: Int): ShortArray {
        val count = SAMPLE_RATE * millis / 1000
        val fade = SAMPLE_RATE * 8 / 1000 // 8 ms fade in/out avoids clicks
        return ShortArray(count) { i ->
            val envelope = min(1.0, min(i, count - 1 - i).toDouble() / fade)
            (sin(2 * PI * frequency * i / SAMPLE_RATE) * envelope * 0.9 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun silence(millis: Int) = ShortArray(SAMPLE_RATE * millis / 1000)

    private companion object {
        const val SAMPLE_RATE = 44100
    }
}
