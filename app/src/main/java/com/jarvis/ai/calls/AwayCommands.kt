package com.jarvis.ai.calls

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/** Voice commands for away mode. Returns the reply, or null when the command is not about it. */
object AwayCommands {
    private val ON = Regex("(बाहर जा रहा हूँ|बाहर जा रहा हूं|बाहर जा रहा|away mode on|away mode chalu|बाहर मोड चालू)", RegexOption.IGNORE_CASE)
    private val OFF = Regex("(वापस आ गया|वापस आ गई|वापस आ गया हूँ|वापस आ गया हूं|away mode off|बाहर मोड बंद)", RegexOption.IGNORE_CASE)
    private val MISSED = Regex("(मिस्ड कॉल|missed call|कॉल क्या आई|कौन कॉल)", RegexOption.IGNORE_CASE)

    fun handle(command: String, context: Context): String? {
        if (ON.containsMatchIn(command)) {
            val allowed = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            if (!allowed) {
                return "कॉल का जवाब देने के लिए पहले फ़ोन की Settings में Jarvis को notification access दीजिए। फिर से बोलिए 'बाहर जा रहा हूँ'।"
            }
            AwayMode.setOn(context, true)
            return "ठीक है, बाहर मोड चालू है। आने वाली कॉल पर मैं अपने आप बताऊँगा कि सर अभी उपलब्ध नहीं हैं।"
        }
        if (OFF.containsMatchIn(command)) {
            AwayMode.setOn(context, false)
            val missed = AwayMode.missedList(context)
            AwayMode.clearMissed(context)
            return if (missed.isEmpty()) "स्वागत है। बाहर रहते हुए कोई कॉल नहीं आई।"
            else "स्वागत है। बाहर रहते हुए ये कॉल आईं: " + missed.joinToString(", ") + "।"
        }
        if (MISSED.containsMatchIn(command)) {
            val missed = AwayMode.missedList(context)
            return if (missed.isEmpty()) "कोई मिस्ड कॉल नहीं है।"
            else "मिस्ड कॉल: " + missed.joinToString(", ") + "।"
        }
        return null
    }
}
