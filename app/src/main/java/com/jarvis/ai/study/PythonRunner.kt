package com.jarvis.ai.study

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** Runs Python on the phone with Chaquopy. The Python side (jarvis_runner.py) stops code after 5 seconds. */
object PythonRunner {

    /** Blocking call. Run it on an IO thread. */
    fun run(context: Context, code: String): String {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return try {
            Python.getInstance().getModule("jarvis_runner").callAttr("run", code).toString()
        } catch (e: PyException) {
            "Python error: ${e.message.orEmpty()}"
        }
    }
}
