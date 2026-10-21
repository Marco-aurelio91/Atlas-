package com.unyx.athena.runtime

import com.unyx.athena.core.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Bounded, atomic, versioned technical cache. The caller supplies a private no-backup directory. */
class FileWorldStore(private val file: File) : WorldStore {
    @Synchronized override fun load(): WorldSnapshot {
        if (!file.exists()) return WorldSnapshot()
        require(file.length() <= MAX_BYTES)
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = bytes.toString(Charsets.UTF_8)
        var depth = 0; var quoted = false; var escaped = false
        text.forEach { c ->
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 12) }; '}', ']' -> depth-- }
        }
        val root = JSONObject(text)
        require(root.getInt("version") == WORLD_PROTOCOL_VERSION)
        val events = root.getJSONArray("events")
        val subs = root.getJSONArray("subscriptions")
        require(events.length() <= 32 && subs.length() <= 16)
        return WorldSnapshot(
            (0 until events.length()).map { event(events.getJSONObject(it)) },
            (0 until subs.length()).map { i -> subs.getJSONObject(i).let {
                WorldSubscription(it.getString("id"), selector(it.getJSONObject("selector")), Instant.parse(it.getString("expires")))
            } },
        )
    }

    @Synchronized override fun save(snapshot: WorldSnapshot) {
        require(snapshot.events.size <= 32 && snapshot.subscriptions.size <= 16)
        val events = JSONArray()
        snapshot.events.filter { it.payload !is WorldPayload.SustainedCold }.forEach { events.put(eventJson(it)) }
        val subs = JSONArray()
        snapshot.subscriptions.forEach { subs.put(JSONObject().put("id", it.id).put("selector", selectorJson(it.selector)).put("expires", it.expiresAt.toString())) }
        val bytes = JSONObject().put("version", WORLD_PROTOCOL_VERSION).put("events", events).put("subscriptions", subs).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val parent = file.absoluteFile.parentFile!!
        check(parent.isDirectory || parent.mkdirs())
        val temp = File(parent, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { if (temp.exists()) temp.delete() }
    }

    private fun selectorJson(s: WorldSelector): JSONObject = when (s) {
        is WorldSelector.Weather -> JSONObject().put("kind", "weather").put("lat", s.region.latitudeHundredths).put("lon", s.region.longitudeHundredths)
        is WorldSelector.Service -> JSONObject().put("kind", "service").put("id", s.id)
    }
    private fun selector(j: JSONObject): WorldSelector = when (j.getString("kind")) {
        "weather" -> WorldSelector.Weather(GeographicRegion(j.getInt("lat"), j.getInt("lon")))
        "service" -> WorldSelector.Service(j.getString("id"))
        else -> error("Unsupported selector")
    }
    private fun eventJson(e: WorldEvent): JSONObject {
        val p = when (val payload = e.payload) {
            is WorldPayload.Weather -> JSONObject().put("kind", "weather").put("temperature", payload.conditions.temperatureC).put("code", payload.conditions.weatherCode)
            is WorldPayload.Service -> JSONObject().put("kind", "service").put("condition", payload.condition.name)
            is WorldPayload.SustainedCold -> error("Derived evidence stays in memory")
        }
        return JSONObject().put("id", e.id).put("selector", selectorJson(e.selector)).put("payload", p)
            .put("source", JSONObject().put("id", e.source.connectorId).put("provider", e.source.provider).put("host", e.source.host))
            .put("occurred", e.occurredAt.toString()).put("observed", e.observedAt.toString()).put("expires", e.expiresAt.toString())
    }
    private fun event(j: JSONObject): WorldEvent {
        val p = j.getJSONObject("payload")
        val payload = when (p.getString("kind")) {
            "weather" -> WorldPayload.Weather(WeatherConditions(p.getDouble("temperature"), p.getInt("code")))
            "service" -> WorldPayload.Service(ServiceCondition.valueOf(p.getString("condition")))
            else -> error("Unsupported payload")
        }
        val s = j.getJSONObject("source")
        return WorldEvent(j.getString("id"), selector(j.getJSONObject("selector")), payload,
            AthenaSource(s.getString("id"), s.getString("provider"), s.getString("host")),
            Instant.parse(j.getString("occurred")), Instant.parse(j.getString("observed")), Instant.parse(j.getString("expires")))
    }
    companion object { private const val MAX_BYTES = 131072 }
}
