package com.jarvis.ai.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.ai.ai.GeminiClient
import com.jarvis.ai.core.JarvisCore
import com.jarvis.ai.study.CodingLanguages
import com.jarvis.ai.study.CodingTutor
import com.jarvis.ai.study.JsSandbox
import com.jarvis.ai.study.Levels
import com.jarvis.ai.study.PythonRunner
import com.jarvis.ai.study.StudyProgress
import com.jarvis.ai.ui.theme.JarvisBackground
import com.jarvis.ai.ui.theme.JarvisCyan
import com.jarvis.ai.ui.theme.JarvisMuted
import com.jarvis.ai.ui.theme.JarvisSurface
import com.jarvis.ai.ui.theme.JarvisText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private enum class StudyKind { LEARNER, TUTOR, CODE, OUTPUT, NOTE }

private data class StudyItem(val kind: StudyKind, val text: String, val language: String = "")

/** A message to send once the screen is free. "shown" is what the learner sees in the chat. */
private data class Pending(val message: String, val shown: String)

/** Where the learner is in today's lesson: not started, teaching, or the recap questions. */
private enum class LessonPhase { NONE, TEACH, RECAP }

private val CodeBackground = Color(0xFF0D1117)
private val CodeText = Color(0xFFE6EDF3)
private val OutputText = Color(0xFF7EE787)

/**
 * The popup that opens over the home screen. Tapping outside does not close it, so a lesson is not lost by accident.
 * The Back button or "Band karo" closes it.
 */
@Composable
fun CodingStudyPopup(initialLanguage: String, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = JarvisBackground,
            border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.4f))
        ) {
            // A new language named by voice starts a fresh lesson screen.
            key(initialLanguage) {
                CodingStudyScreen(initialLanguage = initialLanguage, onClose = onClose)
            }
        }
    }
}

