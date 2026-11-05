package com.jarvis.ai.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.jarvis.ai.model.JarvisState

/**
 * The Jarvis orb, shown from assets/orb.html (Three.js). The page gets the app state and mic level,
 * and tells the app when it is tapped.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JarvisOrbWebView(
    state: JarvisState,
    soundLevel: Float,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {}
) {
    val tapHandler = remember { Handler(Looper.getMainLooper()) }
    val currentOnTap by rememberUpdatedState(onTap)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }

    LaunchedEffect(state, soundLevel, pageReady) {
        if (!pageReady) return@LaunchedEffect
        val name = state.name
        val level = soundLevel.coerceIn(0f, 1f)
        webView?.evaluateJavascript("window.jarvisSetState && window.jarvisSetState('$name', $level);", null)
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    AndroidView(
        modifier = modifier.size(340.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.parseColor("#030100"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                addJavascriptInterface(OrbBridge { tapHandler.post { currentOnTap() } }, "AndroidOrb")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageReady = true
                    }
                }
                loadUrl("file:///android_asset/orb.html")
                webView = this
            }
        }
    )
}

private class OrbBridge(private val onTapped: () -> Unit) {
    @JavascriptInterface
    fun onTap() {
        onTapped()
    }
}
