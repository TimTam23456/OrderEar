package com.timtam.orderear

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.Locale

/**
 * Order Ear: the order-taking page runs in a WebView, and Android's own
 * SpeechRecognizer feeds it what it hears (WebViews can't use the browser's
 * speech recognition). It restarts after every phrase so it keeps listening.
 * Replies are spoken with the phone's built-in text-to-speech (free, on device);
 * the mic pauses while it talks so it doesn't hear itself.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var muted = false
    private val main = Handler(Looper.getMainLooper())
    private val restart = Runnable { if (listening && !speaking) listen() }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var utter = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#1B1D1F")
        window.navigationBarColor = Color.parseColor("#1B1D1F")
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#1B1D1F"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "AndroidSpeech")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) main.post { setupVoice() } }
    }

    /** Pick the best Australian English voice that's already on the phone (no internet needed). */
    private fun setupVoice() {
        val t = tts ?: return
        try {
            t.language = Locale("en", "AU")
            val voices = t.voices?.filter {
                it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                    !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            } ?: emptyList()
            val best = voices.filter { it.locale.country == "AU" }.maxByOrNull { it.quality }
                ?: voices.filter { it.locale.country == "GB" }.maxByOrNull { it.quality }
            if (best != null) t.voice = best
            t.setSpeechRate(1.05f)
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (_: Exception) {}
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { doneSpeaking() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { doneSpeaking() } }
            override fun onError(utteranceId: String?, errorCode: Int) { main.post { doneSpeaking() } }
        })
        ttsReady = true
    }

    private fun speakNow(text: String) {
        val t = tts
        if (!ttsReady || t == null) { state("spoke"); return }
        speaking = true
        state("speaking")
        main.removeCallbacks(restart)
        try { recognizer?.cancel() } catch (_: Exception) {}
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "oe" + (++utter))
    }

    private fun doneSpeaking() {
        if (!speaking) return
        speaking = false
        state("spoke")
        if (listening) restartSoon(300)
    }

    inner class Bridge {
        @JavascriptInterface fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(this@MainActivity)
        @JavascriptInterface fun start() { main.post { begin() } }
        @JavascriptInterface fun stop() { main.post { end() } }
        @JavascriptInterface fun speak(text: String) { main.post { speakNow(text) } }
        @JavascriptInterface fun hush() { main.post { tts?.stop(); doneSpeaking() } }
        @JavascriptInterface fun hasVoice(): Boolean = ttsReady
    }

    private fun begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            state("error:This phone has no speech recognition service. Install or update the Google app, then try again.")
            state("stopped")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        }
        listening = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setMuted(true)
        listen()
        state("listening")
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-AU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try { recognizer?.startListening(i) } catch (e: Exception) { restartSoon(500) }
    }

    private fun restartSoon(ms: Long) {
        main.removeCallbacks(restart)
        if (listening) main.postDelayed(restart, ms)
    }

    private fun end() {
        listening = false
        main.removeCallbacks(restart)
        try { recognizer?.cancel() } catch (_: Exception) {}
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        main.postDelayed({ if (!listening) setMuted(false) }, 400)
        state("stopped")
    }

    /** Android plays a beep every time listening restarts; keep those quiet while the mic is on. */
    private fun setMuted(on: Boolean) {
        if (on == muted) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        for (stream in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                am.adjustStreamVolume(stream, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {}
        }
        muted = on
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(b: Bundle?) { if (!speaking) first(b)?.let { send(it, false) } }
        override fun onResults(b: Bundle?) {
            if (!speaking) first(b)?.let { send(it, true) }
            restartSoon(100)
        }
        override fun onError(error: Int) {
            if (speaking) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { listening = false; setMuted(false); state("denied") }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    state("error:No internet connection. Speech recognition needs one, unless you download English (Australia) for offline speech in your phone's settings.")
                    restartSoon(2500)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    try { recognizer?.cancel() } catch (_: Exception) {}
                    restartSoon(600)
                }
                else -> restartSoon(200) // nothing heard / timed out: just keep listening
            }
        }
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun send(text: String, isFinal: Boolean) = js("window.onNativeSpeech&&window.onNativeSpeech(${JSONObject.quote(text)},$isFinal)")
    private fun state(s: String) = js("window.onNativeState&&window.onNativeState(${JSONObject.quote(s)})")
    private fun js(code: String) { main.post { web.evaluateJavascript(code, null) } }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) begin()
            else state("denied")
        }
    }

    override fun onPause() {
        super.onPause()
        if (listening) end()   // never keep the mic on when the app isn't on screen
        tts?.stop(); speaking = false
    }

    override fun onDestroy() {
        listening = false
        main.removeCallbacksAndMessages(null)
        setMuted(false)
        recognizer?.destroy()
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }
}
