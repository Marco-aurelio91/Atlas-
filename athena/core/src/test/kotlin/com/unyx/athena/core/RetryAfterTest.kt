package com.unyx.athena.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RetryAfterTest {
    @Test fun `seconds dates and hostile values are bounded`() {
        val now = Instant.parse("2026-09-30T12:00:00Z")
        assertEquals(Duration.ofSeconds(120), retryAfter("120", now))
        assertEquals(Duration.ofSeconds(120), retryAfter("Wed, 30 Sep 2026 12:02:00 GMT", now))
        assertEquals(Duration.ofHours(1), retryAfter("999999", now))
        assertEquals(Duration.ofSeconds(1), retryAfter("-1", now))
        assertNull(retryAfter("arbitrary instructions", now))
    }
}
