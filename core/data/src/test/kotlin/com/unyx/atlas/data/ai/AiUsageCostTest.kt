package com.unyx.atlas.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiUsageCostTest {

    private fun day(requests: Int, input: Long = 0, cacheRead: Long = 0, cacheWrite: Long = 0, output: Long = 0) =
        AiDayUsage("2026-10-22", requests, input, cacheRead, cacheWrite, output)

    @Test
    fun `prices input and output at the sonnet 5 rates`() {
        // 1M input at $2 + 1M output at $10.
        val cost = AiUsageCost.estimateUsd(day(requests = 1, input = 1_000_000, output = 1_000_000))

        assertThat(cost).isWithin(1e-9).of(12.0)
    }

    @Test
    fun `cache reads cost a tenth of fresh input and cache writes a quarter more`() {
        assertThat(AiUsageCost.estimateUsd(day(1, cacheRead = 1_000_000))).isWithin(1e-9).of(0.20)
        assertThat(AiUsageCost.estimateUsd(day(1, cacheWrite = 1_000_000))).isWithin(1e-9).of(2.50)
    }

    @Test
    fun `the assumed 6k input and 400 output message costs about one and a half cents`() {
        val cost = AiUsageCost.estimateUsd(day(requests = 1, input = 6_000, output = 400))

        assertThat(cost).isWithin(1e-9).of(0.016)
    }

    @Test
    fun `average per request spans several days and is null without data`() {
        val days = listOf(
            day(requests = 2, input = 12_000, output = 800),
            day(requests = 3, input = 18_000, output = 1_200),
        )

        assertThat(AiUsageCost.averageUsdPerRequest(days)).isWithin(1e-9).of(0.016)
        assertThat(AiUsageCost.averageUsdPerRequest(emptyList())).isNull()
    }
}
