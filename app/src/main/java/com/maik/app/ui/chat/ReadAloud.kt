package com.maik.app.ui.chat

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.maik.app.data.speakableText
import java.util.Locale

/**
 * Reads replies aloud with the phone's own offline voice. One at a time; starting
 * another stops the first.
 */
class ReadAloud internal constructor() {
    /** The message being read, by its timestamp, or null when silent. */
    var speakingAt by mutableStateOf<Long?>(null)
        internal set

    internal var engine: TextToSpeech? = null
    internal var ready = false

    /** False when the phone has no speech engine at all. */
    val available: Boolean get() = ready

    fun speak(at: Long, text: String) {
        val tts = engine ?: return
        if (!ready) return
        speakingAt = at
        tts.speak(speakableText(text), TextToSpeech.QUEUE_FLUSH, null, at.toString())
    }

    fun stop() {
        engine?.stop()
        speakingAt = null
    }
}

@Composable
fun rememberReadAloud(): ReadAloud {
    val context = LocalContext.current
    val reader = remember { ReadAloud() }
    DisposableEffect(context) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            reader.ready = status == TextToSpeech.SUCCESS
            if (reader.ready) {
                runCatching { tts?.language = Locale.getDefault() }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == reader.speakingAt?.toString()) reader.speakingAt = null
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                reader.speakingAt = null
            }
        })
        reader.engine = tts
        onDispose {
            // Leaving the chat stops the voice; nothing should talk from the background.
            tts.stop()
            tts.shutdown()
            reader.engine = null
            reader.ready = false
            reader.speakingAt = null
        }
    }
    return reader
}
