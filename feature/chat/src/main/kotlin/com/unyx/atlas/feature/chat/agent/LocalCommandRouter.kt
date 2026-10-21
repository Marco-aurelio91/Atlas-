package com.unyx.atlas.feature.chat.agent

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/** One command [LocalCommandRouter] recognized and already executed. [toolName] matches one of
 * [AtlasToolExecutor]'s own tool names so the UI renders it exactly like an AI-dispatched call of
 * the same tool would (see ChatScreen's actionCardSpec/executingLabel). */
data class LocalCommandResult(val toolName: String, val resultText: String)

/**
 * A local fast-path for the handful of device commands clear enough to never need an LLM's
 * judgment at all — "abre o whatsapp", "volume 20%", "abaixa o brilho". Tried BEFORE either chat
 * backend (see ChatViewModel/AssistantViewModel's own dispatch) so these specific, unambiguous
 * actions keep working even if Claude *and* Gemini are both down — the real gap a user hit: an
 * Anthropic billing outage took down even "abaixa o volume". It's an addition, not a replacement
 * for natural-language understanding: only the narrowest, least ambiguous phrasings match at all;
 * anything else (including a plain question like "qual o volume atual?") falls straight through to
 * [AtlasChatClient] unchanged. Never attempted when there's also an attached image — vision needs a
 * real model, there's no local path for that.
 */
