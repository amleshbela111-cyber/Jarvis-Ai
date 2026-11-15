package com.jarvis.ai.study

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Runs JavaScript in a hidden WebView with no network access. It returns what console.log printed,
 * or the error. It gives up after a few seconds, so an endless loop cannot hang the app.
 */
object JsSandbox {

    private const val LIMIT_MS = 5_000L

    suspend fun run(context: Context, code: String): String {
        val result = CompletableDeferred<String>()
        val view = withContext(Dispatchers.Main) { create(context, code, result) }
        return try {
            withTimeoutOrNull(LIMIT_MS) { result.await() }
                ?: "Time limit (5 second) khatam. Code shayad kisi loop mein atak gaya."
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                view.stopLoading()
                view.destroy()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context, code: String, result: CompletableDeferred<String>): WebView {
        val view = WebView(context)
        view.settings.javaScriptEnabled = true
        view.settings.blockNetworkLoads = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.domStorageEnabled = false
        view.addJavascriptInterface(Bridge(result), "Android")
        var started = false
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(page: WebView?, url: String?) {
                if (started) return
                started = true
                page?.evaluateJavascript("runCode(" + JSONObject.quote(code) + ")", null)
            }
        }
        view.loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null)
        return view
    }

    /** The page calls Android.done(text) with the output. Public so the release build keeps it (see proguard rules). */
    class Bridge(private val result: CompletableDeferred<String>) {
        @JavascriptInterface
        fun done(text: String) {
            result.complete(text)
        }
    }

    /**
     * Captures console output. It waits for timers and promises that the code started, then reports.
     * Errors are reported as they happen.
     */
    private const val PAGE = """<!doctype html>
<html><head><meta charset="utf-8"></head><body>
<script>
(function () {
  var lines = [];
  var finished = false;
  var pending = 0;
  var realTimeout = window.setTimeout;

  function show(v) {
    if (typeof v === 'string') return v;
    try { var s = JSON.stringify(v); return s === undefined ? String(v) : s; } catch (e) { return String(v); }
  }
  function add(prefix, args) {
    var parts = [];
    for (var i = 0; i < args.length; i++) parts.push(show(args[i]));
    lines.push(prefix + parts.join(' '));
  }
  console.log = function () { add('', arguments); };
  console.info = console.log;
  console.warn = function () { add('warn: ', arguments); };
  console.error = function () { add('error: ', arguments); };

  function finish(err) {
    if (finished) return;
    finished = true;
    if (err) lines.push('Error: ' + err);
    if (lines.length === 0) lines.push('(koi output nahi aaya)');
    Android.done(lines.join('\n').slice(0, 4000));
  }
  function check() { if (pending === 0) finish(null); }

  window.setTimeout = function (fn, ms) {
    pending++;
    return realTimeout.call(window, function () {
      try { fn(); } catch (e) { finish(e && e.message ? e.message : String(e)); return; }
      pending--;
      check();
    }, ms);
  };

  window.addEventListener('error', function (ev) { finish(ev.message || 'error'); });
  window.addEventListener('unhandledrejection', function (ev) {
    var r = ev.reason;
    finish(r && r.message ? r.message : String(r));
  });

  window.runCode = function (code) {
    try {
      (0, eval)(code);
    } catch (e) {
      finish(e && e.name ? e.name + ': ' + e.message : String(e));
      return;
    }
    realTimeout.call(window, check, 50);
  };
})();
</script>
</body></html>"""
}
