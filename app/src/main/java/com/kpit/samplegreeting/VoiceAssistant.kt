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

        val CONFIRM_LINES = listOf(
            "Sure, opening it now.",
            "Got it, starting it for you.",
            "On it. Opening now."
        )

        val DECLINE_LINES = listOf(
            "Alright, I'll leave it for now.",
            "No problem, maybe next time.",
            "Okay, skipping it for now."
        )

        val RETRY_LINES = listOf(
            "Sorry, I didn't catch that. Would you like me to open it? Just say yes or no.",
            "I didn't hear you clearly. Shall I open it? You can just say yes or no."
        )

        val CLOSE_LINES = listOf(
            "No worries, I'll leave it for now.",
            "That's okay, you can always tap the button if you'd like."
        )
    }

    enum class VoiceState {
        IDLE,
        SPEAKING,
        LISTENING,
        RETRYING,
        WAITING_TAP
    }

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val audioMgr = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var ttsReady = false
    private var isActive = false
    private var retryCount = 0
    private var gen = 0
    private var timeoutRunnable: Runnable? = null
    private var speakOnlyMode = false

    private var customYes: (() -> Unit)? = null
    private var customNo: (() -> Unit)? = null
    private var customConfirmLine: String? = null
    private var customDeclineLine: String? = null
    private var customRetryLine: String? = null

    init {
        initTTS()
        detectRecognizer()
    }

    // ══════════════════════════════════════
    // RECOGNIZER DETECTION
    // ══════════════════════════════════════

    private fun detectRecognizer() {
        val services = activity.packageManager.queryIntentServices(
            Intent("android.speech.RecognitionService"), 0
        )
        Log.i(TAG, "RecognitionService count: ${services.size}")
        if (services.isNotEmpty()) {
            services.forEach {
                Log.i(TAG, "  Found: ${it.serviceInfo.packageName}/${it.serviceInfo.name}")
            }
            speakOnlyMode = false
            Log.i(TAG, "Mode: FULL VOICE (speak + listen)")
        } else {
            speakOnlyMode = true
            Log.i(TAG, "Mode: SPEAK-ONLY (TTS speaks, user taps buttons)")
        }
    }

    // ══════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════

    fun askQuestion(voiceText: String) {
        customYes = null
        customNo = null
        customConfirmLine = null
        customDeclineLine = null
        customRetryLine = null
        startFlowWhenReady(voiceText)
    }

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

    fun speakMessage(text: String) {
        if (!ttsReady) {
            waitForTTS { speakMessage(text) }
            return
        }
        stopEverything()
        gen++
        isActive = true
        Log.i(TAG, "Message (gen=$gen)")
        doSpeak(text, "message", gen)
    }

    fun cancel() {
        if (!isActive) return
        Log.i(TAG, "Cancelled")
        stopEverything()
        onStateChange(VoiceState.IDLE)
    }

    fun isRunning(): Boolean = isActive

    fun isSpeakOnly(): Boolean = speakOnlyMode

    fun destroy() {
        stopEverything()
        tts?.shutdown()
        tts = null
    }

    // ══════════════════════════════════════
    // TTS WAIT
    // ══════════════════════════════════════

    private fun waitForTTS(attempt: Int = 0, action: () -> Unit) {
        if (ttsReady) {
            action()
            return
        }
        if (attempt >= 10) {
            Log.i(TAG, "TTS not ready after 5s, giving up")
            return
        }
        Log.i(TAG, "TTS not ready, retry ${attempt + 1}/10...")
        handler.postDelayed({ waitForTTS(attempt + 1, action) }, 500)
    }

    private fun startFlowWhenReady(voiceText: String) {
        if (ttsReady) {
            startFlow(voiceText)
        } else {
            waitForTTS { startFlow(voiceText) }
        }
    }

    private fun startFlow(voiceText: String) {
        if (!ttsReady) return
        stopEverything()
        gen++
        isActive = true
        retryCount = 0
        val mode = if (speakOnlyMode) "SPEAK-ONLY" else "FULL"
        Log.i(TAG, "Question (gen=$gen) [$mode]")
        doSpeak(voiceText, "ask", gen)
    }

    // ══════════════════════════════════════
    // TTS ENGINE
    // ══════════════════════════════════════

    private fun initTTS() {
        Log.i(TAG, "Initializing TTS...")
        tts = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langResult = tts?.setLanguage(Locale.US)
                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.i(TAG, "English US unavailable, trying UK")
                    val ukResult = tts?.setLanguage(Locale.UK)
                    if (ukResult == TextToSpeech.LANG_MISSING_DATA ||
                        ukResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        Log.i(TAG, "English not available at all")
                        return@TextToSpeech
                    }
                }
                tts?.setSpeechRate(0.95f)
                ttsReady = true
                Log.i(TAG, "TTS ready (engine: ${tts?.defaultEngine})")

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        handler.post {
                            if (isActive) onStateChange(VoiceState.SPEAKING)
                        }
                    }

                    override fun onDone(id: String?) {
                        val parts = (id ?: "").split("_")
                        val type = parts.getOrElse(0) { "" }
                        val g = parts.getOrElse(1) { "0" }.toIntOrNull() ?: 0
                        handler.post { ttsDone(type, g) }
                    }

                    override fun onError(id: String?) {
                        val parts = (id ?: "").split("_")
                        val type = parts.getOrElse(0) { "" }
                        val g = parts.getOrElse(1) { "0" }.toIntOrNull() ?: 0
                        handler.post { ttsDone(type, g) }
                    }
                })
            } else {
                Log.i(TAG, "TTS init FAILED (status=$status)")
            }
        }
    }

    private fun doSpeak(text: String, type: String, g: Int) {
        onStateChange(VoiceState.SPEAKING)
        val uttId = "${type}_${g}"
        Log.i(TAG, "TTS speaking: $uttId")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uttId)
    }

    private fun ttsDone(type: String, g: Int) {
        if (g != gen || !isActive) return
        Log.i(TAG, "TTS done: $type (gen=$g)")

        when (type) {
            "ask", "retry" -> {
                if (speakOnlyMode) {
                    Log.i(TAG, "Speak-only: waiting for button tap")
                    onStateChange(VoiceState.WAITING_TAP)
                    clearTimeout()
                    timeoutRunnable = Runnable {
                        if (g == gen && isActive) {
                            Log.i(TAG, "Speak-only timeout")
                            isActive = false
                            onStateChange(VoiceState.IDLE)
                            onTimeout()
                        }
                    }
                    handler.postDelayed(timeoutRunnable!!, 15000)
                } else {
                    try {
                        audioMgr.abandonAudioFocus { }
                    } catch (_: Exception) {
                    }
                    Log.i(TAG, "Waiting ${TTS_TO_LISTEN_DELAY}ms before listen...")
                    handler.postDelayed({
                        if (g == gen && isActive) listen(g)
                    }, TTS_TO_LISTEN_DELAY)
                }
            }

            "confirm" -> {
                isActive = false
                onStateChange(VoiceState.IDLE)
                (customYes ?: onYes)()
            }

            "decline" -> {
                isActive = false
                onStateChange(VoiceState.IDLE)
                (customNo ?: onNo)()
            }

            "close" -> {
                isActive = false
                onStateChange(VoiceState.IDLE)
                onTimeout()
            }

            "message" -> {
                isActive = false
                onStateChange(VoiceState.IDLE)
            }
        }
    }

    // ══════════════════════════════════════
    // SPEECH RECOGNITION (full mode only)
    // ══════════════════════════════════════

    private fun listen(g: Int) {
        if (g != gen || !isActive || speakOnlyMode) return
        onStateChange(if (retryCount > 0) VoiceState.RETRYING else VoiceState.LISTENING)
        killRecognizer()

        if (retryCount > 0) {
            Log.i(TAG, "Retry cooldown 1.5s...")
            handler.postDelayed({
                if (g == gen && isActive) createAndStart(g)
            }, 1500)
        } else {
            createAndStart(g)
        }
    }

    private fun createAndStart(g: Int) {
        if (g != gen || !isActive) return

        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Log.i(TAG, "Recognizer unavailable, switching to speak-only")
            speakOnlyMode = true
            noResponse(g)
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer?.setRecognitionListener(makeListener(g))

        val silenceC = if (retryCount > 0) 5000L else 4000L
        val silenceP = if (retryCount > 0) 4500L else 3500L
        val minLen = if (retryCount > 0) 8000L else 6000L

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                minLen
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                silenceC
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                silenceP
            )
        }

        try {
            Log.i(TAG, "Recognizer start (gen=$g, retry=$retryCount)")
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.i(TAG, "startListening failed: ${e.message}")
            speakOnlyMode = true
            noResponse(g)
            return
        }

        clearTimeout()
        timeoutRunnable = Runnable {
            if (g == gen && isActive) {
                Log.i(TAG, "Listen timeout (gen=$g)")
                killRecognizer()
                noResponse(g)
            }
        }
        handler.postDelayed(timeoutRunnable!!, LISTEN_TIMEOUT_MS)
    }

    private fun makeListener(g: Int) = object : RecognitionListener {

        override fun onReadyForSpeech(p: Bundle?) {
            if (g != gen) return
            Log.i(TAG, "Listening... (gen=$g)")
        }

        override fun onBeginningOfSpeech() {
            if (g != gen) return
            Log.i(TAG, "User speaking (gen=$g)")
            clearTimeout()
            timeoutRunnable = Runnable {
                if (g == gen && isActive) {
                    Log.i(TAG, "Speech timeout (gen=$g)")
                    killRecognizer()
                    noResponse(g)
                }
            }
            handler.postDelayed(timeoutRunnable!!, SPEECH_ACTIVE_TIMEOUT_MS)
        }

        override fun onEndOfSpeech() {
            if (g != gen) return
            Log.i(TAG, "User stopped (gen=$g)")
        }

        override fun onResults(results: Bundle?) {
            if (g != gen || !isActive) return
            clearTimeout()
            val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val spoken = all?.firstOrNull()?.lowercase()?.trim() ?: ""
            Log.i(TAG, "Heard: '$spoken' (gen=$g)")
            gotResponse(spoken, g)
        }

        override fun onError(error: Int) {
            if (g != gen || !isActive) return
            clearTimeout()
            val name = when (error) {
                1 -> "NETWORK_TIMEOUT"
                2 -> "NETWORK"
                3 -> "AUDIO"
                4 -> "SERVER"
                5 -> "CLIENT"
                6 -> "SPEECH_TIMEOUT"
                7 -> "NO_MATCH"
                8 -> "BUSY"
                9 -> "NO_PERMISSION"
                11 -> "TOO_MANY_REQUESTS"
                else -> "UNKNOWN($error)"
            }
            Log.i(TAG, "Error: $name ($error) gen=$g")
            if (error == 9) {
                speakOnlyMode = true
                Log.i(TAG, "Switched to speak-only (no permission)")
            }
            noResponse(g)
        }

        override fun onPartialResults(r: Bundle?) {
            if (g != gen) return
            val partial = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!partial.isNullOrEmpty()) {
                Log.i(TAG, "Partial: $partial (gen=$g)")
            }
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    // ══════════════════════════════════════
    // RESPONSE HANDLING
    // ══════════════════════════════════════

    private fun gotResponse(spoken: String, g: Int) {
        if (g != gen || !isActive) return
        val intent = detectIntent(spoken)
        Log.i(TAG, "Intent: $intent (gen=$g)")
        when (intent) {
            "yes" -> doSpeak(
                customConfirmLine ?: CONFIRM_LINES.random(),
                "confirm", g
            )

            "no" -> doSpeak(
                customDeclineLine ?: DECLINE_LINES.random(),
                "decline", g
            )

            else -> noResponse(g)
        }
    }

    private fun noResponse(g: Int) {
        if (g != gen || !isActive) return

        if (speakOnlyMode) {
            Log.i(TAG, "Speak-only: waiting for button tap")
            onStateChange(VoiceState.WAITING_TAP)
            clearTimeout()
            timeoutRunnable = Runnable {
                if (g == gen && isActive) {
                    isActive = false
                    onStateChange(VoiceState.IDLE)
                    onTimeout()
                }
            }
            handler.postDelayed(timeoutRunnable!!, 15000)
            return
        }

        if (retryCount == 0) {
            retryCount = 1
            Log.i(TAG, "Miss -> retry (gen=$g)")
            doSpeak(
                customRetryLine ?: RETRY_LINES.random(),
                "retry", g
            )
        } else {
            Log.i(TAG, "Miss again -> close (gen=$g)")
            doSpeak(CLOSE_LINES.random(), "close", g)
        }
    }

    private fun detectIntent(s: String): String {
        val lower = s.lowercase().trim()
        if (lower.isEmpty()) return "unclear"
        if (NEGATIVE.any { lower.contains(it) }) return "no"
        if (POSITIVE.any { lower.contains(it) }) return "yes"
        return "unclear"
    }

    // ══════════════════════════════════════
    // CLEANUP
    // ══════════════════════════════════════

    private fun stopEverything() {
        isActive = false
        tts?.stop()
        clearTimeout()
        killRecognizer()
        handler.removeCallbacksAndMessages(null)
    }

    private fun killRecognizer() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun clearTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }
}
