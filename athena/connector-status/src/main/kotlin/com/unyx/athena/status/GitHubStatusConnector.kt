package com.unyx.athena.status

import com.unyx.athena.core.*
import java.time.Duration
import java.time.Instant
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

fun interface StatusTransport { suspend fun get(): String }

/** Public status rollup. Provider text is discarded; only a known indicator crosses the boundary. */
class GitHubStatusConnector(private val transport: StatusTransport = GitHubStatusTransport()) : WorldConnector {
    override val descriptor = WorldManifest(AthenaSource("github.status", "GitHub Status", HOST), Duration.ofMinutes(15), setOf(SELECTOR))
    override fun accepts(selector: WorldSelector) = selector == SELECTOR
    override suspend fun fetch(selector: WorldSelector): WorldCandidate {
        if (!accepts(selector)) throw ConnectorException(AthenaError.INVALID_DATA)
        val body = transport.get()
        try {
            require(body.toByteArray(Charsets.UTF_8).size <= 65536)
            var depth = 0; var quoted = false; var escaped = false
            body.forEach { c ->
                if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
                else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 12) }; '}', ']' -> depth-- }
            }
            val root = JSONObject(body)
            val page = root.getJSONObject("page")
            require(page.getString("id") == "kctbh9vrtdwd")
            val condition = when (root.getJSONObject("status").getString("indicator")) {
                "none" -> ServiceCondition.OPERATIONAL
                "minor" -> ServiceCondition.DEGRADED
                "major" -> ServiceCondition.MAJOR_OUTAGE
                "critical" -> ServiceCondition.CRITICAL_OUTAGE
                else -> throw IllegalArgumentException()
            }
            return WorldCandidate(selector, WorldPayload.Service(condition), Instant.parse(page.getString("updated_at")))
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { throw ConnectorException(AthenaError.INVALID_DATA) }
    }
    companion object {
        const val HOST = "www.githubstatus.com"
        const val URL = "https://www.githubstatus.com/api/v2/status.json"
        val SELECTOR = WorldSelector.Service("github")
    }
}
