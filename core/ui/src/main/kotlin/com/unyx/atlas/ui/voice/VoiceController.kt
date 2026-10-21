package com.unyx.atlas.ui.voice

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Owns single-shot system recognition and TTS. All state and callbacks run on main. */
class VoiceController(context: Context) {
    private val appContext = context.applicationContext
    private val inputHandler = Handler(Looper.getMainLooper())
    private val speechHandler = Handler(Looper.getMainLooper())
    private val inputGate = VoiceSessionGate()
    private val speechGate = VoiceSessionGate()
    private var recognizer: SpeechRecognizer? = null
    private var inputEnd: (() -> Unit)? = null
    private var stopCurrentListening: (() -> Unit)? = null
    private var pauseCurrentListening: (() -> Unit)? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechCancel: (() -> Unit)? = null
    private var destroyed = false
    private var bargeInWatcher: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun isOnDeviceRecognitionAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)

    data class SpeechVoice(val name: String, val label: String, val requiresNetwork: Boolean)

    /** Loads installed Brazilian Portuguese voices from the system TTS engine. */
    fun availablePortugueseVoices(onResult: (List<SpeechVoice>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val current = tts
        if (current != null && ttsReady) {
            onResult(portugueseVoices(current))
            return
        }
        current?.shutdown()
        ttsReady = false
        try {
            tts = TextToSpeech(appContext) { status ->
                speechHandler.post {
                    ttsReady = status == TextToSpeech.SUCCESS
                    onResult(if (ttsReady) tts?.let(::portugueseVoices).orEmpty() else emptyList())
                }
            }
        } catch (_: RuntimeException) {
            onResult(emptyList())
        }
    }

    private fun portugueseVoices(engine: TextToSpeech): List<SpeechVoice> = engine.voices.orEmpty()
        .filter { it.locale.language == "pt" }
        .sortedWith(compareBy<android.speech.tts.Voice>({ it.locale.country != "BR" }, { it.name }))
        .map { voice ->
            val region = voice.locale.displayCountry.takeIf(String::isNotBlank)
            SpeechVoice(voice.name, listOfNotNull(voice.locale.displayLanguage, region).joinToString(" · "), voice.isNetworkConnectionRequired)
        }

    /** True when audio is routed somewhere the mic can't also hear — wired/Bluetooth/USB headset.
     * Barge-in (listening for the user while Atlas is still talking) only makes sense here: on
     * the speaker, the mic would just hear Atlas's own voice and "detect" a barge-in on every
     * reply, with no acoustic echo cancellation in this pipeline to tell the difference. */
    fun isHeadsetConnected(): Boolean {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        }.getOrDefault(false)
    }

    fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onAmplitude: (Float) -> Unit,
        // [recoverable] is true for "you didn't say anything I could make out" (no match / timed
        // out waiting for speech) — the kind of silence that's a normal part of a back-and-forth
        // conversation, not a real failure. False covers permissions, hardware, network, and
        // similar: something a caller should actually surface and stop on.
        onError: (message: String, recoverable: Boolean) -> Unit,
        onEnd: () -> Unit,
        onPhase: (VoiceInputPhase) -> Unit = {},
        onPaused: (String) -> Unit = {},
        preferOffline: Boolean = true,
        endpointSilenceMillis: Int = 1500,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (destroyed) return
        stopListening()
        inputEnd = { onPhase(VoiceInputPhase.IDLE); onEnd() }
        fun finish(token: Long, text: String? = null, error: String? = null, recoverable: Boolean = false, paused: Boolean = false) {
            if (!inputGate.finish(token)) return
            inputHandler.removeCallbacksAndMessages(null)
            stopCurrentListening = null
            pauseCurrentListening = null
            releaseRecognizer()
            val ended = inputEnd
            inputEnd = null
            ended?.invoke()
            if (paused) onPaused(text.orEmpty())
            else if (error != null) onError(error, recoverable)
            if (!paused && !text.isNullOrBlank()) onFinal(text)
        }
        fun attempt(retries: Int) {
            val token = inputGate.begin()
            var heardSpeech = false
            var pauseRequested = false
            onPhase(VoiceInputPhase.PREPARING)
            fun watchdog(delay: Long) {
                inputHandler.removeCallbacksAndMessages(null)
                inputHandler.postDelayed(
                    { finish(token, error = "A escuta demorou demais. Tente novamente.", recoverable = true) },
                    delay,
                )
            }
            try {
                // On-device recognition (API 31+, when the language pack is downloaded) doesn't
                // round-trip audio over the network — faster, and it keeps working with no or
                // spotty connectivity. Falls straight back to the regular recognizer otherwise.
                val onDevice = preferOffline && isOnDeviceRecognitionAvailable()
                val engine = if (onDevice) {
                    runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }
                        .getOrElse { SpeechRecognizer.createSpeechRecognizer(appContext) }
                } else {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                }
                recognizer = engine
                engine.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (!inputGate.accepts(token)) return
                        onPhase(VoiceInputPhase.LISTENING)
                        watchdog(60_000)
                    }
                    override fun onBeginningOfSpeech() {
                        if (inputGate.accepts(token)) heardSpeech = true
                    }
                    override fun onRmsChanged(rmsdB: Float) {
                        if (inputGate.accepts(token)) onAmplitude(rmsdB)
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        if (!inputGate.accepts(token)) return
                        onPhase(VoiceInputPhase.TRANSCRIBING)
                        watchdog(10_000)
                    }
                    override fun onError(error: Int) {
                        if (!inputGate.accepts(token)) return
                        if (pauseRequested) {
                            finish(token, paused = true)
                            return
                        }
                        val transient = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                            error == SpeechRecognizer.ERROR_SERVER
                        if (transient && retries == 0 && !heardSpeech) {
                            inputGate.cancel()
                            inputHandler.removeCallbacksAndMessages(null)
                            releaseRecognizer()
                            onPhase(VoiceInputPhase.PREPARING)
                            inputHandler.postDelayed({ if (!destroyed && inputEnd != null) attempt(1) }, 600)
                        } else {
                            // Just plain silence — nothing heard at all, or heard but not matched
                            // to anything — is a normal conversational pause, not a real failure.
                            val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            finish(token, error = speechErrorMessage(error), recoverable = silence)
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                        if (pauseRequested) {
                            finish(token, text = text, paused = true)
                            return
                        }
                        // CONFIDENCE_SCORES used to gate a low-confidence result behind "não tenho
                        // certeza, pode repetir?" instead of passing it through — removed after a
                        // real user hit this on EVERY utterance regardless of how clearly they
                        // spoke: this score is calibrated wildly differently across recognizer
                        // backends (especially on-device ones, see isOnDeviceRecognitionAvailable
                        // above), and at least one reports a near-zero value unconditionally. A
                        // wrong transcript downstream (Claude/Gemini misunderstanding, or
                        // LocalCommandRouter's own narrow regexes simply not matching) already
                        // fails gracefully on its own — that's a far better failure mode than
                        // rejecting correct speech outright on every single turn.
                        val error = if (text.isBlank()) "Não entendi, pode repetir?" else null
                        finish(token, text.takeIf { error == null }, error, recoverable = true)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!inputGate.accepts(token)) return
                        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!text.isNullOrBlank()) { heardSpeech = true; onPartial(text) }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                stopCurrentListening = {
                    if (inputGate.accepts(token)) {
                        onPhase(VoiceInputPhase.TRANSCRIBING)
                        watchdog(10_000)
                        runCatching { engine.stopListening() }
                    }
                }
                pauseCurrentListening = {
                    if (inputGate.accepts(token)) {
                        pauseRequested = true
                        onPhase(VoiceInputPhase.TRANSCRIBING)
                        watchdog(10_000)
                        runCatching { engine.stopListening() }
                    }
                }
                watchdog(10_000)
                engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                    // Left unset, endpointing (deciding "the user has stopped talking") is
                    // whatever the OEM/OS default is — inconsistent across devices and the
                    // likeliest cause of getting cut off mid-sentence on a normal pause. A ~1.5s
                    // silence window is generous enough for a breath without feeling laggy.
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, endpointSilenceMillis.toLong())
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, endpointSilenceMillis.toLong())
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15_000L)
                })
            } catch (_: SecurityException) {
                finish(token, error = "Preciso de permissão de microfone.")
            } catch (_: RuntimeException) {
                finish(token, error = "Não foi possível iniciar o microfone. Tente novamente.")
            }
        }
        attempt(0)
    }

    private fun releaseRecognizer() {
        val old = recognizer
        recognizer = null
        runCatching { old?.cancel() }
        runCatching { old?.destroy() }
    }

    fun stopListening() {
        inputGate.cancel()
        inputHandler.removeCallbacksAndMessages(null)
        stopCurrentListening = null
        pauseCurrentListening = null
        releaseRecognizer()
        val ended = inputEnd
        inputEnd = null
        ended?.invoke()
    }

    /** Requests a final result from the recognizer rather than discarding the current utterance. */
    fun finishListening() {
        check(Looper.myLooper() == Looper.getMainLooper())
        stopCurrentListening?.invoke()
    }

    /** Ends the current recognition segment while preserving its final partial result for resume. */
    fun pauseListening() {
        check(Looper.myLooper() == Looper.getMainLooper())
        pauseCurrentListening?.invoke()
    }

    fun speak(
        text: String,
        rate: Float = 1.1f,
        // Only takes effect with a headset connected (see [isHeadsetConnected]) — silently a
        // no-op otherwise, so callers don't need to check the headset themselves.
        allowBargeIn: Boolean = false,
        onBargeIn: () -> Unit = {},
        onError: (String) -> Unit = {},
        onCancelled: () -> Unit = {},
        voiceName: String = "",
        onDone: () -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (destroyed) return
        stopSpeaking()
        val token = speechGate.begin()
        speechCancel = onCancelled
        val spoken = stripEmoji(text).trim()
        fun finish(error: String? = null) {
            if (!speechGate.finish(token)) return
            stopBargeInWatcher()
            speechHandler.removeCallbacksAndMessages(null)
            speechCancel = null
            if (error == null) onDone() else {
                tts?.stop()
                onError(error)
            }
        }
        fun play(engine: TextToSpeech) {
            if (!speechGate.accepts(token)) return
            if (spoken.isBlank()) { finish(); return }
            val language = engine.setLanguage(Locale("pt", "BR"))
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                finish("Instale uma voz em português nas configurações de texto para fala.")
                return
            }
            if (voiceName.isNotBlank()) engine.voices?.firstOrNull { it.name == voiceName }?.let(engine::setVoice)
            engine.setSpeechRate(rate)
            val chunks = splitForSpeech(spoken, TextToSpeech.getMaxSpeechInputLength() - 1)
            val finalId = "atlas_${token}_${chunks.lastIndex}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    speechHandler.post { if (utteranceId == finalId) finish() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    speechHandler.post {
                        if (utteranceId?.startsWith("atlas_${token}_") == true) finish("Não foi possível reproduzir a resposta.")
                    }
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    speechHandler.post {
                        if (utteranceId?.startsWith("atlas_${token}_") == true && speechGate.accepts(token)) stopSpeaking()
                    }
                }
            })
            speechHandler.removeCallbacksAndMessages(null)
            speechHandler.postDelayed({ finish("A reprodução de voz não terminou. Tente novamente.") },
                (spoken.length * 150L / rate.coerceAtLeast(.5f)).toLong().coerceIn(30_000L, 900_000L))
            if (allowBargeIn) startBargeInWatcher(token, onBargeIn)
            chunks.forEachIndexed { index, chunk ->
                if (!speechGate.accepts(token)) return
                if (engine.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                        null, "atlas_${token}_$index") == TextToSpeech.ERROR) {
                    finish("Não foi possível reproduzir a resposta.")
                    return
                }
            }
        }
        val existing = tts
        if (existing != null && ttsReady) { play(existing); return }
        existing?.shutdown()
        ttsReady = false
        speechHandler.postDelayed({ finish("O serviço de voz não respondeu.") }, 10_000)
        try {
            tts = TextToSpeech(appContext) { status ->
                speechHandler.post {
                    if (!speechGate.accepts(token)) return@post
                    ttsReady = status == TextToSpeech.SUCCESS
                    val engine = tts
                    if (ttsReady && engine != null) play(engine)
                    else finish("Não foi possível iniciar a voz do ATLAS.")
                }
            }
        } catch (_: RuntimeException) { finish("Não foi possível iniciar a voz do ATLAS.") }
    }

    fun stopSpeaking() {
        stopBargeInWatcher()
        speechGate.cancel()
        speechHandler.removeCallbacksAndMessages(null)
        val cancelled = speechCancel
        speechCancel = null
        tts?.stop()
        cancelled?.invoke()
    }

    // A second, disposable SpeechRecognizer whose only job is noticing the user has started
    // talking while Atlas is still speaking — its transcript is thrown away; onBeginningOfSpeech
    // alone is the signal. Only ever started with a headset connected (see [isHeadsetConnected]),
    // so there's no TTS-into-mic echo to confuse it with a real interruption.
    private fun startBargeInWatcher(token: Long, onBargeIn: () -> Unit) {
        if (!isHeadsetConnected() || !isAvailable()) return
        val watcher = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull() ?: return
        bargeInWatcher = watcher
        var triggered = false
        watcher.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                if (triggered || !speechGate.accepts(token)) return
                triggered = true
                stopSpeaking()
                onBargeIn()
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { stopBargeInWatcher() }
            override fun onResults(results: Bundle?) { stopBargeInWatcher() }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching {
            watcher.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        }.onFailure { stopBargeInWatcher() }
    }

    private fun stopBargeInWatcher() {
        val watcher = bargeInWatcher
        bargeInWatcher = null
        runCatching { watcher?.cancel() }
        runCatching { watcher?.destroy() }
    }

    fun destroy() {
        destroyed = true
        stopListening()
        stopSpeaking()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}

