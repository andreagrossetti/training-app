package com.andreagrossetti.training.workout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Turns a press of the headphones' volume buttons into an action, even with the screen off
 * and while another app (music) owns the media buttons. Bluetooth headphones with absolute
 * volume change the phone's music volume, which the system broadcasts; when [onPress]
 * consumes the change, the volume is put back so the music doesn't get louder or quieter.
 */
class VolumeTrigger(private val context: Context, private val onPress: () -> Boolean) {
    private val audio = context.getSystemService(AudioManager::class.java)
    /** Volume we're restoring: its own broadcast must not count as a press. */
    private var restoringTo: Int? = null
    private var lastPress = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            val value = intent.getIntExtra(EXTRA_VALUE, -1)
            val previous = intent.getIntExtra(EXTRA_PREV_VALUE, -1)
            if (value == previous || value < 0 || previous < 0) return
            if (restoringTo != null) {
                if (value == restoringTo) restoringTo = null
                return
            }
            // A held button sends several steps: they make a single press.
            val now = SystemClock.elapsedRealtime()
            if (now - lastPress < DEBOUNCE_MS) return
            if (onPress()) {
                lastPress = now
                restoringTo = previous
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0) }
            }
        }
    }
    private var registered = false

    fun start() {
        if (registered) return
        restoringTo = null
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }

    private companion object {
        // Not in the public SDK, but sent by every Android version.
        const val ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        const val DEBOUNCE_MS = 1_500L
    }
}
