package com.andreagrossetti.training.workout

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Italian text-to-speech. While speaking it asks for transient audio focus with ducking,
 * so music gets quieter instead of stopping, and gives it back right after.
 */
class Voice(context: Context) {
    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()
    private var speaking = 0

    fun speak(text: String) {
        if (tts == null) tts = TextToSpeech(context, ::onInit)
        if (ready) say(text) else pending += text
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        engine.language = Locale.ITALIAN
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
        })
        ready = true
        pending.forEach(::say)
        pending.clear()
    }

    private fun say(text: String) {
        synchronized(this) { if (speaking++ == 0) audio.requestAudioFocus(focus) }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
    }

    // Called on a TTS thread.
    private fun finished() {
        synchronized(this) {
            if (--speaking <= 0) {
                speaking = 0
                audio.abandonAudioFocusRequest(focus)
            }
        }
    }
}

/** "5:32" as it should be read: "5 e 32". */
fun spokenPace(secondsPerKm: Int): String {
    val m = secondsPerKm / 60
    val s = secondsPerKm % 60
    return if (s == 0) "$m minuti" else "$m e $s"
}