private fun stripEmoji(text: String): String {
    val builder = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        if (!isEmojiCodePoint(codePoint)) builder.appendCodePoint(codePoint)
        i += Character.charCount(codePoint)
    }
    // Collapse whatever whitespace an emoji's removal left behind (e.g. a trailing " 🎙️").
    return builder.toString().replace(Regex(" {2,}"), " ").trim()
}

private fun isEmojiCodePoint(codePoint: Int): Boolean = when (codePoint) {
    in 0x1F300..0x1FAFF, in 0x2600..0x27BF, in 0x1F1E6..0x1F1FF,
    in 0x2300..0x23FF, in 0x2B00..0x2BFF, 0xFE0F, 0x200D -> true
    else -> false
}

private val SENTENCE_BOUNDARY = Regex("(?<=[.!?…])\\s+")

/** Packs whole sentences into chunks up to [maxLen] instead of cutting at a fixed character
 * count, so a chunk boundary — which TTS treats as a hard pause — lands between sentences
 * rather than mid-word or mid-sentence. Only matters for replies long enough to need more than
 * one chunk at all (getMaxSpeechInputLength() is ~4000 chars); short ones are untouched. */
private fun splitForSpeech(text: String, maxLen: Int): List<String> {
    if (text.length <= maxLen) return listOf(text)
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    for (sentence in SENTENCE_BOUNDARY.split(text)) {
        val candidate = if (current.isEmpty()) sentence else "$current $sentence"
        when {
            candidate.length <= maxLen -> { current.setLength(0); current.append(candidate) }
            sentence.length <= maxLen -> {
                if (current.isNotEmpty()) chunks.add(current.toString())
                current.setLength(0); current.append(sentence)
            }
            else -> {
                // A single sentence longer than maxLen on its own — rare, but still needs a
                // hard split as a last resort so it can be spoken at all.
                if (current.isNotEmpty()) { chunks.add(current.toString()); current.setLength(0) }
                chunks.addAll(sentence.chunked(maxLen))
            }
        }
    }
    if (current.isNotEmpty()) chunks.add(current.toString())
    return chunks
}

private fun speechErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi, pode repetir?"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi nada."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Preciso de permissão de microfone."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sem conexão para reconhecer voz."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O microfone está ocupado. Tente novamente."
    SpeechRecognizer.ERROR_SERVER -> "O serviço de reconhecimento não respondeu."
    SpeechRecognizer.ERROR_AUDIO -> "Erro no microfone."
    else -> "Não consegui ouvir agora."
}
