package com.kpit.samplegreeting

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class VoiceAssistant(
    private val activity: Activity,
    private val onYes: () -> Unit,
    private val onNo: () -> Unit,
    private val onTimeout: () -> Unit,
    private val onStateChange: (VoiceState) -> Unit
) {
    companion object {
        private const val TAG = "VoiceAssist"
        private const val LISTEN_TIMEOUT_MS = 12000L
        private const val SPEECH_ACTIVE_TIMEOUT_MS = 10000L
        private const val TTS_TO_LISTEN_DELAY = 1500L

        private val POSITIVE = listOf(
            "yes", "yeah", "yep", "yup", "sure", "okay", "ok",
            "open", "do it", "start", "go ahead", "please",
            "alright", "let's go", "right", "fine", "sync",
            "save", "upload", "update"
        )
        private val NEGATIVE = listOf(
            "no", "nope", "nah", "not", "cancel", "skip",
            "don't", "leave", "pass", "later", "never", "dismiss", "good"
        )
        val CONFIRM_LINES = listOf("Sure, opening it now.", "Got it, starting it for you.", "On it. Opening now.")
        val DECLINE_LINES = listOf("Alright, I'll leave it for now.", "No problem, maybe next time.", "Okay, skipping it for now.")
        val RETRY_LINES = listOf(
            "Sorry, I didn't catch that. Would you like me to open it? Just say yes or no.",
            "I didn't hear you clearly. Shall I open it? You can just say yes or no."
        )
        val CLOSE_LINES = listOf(
            "No worries, I'll leave it for now.",
            "That's okay, you can always tap the button if you'd like."
        )
    }

    enum class VoiceState { IDLE, SPEAKING, LISTENING, RETRYING }

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val audioMgr = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var ttsReady = false
    private var isActive = false
    private var retryCount = 0
    private var gen = 0
    private var timeoutRunnable: Runnable? = null

    // Custom callbacks for special flows (sync prompt)
    private var customYes: (() -> Unit)? = null
    private var customNo: (() -> Unit)? = null
    private var customConfirmLine: String? = null
    private var customDeclineLine: String? = null
    private var customRetryLine: String? = null

    init { initTTS() }

    // ═══════════════════
    // PUBLIC API
    // ═══════════════════

    /** Standard app suggestion question */
    fun askQuestion(voiceText: String) {
        customYes = null; customNo = null
        customConfirmLine = null; customDeclineLine = null; customRetryLine = null
        startFlowWhenReady(voiceText)
    }

    /**
     * Custom question with custom callbacks and response lines.
     * Used for sync prompt at Step 29.
     */
    fun askCustomQuestion(
        voiceText: String,
        confirmLine: String,
        declineLine: String,
        retryLine: String,
        onCustomYes: () -> Unit,
        onCustomNo: () -> Unit
    ) {
        customYes = onCustomYes
        customNo = onCustomNo
        customConfirmLine = confirmLine
        customDeclineLine = declineLine
        customRetryLine = retryLine
        startFlowWhenReady(voiceText)
    }

    /** Speak a message without listening for response */
    fun speakMessage(text: String) {
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready for message, waiting...")
            waitForTTS { speakMessage(text) }
            return
        }
        stopEverything()
        gen++; isActive = true
        Log.d(TAG, "━━ Message (gen=$gen) ━━")
        doSpeak(text, "message", gen)
    }

    /**
     * Wait up to 5 seconds for TTS to become ready.
     * On physical devices, TTS init can take 3-5 seconds.
     */
    private fun waitForTTS(attempt: Int = 0, action: () -> Unit) {
        if (ttsReady) {
            action()
            return
        }
        if (attempt >= 10) {  // 10 × 500ms = 5 seconds max wait
            Log.e(TAG, "TTS still not ready after 5s — giving up")
            Log.e(TAG, "Check: Settings → Accessibility → Text-to-Speech")
            Log.e(TAG, "Make sure Google TTS is installed + English voice data downloaded")
            return
        }
        Log.d(TAG, "TTS not ready, retry ${attempt + 1}/10 in 500ms...")
        handler.postDelayed({ waitForTTS(attempt + 1, action) }, 500)
    }

    private fun startFlowWhenReady(voiceText: String) {
        if (ttsReady) {
            startFlow(voiceText)
        } else {
            Log.w(TAG, "TTS not ready yet, waiting...")
            waitForTTS { startFlow(voiceText) }
        }
    }

    fun cancel() {
        if (!isActive) return
        Log.d(TAG, "Cancelled by user")
        stopEverything()
        onStateChange(VoiceState.IDLE)
    }

    fun isRunning() = isActive

    fun destroy() { stopEverything(); tts?.shutdown(); tts = null }

    // ═══════════════════
    // INTERNAL
    // ═══════════════════

    private fun startFlow(voiceText: String) {
        if (!ttsReady) { Log.w(TAG, "TTS not ready"); return }
        stopEverything()
        gen++; isActive = true; retryCount = 0
        Log.d(TAG, "━━ Question (gen=$gen) ━━")
        doSpeak(voiceText, "ask", gen)
    }

    // ═══════════════════
    // TTS
    // ═══════════════════

    private fun initTTS() {
        Log.d(TAG, "Initializing TTS engine...")
        tts = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langResult = tts?.setLanguage(Locale.US)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS: English US not available (result=$langResult)")
                    Log.e(TAG, "Try: Settings → Text-to-Speech → Install voice data → English")
                    // Try UK English as fallback
                    val ukResult = tts?.setLanguage(Locale.UK)
                    if (ukResult == TextToSpeech.LANG_MISSING_DATA || ukResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "TTS: English UK also not available")
                        return@TextToSpeech
                    }
                    Log.d(TAG, "TTS: Using English UK as fallback")
                }
                tts?.setSpeechRate(0.95f)
                ttsReady = true
                Log.d(TAG, "TTS ready ✓ (engine: ${tts?.defaultEngine})")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        handler.post { if (isActive) onStateChange(VoiceState.SPEAKING) }
                    }
                    override fun onDone(id: String?) {
                        val p = (id ?: "").split("_")
                        handler.post { ttsDone(p.getOrElse(0) { "" }, p.getOrElse(1) { "0" }.toIntOrNull() ?: 0) }
                    }
                    override fun onError(id: String?) {
                        val p = (id ?: "").split("_")
                        handler.post { ttsDone(p.getOrElse(0) { "" }, p.getOrElse(1) { "0" }.toIntOrNull() ?: 0) }
                    }
                })
            } else {
                Log.e(TAG, "TTS init FAILED (status=$status)")
                Log.e(TAG, "Possible fixes:")
                Log.e(TAG, "  1. Install 'Google Text-to-Speech' from Play Store")
                Log.e(TAG, "  2. Settings → Accessibility → Text-to-Speech → select engine")
                Log.e(TAG, "  3. Download English voice data in TTS settings")
            }
        }
    }

    private fun doSpeak(text: String, type: String, g: Int) {
        onStateChange(VoiceState.SPEAKING)
        Log.d(TAG, "TTS: '$text' (${type}_$g)")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "${type}_$g")
    }

    private fun ttsDone(type: String, g: Int) {
        if (g != gen || !isActive) return
        Log.d(TAG, "TTS done: $type (gen=$g)")
        when (type) {
            "ask", "retry" -> {
                try { audioMgr.abandonAudioFocus { } } catch (_: Exception) {}
                Log.d(TAG, "Waiting ${TTS_TO_LISTEN_DELAY}ms...")
                handler.postDelayed({
                    if (g == gen && isActive) listen(g)
                }, TTS_TO_LISTEN_DELAY)
            }
            "confirm" -> {
                isActive = false; onStateChange(VoiceState.IDLE)
                (customYes ?: onYes)()
            }
            "decline" -> {
                isActive = false; onStateChange(VoiceState.IDLE)
                (customNo ?: onNo)()
            }
            "close" -> {
                isActive = false; onStateChange(VoiceState.IDLE); onTimeout()
            }
            "message" -> {
                // Just spoke a standalone message — done
                isActive = false; onStateChange(VoiceState.IDLE)
            }
        }
    }

    // ═══════════════════
    // RECOGNITION
    // ═══════════════════

    private fun listen(g: Int) {
        if (g != gen || !isActive) return
        onStateChange(if (retryCount > 0) VoiceState.RETRYING else VoiceState.LISTENING)

        killRecognizer()

        // On retry, add extra delay — Android needs time to fully release
        // the previous recognizer resources, otherwise TOO_MANY_REQUESTS (11)
        if (retryCount > 0) {
            Log.d(TAG, "Retry listen — extra 1.5s cooldown for recognizer...")
            handler.postDelayed({
                if (g == gen && isActive) createAndStartRecognizer(g)
            }, 1500)
        } else {
            createAndStartRecognizer(g)
        }
    }

    private fun createAndStartRecognizer(g: Int) {
        if (g != gen || !isActive) return

        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            noResponse(g); return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer?.setRecognitionListener(makeListener(g))

        // Use LONGER silence values on retry — user already knows
        // what to say, give them more time to speak clearly
        val silenceComplete = if (retryCount > 0) 5000L else 4000L
        val silencePossible = if (retryCount > 0) 4500L else 3500L
        val minLength = if (retryCount > 0) 8000L else 6000L

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minLength)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceComplete)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silencePossible)
        }

        try {
            Log.d(TAG, "Recognizer start (gen=$g, retry=$retryCount, silence=${silenceComplete}ms)")
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}")
            noResponse(g); return
        }

        clearTimeout()
        timeoutRunnable = Runnable {
            if (g == gen && isActive) {
                Log.d(TAG, "Timeout (gen=$g)")
                killRecognizer(); noResponse(g)
            }
        }
        handler.postDelayed(timeoutRunnable!!, LISTEN_TIMEOUT_MS)
    }

    private fun makeListener(g: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {
            if (g != gen) return
            Log.d(TAG, "🎙️ Listening... (gen=$g)")
        }
        override fun onBeginningOfSpeech() {
            if (g != gen) return
            Log.d(TAG, "🗣️ User speaking — extending timeout (gen=$g)")
            clearTimeout()
            timeoutRunnable = Runnable {
                if (g == gen && isActive) {
                    Log.d(TAG, "Speech-active timeout (gen=$g)")
                    killRecognizer(); noResponse(g)
                }
            }
            handler.postDelayed(timeoutRunnable!!, SPEECH_ACTIVE_TIMEOUT_MS)
        }
        override fun onEndOfSpeech() {
            if (g != gen) return
            Log.d(TAG, "🔇 User stopped — waiting for result... (gen=$g)")
        }
        override fun onResults(results: Bundle?) {
            if (g != gen || !isActive) return
            clearTimeout()
            val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            Log.d(TAG, "📋 Candidates: $all (gen=$g)")
            val spoken = all?.firstOrNull()?.lowercase()?.trim() ?: ""
            Log.d(TAG, "✅ Best: '$spoken' (gen=$g)")
            gotResponse(spoken, g)
        }
        override fun onError(error: Int) {
            if (g != gen || !isActive) return
            clearTimeout()
            val name = when (error) {
                1->"NETWORK_TIMEOUT";2->"NETWORK";3->"AUDIO";4->"SERVER"
                5->"CLIENT";6->"SPEECH_TIMEOUT";7->"NO_MATCH";8->"BUSY"
                9->"NO_PERMISSION";11->"TOO_MANY_REQUESTS";else->"UNKNOWN($error)"
            }
            Log.d(TAG, "❌ Error: $name ($error) gen=$g")
            noResponse(g)
        }
        override fun onPartialResults(r: Bundle?) {
            if (g != gen) return
            val partial = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!partial.isNullOrEmpty()) Log.d(TAG, "⏳ Partial: $partial (gen=$g)")
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    // ═══════════════════
    // RESPONSE
    // ═══════════════════

    private fun gotResponse(spoken: String, g: Int) {
        if (g != gen || !isActive) return
        val intent = detectIntent(spoken)
        Log.d(TAG, "Intent: $intent (gen=$g)")
        when (intent) {
            "yes" -> doSpeak(customConfirmLine ?: CONFIRM_LINES.random(), "confirm", g)
            "no"  -> doSpeak(customDeclineLine ?: DECLINE_LINES.random(), "decline", g)
            else  -> noResponse(g)
        }
    }

    private fun noResponse(g: Int) {
        if (g != gen || !isActive) return
        if (retryCount == 0) {
            retryCount = 1
            Log.d(TAG, "Miss → retry (gen=$g)")
            doSpeak(customRetryLine ?: RETRY_LINES.random(), "retry", g)
        } else {
            Log.d(TAG, "Miss again → close (gen=$g)")
            doSpeak(CLOSE_LINES.random(), "close", g)
        }
    }

    private fun detectIntent(s: String): String {
        val l = s.lowercase().trim()
        if (l.isEmpty()) return "unclear"
        if (NEGATIVE.any { l.contains(it) }) return "no"
        if (POSITIVE.any { l.contains(it) }) return "yes"
        return "unclear"
    }

    // ═══════════════════
    // CLEANUP
    // ═══════════════════

    private fun stopEverything() {
        isActive = false; tts?.stop(); clearTimeout(); killRecognizer()
        handler.removeCallbacksAndMessages(null)
    }

    private fun killRecognizer() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun clearTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }; timeoutRunnable = null
    }
}
