package com.jarvis.ai.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import com.jarvis.ai.model.SpeechLanguage
import java.util.Locale

/**
 * Lists the voices installed on the phone for one language. Android does not report gender,
 * so the names are shown as they are and the user picks the voice they like.
 */
object PhoneVoices {

    fun list(context: Context, language: SpeechLanguage, onReady: (List<String>) -> Unit) {
        val wanted = Locale.forLanguageTag(language.tag)
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            val names = if (status == TextToSpeech.SUCCESS) {
                runCatching {
                    engine?.voices.orEmpty()
                        .filter { voice ->
                            voice.locale.language == wanted.language &&
                                (wanted.language != "en" || voice.locale.country == wanted.country)
                        }
                        .map { it.name }
                        .sorted()
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            engine?.shutdown()
            onReady(names)
        }
    }
}