@Singleton
class LocalCommandRouter @Inject constructor(
    private val toolExecutor: AtlasToolExecutor,
    @ApplicationContext private val appContext: Context,
) {
    /** [lastAssistantReply] lets a bare follow-up with no subject of its own ("coloca em 15",
     * "abaixa mais um pouco") inherit whichever setting the turn right before it was about — e.g.
     * "ajusta o volume" then "coloca em 15" — without that, "coloca em 15" alone has no subject at
     * all and had to go through the full AI pipeline just to reconnect it to "volume", defeating
     * the entire point of a local fast-path for something this unambiguous in context. Only trusted
     * when [lastAssistantReply] is literally one of AtlasToolExecutor's own success templates (see
     * the startsWith checks below) — never a free-form AI sentence that merely happens to mention
     * "volume" in passing (e.g. "o volume de vendas caiu"). */
    suspend fun tryHandle(rawText: String, lastAssistantReply: String? = null): LocalCommandResult? {
        val text = rawText.trim()
        if (text.isBlank()) return null
        val normalized = normalizeForMatch(text)

        // Never let a one-shot device shortcut consume a recurring request. The conversational
        // automation tool needs the whole request (trigger + action) to create a saved routine.
        if (AUTOMATION_MARKERS.any(normalized::contains) ||
            (CHARGER_OR_HEADPHONE_WORD.containsMatchIn(normalized) &&
                (VOLUME_WORD.containsMatchIn(normalized) || BRIGHTNESS_WORD.containsMatchIn(normalized)))
        ) return null

        if (NETWORK_PANEL_MARKERS.any(normalized::contains)) {
            return LocalCommandResult(
                "open_internet_panel",
                toolExecutor.execute("open_internet_panel", JSONObject()),
            )
        }

        OPEN_APP_REGEX.find(text)?.let { match ->
            val appName = match.groupValues[1].trim().trimEnd('.', '!', '?')
            // A sane upper bound on length — past this it reads like a sentence Atlas should
            // actually reason about ("abre uma exceção na minha rotina de hoje..."), not a bare
            // app name, so it's better left to the AI than forced through open_app's fuzzy match.
            if (appName.isNotBlank() && appName.length <= 40) {
                val result = toolExecutor.execute("open_app", JSONObject().put("nome_app", appName))
                return LocalCommandResult("open_app", result)
            }
        }

        val mentionsVolume = VOLUME_WORD.containsMatchIn(normalized)
        val mentionsBrightness = BRIGHTNESS_WORD.containsMatchIn(normalized)
        val impliedTopic = if (!mentionsVolume && !mentionsBrightness) {
            when {
                lastAssistantReply == null -> null
                lastAssistantReply.startsWith("Ajustei o volume para") -> "volume"
                lastAssistantReply.startsWith("Ajustei o brilho para") -> "brilho"
                else -> null
            }
        } else {
            null
        }

        if (mentionsVolume || impliedTopic == "volume") {
            resolveLevel(normalized, { currentVolumePercent(streamTypeFor(normalized)) }, impliedTopic == "volume")?.let { nivel ->
                val input = JSONObject().put("nivel", nivel)
                streamNameFor(normalized)?.let { input.put("tipo", it) }
                val result = toolExecutor.execute("set_volume", input)
                return LocalCommandResult("set_volume", result)
            }
        }

        if (mentionsBrightness || impliedTopic == "brilho") {
            resolveLevel(normalized, { currentBrightnessPercent() }, impliedTopic == "brilho")?.let { nivel ->
                val result = toolExecutor.execute("set_brightness", JSONObject().put("nivel", nivel))
                return LocalCommandResult("set_brightness", result)
            }
        }

        return null
    }

    /** An explicit number in the phrase wins outright (absolute target, e.g. "volume 20%");
     * otherwise an up/down word nudges the CURRENT level (read via [currentLevel]) by a fixed
     * step. Null when neither is present — e.g. "qual o volume atual?" names the word "volume"
     * but asks a question, not a command, and is correctly left for the AI to answer instead. */
    private fun resolveLevel(
        normalized: String,
        currentLevel: () -> Int,
        allowContextualNumber: Boolean = false,
    ): Int? {
        val explicit = LEVEL_AFTER_SETTING_REGEX.find(normalized)
            ?: LEVEL_BEFORE_SETTING_REGEX.find(normalized)
            ?: if (allowContextualNumber) CONTEXTUAL_LEVEL_REGEX.find(normalized) else null
        explicit?.let { return it.groupValues[1].toInt().coerceIn(0, 100) }
        return when {
            UP_WORDS.any { normalized.contains(it) } -> (currentLevel() + LEVEL_STEP).coerceIn(0, 100)
            DOWN_WORDS.any { normalized.contains(it) } -> (currentLevel() - LEVEL_STEP).coerceIn(0, 100)
            else -> null
        }
    }

    private fun streamTypeFor(normalized: String): Int = when {
        RING_WORDS.any { normalized.contains(it) } -> AudioManager.STREAM_RING
        CALL_WORDS.any { normalized.contains(it) } -> AudioManager.STREAM_VOICE_CALL
        ALARM_WORDS.any { normalized.contains(it) } -> AudioManager.STREAM_ALARM
        else -> AudioManager.STREAM_MUSIC
    }

    /** The exact "tipo" string set_volume's own schema expects — must match AtlasToolExecutor's
     * "set_volume" branch's lowercase() cases verbatim. Null defaults to media there too. */
    private fun streamNameFor(normalized: String): String? = when {
        RING_WORDS.any { normalized.contains(it) } -> "toque"
        CALL_WORDS.any { normalized.contains(it) } -> "chamada"
        ALARM_WORDS.any { normalized.contains(it) } -> "alarme"
        else -> null
    }

    private fun currentVolumePercent(streamType: Int): Int {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(streamType)
        if (max <= 0) return 0
        return (audioManager.getStreamVolume(streamType) * 100) / max
    }

    private fun currentBrightnessPercent(): Int {
        val raw = runCatching {
            Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (raw * 100 / 255).coerceIn(0, 100)
    }

    private companion object {
        // One nudge per bare "aumenta"/"abaixa" with no number — big enough to be noticeable in a
        // single tap, small enough not to blow past a comfortable level by accident.
        const val LEVEL_STEP = 20

        val OPEN_APP_REGEX = Regex("""^(?:abre|abra|abrir)\s+(?:o|a|os|as)?\s*(.+)$""", RegexOption.IGNORE_CASE)
        val VOLUME_WORD = Regex("""\bvolume\b""")
        val BRIGHTNESS_WORD = Regex("""\bbrilho\b""")
        val LEVEL_AFTER_SETTING_REGEX = Regex("""\b(?:volume|brilho)\s+(?:(?:para|em)\s+)?(\d{1,3})\s*%?\b""")
        val LEVEL_BEFORE_SETTING_REGEX = Regex("""\b(\d{1,3})\s*%\s*(?:de\s+)?(?:volume|brilho)\b""")
        val CONTEXTUAL_LEVEL_REGEX = Regex("""\b(?:coloca|coloque|define|defina|ajusta|ajuste)\s+(?:em|para)\s+(\d{1,3})\s*%?\b""")
        val UP_WORDS = listOf("aumenta", "aumentar", "sobe", "subir")
        val DOWN_WORDS = listOf("abaixa", "abaixar", "diminui", "diminuir", "baixa", "baixar", "desce", "descer")
        val RING_WORDS = listOf("toque", "campainha")
        val CALL_WORDS = listOf("chamada", "ligacao")
        val ALARM_WORDS = listOf("alarme")
        val AUTOMATION_MARKERS = listOf("sempre que", "toda vez", "toda vez que", "todo dia", "diariamente", "automacao", "automaticamente", "rotina")
        val CHARGER_OR_HEADPHONE_WORD = Regex("\\b(carregador|carregar|fone|fone de ouvido|headset)\\b")
        val NETWORK_PANEL_MARKERS = listOf("abre painel de internet", "abrir painel de internet", "abra painel de internet", "abre painel internet", "abrir painel internet", "abre configuracoes de internet", "abrir configuracoes de internet")
    }
}

/** Lowercases and strips accents (NFD-decompose, drop combining marks) so trigger words match
 * regardless of how the user typed them ("Não" / "nao" / "não" all collapse the same way) —
 * app names pulled out via [LocalCommandRouter.OPEN_APP_REGEX] are kept un-normalized, since
 * AppRepository's own matching already handles that. */
private fun normalizeForMatch(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