/**
 * Coding study. One language, one small topic a day, from the basics up to master level.
 * Each idea is shown by code that runs on the phone (JavaScript, HTML, CSS and Python). The real output goes back to the tutor.
 * At the end of the day the tutor asks three questions about what the learner learned.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CodingStudyScreen(initialLanguage: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { GeminiClient() }
    val chat = remember { mutableStateListOf<StudyItem>() }
    val listState = rememberLazyListState()
    var language by rememberSaveable { mutableStateOf(CodingLanguages.canonical(initialLanguage)) }
    var input by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeLanguage by rememberSaveable { mutableStateOf("none") }
    var output by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var phase by remember(language) { mutableStateOf(LessonPhase.NONE) }
    var topicTitle by remember(language) { mutableStateOf("") }
    // Progress is kept for each language on its own.
    val progress = remember(language) { StudyProgress(context, language) }
    var day by remember(language) { mutableStateOf(progress.day) }
    var doneToday by remember(language) { mutableStateOf(progress.doneToday()) }

    LaunchedEffect(language) { StudyProgress.rememberLanguage(context, language) }

    // Runs the code in the editor. JavaScript and Python run on the phone, HTML and CSS show a live preview.
    suspend fun runCurrent() {
        val src = code
        if (src.isBlank()) {
            output = "Editor mein code nahi hai."
            return
        }
        when (codeLanguage) {
            "javascript" -> {
                preview = ""
                running = true
                output = "Chal raha hai..."
                try {
                    val result = JsSandbox.run(context, src)
                    output = result
                    chat.add(StudyItem(StudyKind.OUTPUT, result))
                } finally {
                    running = false
                }
            }
            "python" -> {
                preview = ""
                running = true
                output = "Python chal raha hai..."
                try {
                    val result = withTimeoutOrNull(8_000L) {
                        withContext(Dispatchers.IO) { PythonRunner.run(context, src) }
                    } ?: "Time limit khatam. Code 8 second se zyada chala."
                    output = result
                    chat.add(StudyItem(StudyKind.OUTPUT, result))
                } finally {
                    running = false
                }
            }
            "html", "css" -> {
                preview = CodingTutor.previewFor(codeLanguage, src)
                output = "Live preview upar dikh raha hai. Button, color aur layout wahin chalte hain."
                chat.add(StudyItem(StudyKind.NOTE, output))
            }
            else -> {
                val name = if (codeLanguage == "none") language else codeLanguage
                preview = ""
                output = "$name abhi is phone par run nahi hota. Code ko line-by-line padhiye aur samjhiye."
                chat.add(StudyItem(StudyKind.NOTE, output))
            }
        }
    }

    // Changes the language. The chat starts again, because the lesson belongs to one language.
    fun switchLanguage(name: String) {
        if (busy || name == language) return
        language = name
        chat.clear()
        code = ""
        codeLanguage = "none"
        output = ""
        preview = ""
    }

    // Sends a message to the tutor. The lesson so far goes with it, including the real output of the last run.
    fun ask(message: String, fromLearner: Boolean, shown: String = message) {
        if (busy) return
        val key = JarvisCore.keys().load()
        if (key.isNullOrBlank()) {
            chat.add(StudyItem(StudyKind.NOTE, "Gemini API key set nahi hai. Settings mein jaakar key daalein."))
            return
        }
        val history = historyOf(chat)
        if (fromLearner) chat.add(StudyItem(StudyKind.LEARNER, shown))
        busy = true
        val mode = phase
        val lessonText = if (mode == LessonPhase.NONE) null else lessonRequest(progress, day, language)
        scope.launch {
            try {
                val step = CodingTutor.nextStep(
                    client,
                    key,
                    JarvisCore.settings().geminiModel,
                    language,
                    history,
                    message,
                    lessonText,
                    recap = mode == LessonPhase.RECAP
                )
                if (step.reply.isNotBlank()) chat.add(StudyItem(StudyKind.TUTOR, step.reply))
                if (step.check.isNotBlank()) chat.add(StudyItem(StudyKind.TUTOR, "Check: ${step.check}"))
                if (mode == LessonPhase.TEACH && step.topic.isNotBlank()) topicTitle = step.topic
                if (mode == LessonPhase.TEACH && step.lessonDone) {
                    // Teaching is done. The three recap questions come next, started by the app.
                    phase = LessonPhase.RECAP
                    chat.add(StudyItem(StudyKind.NOTE, "Ab aaj ke topic par 3 chhote sawal. Jawab neeche likhiye."))
                    pending = Pending("Recap shuru karo.", "Aaj ka recap")
                }
                if (mode == LessonPhase.RECAP && step.recapDone) {
                    val title = topicTitle.ifBlank { "Day $day" }
                    progress.finishToday(title)
                    day = progress.day
                    doneToday = true
                    phase = LessonPhase.NONE
                    chat.add(
                        StudyItem(
                            StudyKind.NOTE,
                            "Aaj ka lesson poora hua: $title. Kal Day $day, level ${Levels.forDay(day)}."
                        )
                    )
                }
                if (step.code.isNotBlank()) {
                    code = step.code
                    codeLanguage = step.language
                    chat.add(StudyItem(StudyKind.CODE, step.code, step.language))
                    runCurrent()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chat.add(StudyItem(StudyKind.NOTE, "Jawab nahi aaya. Ek baar phir bhejiye. (${e.message?.take(100).orEmpty()})"))
            } finally {
                busy = false
            }
        }
    }

    // A message waits here until the screen is free, so it is never lost while a reply is still coming.
    LaunchedEffect(pending, busy) {
        val next = pending
        if (next != null && !busy) {
            pending = null
            ask(next.message, fromLearner = true, shown = next.shown)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(JarvisBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Coding Study", color = JarvisCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Koi bhi bhasha: \"Python sikhao\" bolein ya neeche chunein",
                    color = JarvisMuted, fontSize = 12.sp
                )
            }
            TextButton(onClick = onClose) { Text("Band karo", color = JarvisCyan) }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CodingLanguages.POPULAR.forEach { name ->
                OutlinedButton(onClick = { switchLanguage(name) }, enabled = !busy) {
                    Text(if (name == language) "● $name" else name, fontSize = 12.sp)
                }
            }
        }

        LaunchedEffect(chat.size) {
            if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chat) { item -> ChatItem(item) }
        }

        if (chat.isEmpty()) {
            LessonCard(
                language = language,
                day = day,
                doneToday = doneToday,
                busy = busy,
                onStart = {
                    phase = LessonPhase.TEACH
                    ask("Aaj ka lesson shuru karo.", fromLearner = true, shown = "Aaj ka lesson shuru karo")
                }
            )
        }

        if (preview.isNotEmpty()) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(200.dp).padding(vertical = 4.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.blockNetworkLoads = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = WebViewClient()
                    }
                },
                update = { view ->
                    if (view.tag != preview) {
                        view.tag = preview
                        view.loadDataWithBaseURL(null, preview, "text/html", "utf-8", null)
                    }
                }
            )
        }

        Text(
            "Code (yahan edit kar sakte hain)",
            color = JarvisMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(CodeBackground, RoundedCornerShape(10.dp))
                .padding(10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            BasicTextField(
                value = code,
                onValueChange = { code = it },
                textStyle = TextStyle(color = CodeText, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                cursorBrush = SolidColor(JarvisCyan),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .padding(top = 6.dp)
                .background(JarvisSurface, RoundedCornerShape(10.dp))
                .padding(10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                if (output.isBlank()) "Output yahan dikhega (Run dabayein)" else output,
                color = if (running) JarvisMuted else OutputText,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { scope.launch { runCurrent() } },
                enabled = !running && !busy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
            ) { Text("Run karo") }
            OutlinedButton(
                onClick = { ask("Agla step dikhao. Pichhle output ko dekh kar aage badho.", fromLearner = true, shown = "Agla step") },
                enabled = !busy && chat.isNotEmpty() && phase == LessonPhase.TEACH,
                modifier = Modifier.weight(1f)
            ) { Text("Agla step") }
        }

        if (busy) {
            Text("Tutor soch raha hai...", color = JarvisMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = {
                    Text(
                        if (phase == LessonPhase.RECAP) "Apna jawab likhiye" else "Topic ya sawal likhiye",
                        color = JarvisMuted
                    )
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !busy
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        input = ""
                        // A language named while the course is free switches the language, then the message is sent.
                        val named = if (phase == LessonPhase.NONE) CodingLanguages.fromCommand(text) else null
                        if (named != null && named != language) {
                            switchLanguage(named)
                            pending = Pending(text, text)
                        } else {
                            ask(text, fromLearner = true)
                        }
                    }
                },
                enabled = !busy && input.isNotBlank()
            ) { Text("Bhejo") }
        }
    }
}

/** Shows today's lesson in this language, its level, and whether today is already done. */
@Composable
private fun LessonCard(language: String, day: Int, doneToday: Boolean, busy: Boolean, onStart: () -> Unit) {
    val level = Levels.forDay(day)
    val runNote = if (CodingLanguages.canRunOnPhone(language)) {
        "Har idea ka code phone par live chalega."
    } else {
        "Is language ka code abhi phone par nahi chalta. Tutor code likhega aur line-by-line samjhaega."
    }
    val title = if (doneToday) "Aaj ka lesson ho gaya" else "$language · Day $day · $level"
    val body = if (doneToday) "Kal: Day $day · $level" else "Aaj ek chhota topic, live practical ke saath, aur ant mein 3 sawal. $runNote"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(JarvisSurface, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(title, color = JarvisCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(body, color = JarvisText, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        if (!doneToday) {
            Button(onClick = onStart, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
                Text("Aaj ka lesson shuru karo")
            }
        }
    }
}

@Composable
private fun ChatItem(item: StudyItem) {
    when (item.kind) {
        StudyKind.LEARNER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                item.text,
                color = JarvisText,
                fontSize = 15.sp,
                modifier = Modifier
                    .background(JarvisSurface, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        StudyKind.TUTOR -> Text(item.text, color = JarvisText, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
        StudyKind.CODE -> CodeCard(item)
        StudyKind.OUTPUT -> Text(
            "Output:\n${item.text}",
            color = OutputText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth()
        )
        StudyKind.NOTE -> Text(item.text, color = JarvisMuted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CodeCard(item: StudyItem) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(CodeBackground, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.language.ifBlank { "code" },
                color = JarvisMuted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("code", item.text))
            }) { Text("Copy", color = JarvisCyan, fontSize = 12.sp) }
        }
        Text(
            item.text,
            color = CodeText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
    }
}

/** What the tutor must teach today: the day, the level, and the topics already taught (so it does not repeat them). */
private fun lessonRequest(progress: StudyProgress, day: Int, language: String): String {
    val taught = progress.covered()
    val already = if (taught.isEmpty()) "none yet" else taught.takeLast(40).joinToString(" | ")
    return "$language, Day $day, level ${Levels.forDay(day)}. Topics already taught (do not repeat): $already"
}

/** The last part of the lesson, as plain text for the tutor. */
private fun historyOf(items: List<StudyItem>): String =
    items.takeLast(12).joinToString("\n") { item ->
        val who = when (item.kind) {
            StudyKind.LEARNER -> "Learner"
            StudyKind.TUTOR -> "Tutor"
            StudyKind.CODE -> "Tutor code (${item.language})"
            StudyKind.OUTPUT -> "Real output from phone"
            StudyKind.NOTE -> "Note"
        }
        "$who: ${item.text.take(800)}"
    }
