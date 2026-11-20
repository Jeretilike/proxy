package com.chesslens.android

import android.content.Context
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import com.chesslens.android.chess.PieceColor
import java.util.Locale

/**
 * Speaks results aloud. With the glasses connected as Bluetooth audio, Android plays the speech
 * through them; otherwise through the phone speaker.
 */
class Speaker(context: Context) {
    private var ready = false
    private var queued: String? = null
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            setLanguage()
            queued?.let { say(it) }
            queued = null
        }
    }

    private fun setLanguage() {
        if (tts.setLanguage(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.US)
    }

    fun say(text: String) {
        if (!ready) { queued = text; return }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chesslens")
    }

    fun shutdown() = tts.shutdown()
}

enum class PlayMode(val label: String) { GLASSES("Glasses"), SCREEN("Screen") }

/** Settings stored in SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("chesslens", Context.MODE_PRIVATE)

    var playMode: PlayMode
        get() = PlayMode.entries.firstOrNull { it.name == prefs.getString("playMode", null) } ?: PlayMode.GLASSES
        set(v) = prefs.edit().putString("playMode", v.name).apply()

    var playerColor: PieceColor
        get() = if (prefs.getString("playerColor", "WHITE") == "BLACK") PieceColor.BLACK else PieceColor.WHITE
        set(v) = prefs.edit().putString("playerColor", v.name).apply()

    var handsFree: Boolean
        get() = prefs.getBoolean("handsFree", true)
        set(v) = prefs.edit().putBoolean("handsFree", v).apply()

    var announceOpponent: Boolean
        get() = prefs.getBoolean("announceOpponent", true)
        set(v) = prefs.edit().putBoolean("announceOpponent", v).apply()

    var speakEvaluation: Boolean
        get() = prefs.getBoolean("speakEvaluation", false)
        set(v) = prefs.edit().putBoolean("speakEvaluation", v).apply()

    var thinkTimeMillis: Int
        get() = prefs.getInt("thinkTimeMillis", 1500).coerceAtLeast(200)
        set(v) = prefs.edit().putInt("thinkTimeMillis", v).apply()

    /** How often the glasses check the board even if the view never holds still. */
    var checkIntervalSeconds: Int
        get() = prefs.getInt("checkIntervalSeconds", 5).coerceIn(2, 15)
        set(v) = prefs.edit().putInt("checkIntervalSeconds", v).apply()
}
