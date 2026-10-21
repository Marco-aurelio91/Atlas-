package com.unyx.atlas.feature.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted voice preferences — plain SharedPreferences, matching this codebase's existing
 * lightweight persistence style used by AutomationStore.
 *
 * Closes three real gaps the original [VoiceController]/[ChatViewModel] pairing had: every
 * voice-originated reply was *always* spoken aloud with no way to turn that off, at a hardcoded
 * speech rate, and with no way to stop Atlas mid-sentence once it started.
 */
@Singleton
class VoiceSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("atlas_voice", Context.MODE_PRIVATE)

    /** Whether a voice-originated message's reply gets spoken aloud at all. Default true (matches
     * the behavior before this setting existed) — this is an opt-out, not a new default. */
    var speakReplies: Boolean
        get() = prefs.getBoolean(KEY_SPEAK, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEAK, value).apply()

    /** When true, Atlas automatically starts listening again right after finishing a spoken reply
     * — turns "tap mic, talk, get answer, tap mic again" into an actual back-and-forth
     * conversation. Default false: an always-re-listening mic is a meaningfully different
     * interaction model than this app has shipped so far, so it's opt-in, not a silent behavior
     * change for existing voice users. */
    var handsFreeMode: Boolean
        get() = prefs.getBoolean(KEY_HANDS_FREE, false)
        set(value) = prefs.edit().putBoolean(KEY_HANDS_FREE, value).apply()

    /** TextToSpeech speech rate, 1.0 = engine default. Default 1.1 matches the original master
     * plan's own reasoning (ATLAS_Plano_de_Desenvolvimento.md section 7.2: "1.1x — ligeiramente
     * acima do padrão, mais natural para respostas curtas"), now a real user-adjustable preference
     * instead of a hardcoded constant nothing could change. */
    var speechRate: Float
        get() = prefs.getFloat(KEY_RATE, 1.1f)
        set(value) = prefs.edit().putFloat(KEY_RATE, value).apply()

    /** Voice capture normally sends the recognized text immediately. When enabled, the transcript
     * stays in the composer so the user can correct it first. */
    var confirmTranscript: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_TRANSCRIPT, false)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_TRANSCRIPT, value).apply()

    /** Silence required before the recognizer considers a phrase complete. */
    var endpointSilenceMillis: Int
        get() = prefs.getInt(KEY_ENDPOINT_SILENCE, 1500).coerceIn(800, 3000)
        set(value) = prefs.edit().putInt(KEY_ENDPOINT_SILENCE, value.coerceIn(800, 3000)).apply()

    /** Ask the system speech service to prefer on-device recognition where available. */
    var preferOfflineRecognition: Boolean
        get() = prefs.getBoolean(KEY_PREFER_OFFLINE, true)
        set(value) = prefs.edit().putBoolean(KEY_PREFER_OFFLINE, value).apply()

    /** Empty means use the engine's default Portuguese voice. */
    var speechVoiceName: String
        get() = prefs.getString(KEY_VOICE_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_VOICE_NAME, value).apply()

    private companion object {
        const val KEY_SPEAK = "speak_replies"
        const val KEY_HANDS_FREE = "hands_free"
        const val KEY_RATE = "speech_rate"
        const val KEY_CONFIRM_TRANSCRIPT = "confirm_transcript"
        const val KEY_ENDPOINT_SILENCE = "endpoint_silence_millis"
        const val KEY_PREFER_OFFLINE = "prefer_offline_recognition"
        const val KEY_VOICE_NAME = "speech_voice_name"
    }
}
