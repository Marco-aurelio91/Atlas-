package com.unyx.atlas.ui.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceSessionGateTest {
    @Test fun lateResultsCannotFinishNewSession() {
        val gate = VoiceSessionGate()
        val old = gate.begin()
        val current = gate.begin()
        assertFalse(gate.accepts(old))
        assertFalse(gate.finish(old))
        assertTrue(gate.accepts(current))
        assertTrue(gate.finish(current))
    }
    @Test fun resultAndErrorCannotCompleteTwice() {
        val gate = VoiceSessionGate()
        val token = gate.begin()
        assertTrue(gate.finish(token))
        assertFalse(gate.finish(token))
        assertFalse(gate.accepts(token))
    }
    @Test fun cancellationInvalidatesPendingSpeechAndInputCallbacks() {
        val gate = VoiceSessionGate()
        val token = gate.begin()
        gate.cancel()
        assertFalse(gate.finish(token))
        assertFalse(gate.accepts(token))
        assertTrue(gate.accepts(gate.begin()))
    }
}
