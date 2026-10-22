package com.unyx.atlas.feature.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiQuotaDetectionTest {

    @Test
    fun `recognizes the gateway's daily limit response`() {
        assertThat(isAiQuotaExceeded("Claude API HTTP 429: ai_quota_exceeded")).isTrue()
        assertThat(isAiQuotaExceeded("ai_quota_exceeded")).isTrue()
        assertThat(isAiQuotaExceeded("Gemini API HTTP 429")).isTrue()
    }

    @Test
    fun `does not treat ordinary failures as a quota problem`() {
        assertThat(isAiQuotaExceeded("O Atlas excedeu o tempo limite de comunicação com o serviço de IA.")).isFalse()
        assertThat(isAiQuotaExceeded("Claude API HTTP 503: ai_provider_error")).isFalse()
        assertThat(isAiQuotaExceeded("Sem conexão")).isFalse()
    }
}
