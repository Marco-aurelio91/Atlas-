package com.unyx.athena.status

import com.unyx.athena.core.*

class StatusContractTest : WorldConnectorContract() {
    override fun selector() = GitHubStatusConnector.SELECTOR
    override fun connector() = GitHubStatusConnector {
        """{"page":{"id":"kctbh9vrtdwd","updated_at":"2026-09-30T12:00:00Z"},"status":{"indicator":"none"}}"""
    }
}
