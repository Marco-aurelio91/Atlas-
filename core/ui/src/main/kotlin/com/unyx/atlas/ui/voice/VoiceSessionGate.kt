package com.unyx.atlas.ui.voice

/** Main-thread ownership of asynchronous callbacks; completion consumes the token once. */
internal class VoiceSessionGate {
    private var serial = 0L
    private var active: Long? = null
    fun begin(): Long = (++serial).also { active = it }
    fun accepts(token: Long): Boolean = active == token
    fun finish(token: Long): Boolean {
        if (!accepts(token)) return false
        active = null
        return true
    }
    fun cancel() { active = null }
}

enum class VoiceInputPhase { IDLE, PREPARING, LISTENING, TRANSCRIBING }
