package com.unyx.athena.weather

import com.unyx.athena.core.*
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

fun interface WeatherTransport {
    suspend fun get(url: String): String
}

/** Public conditions only. Location permissions, geocoding and user context belong to ATLAS. */
class OpenMeteoConnector(
    private val transport: WeatherTransport = OpenMeteoHttpTransport(),
) : AthenaConnector {
    override val manifest = ConnectorManifest(
        source = AthenaSource("open-meteo.weather", "Open-Meteo", HOST),
        version = 1,
        domains = setOf(WorldDomain.WEATHER),
        capabilities = setOf(ConnectorCapability.QUERY),
        networkHosts = setOf(HOST),
        retention = Duration.ofMinutes(30),
    )

    override suspend fun query(query: WorldQuery): WeatherObservation {
        val region = query.region
        val url = "https://$HOST/v1/forecast?latitude=%.2f&longitude=%.2f".format(
            Locale.US, region.latitude, region.longitude,
        ) + "&current=temperature_2m,weather_code&temperature_unit=celsius&timeformat=unixtime&timezone=GMT"
        val body = transport.get(url)
        return parse(body, region)
    }

    internal fun parse(body: String, region: GeographicRegion): WeatherObservation {
        try {
            validateJsonBounds(body)
            val root = JSONObject(body)
            val units = root.getJSONObject("current_units")
            if (units.getString("temperature_2m") != "°C" || units.getString("time") != "unixtime" ||
                units.getString("weather_code") != "wmo code"
            ) throw ConnectorException(AthenaError.INVALID_DATA)
            val current = root.getJSONObject("current")
            val temperature = current.get("temperature_2m") as? Number
                ?: throw ConnectorException(AthenaError.INVALID_DATA)
            val code = current.get("weather_code") as? Number
                ?: throw ConnectorException(AthenaError.INVALID_DATA)
            val timestamp = current.get("time") as? Number
                ?: throw ConnectorException(AthenaError.INVALID_DATA)
            if (!temperature.toDouble().isFinite() || !code.toDouble().isFinite() ||
                code.toDouble() != code.toInt().toDouble() || !timestamp.toDouble().isFinite() ||
                timestamp.toDouble() != timestamp.toLong().toDouble()
            ) throw ConnectorException(AthenaError.INVALID_DATA)
            return WeatherObservation(
                region, WeatherConditions(temperature.toDouble(), code.toInt()),
                Instant.ofEpochSecond(timestamp.toLong()),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConnectorException) {
            throw e
        } catch (_: Exception) {
            // Parser exceptions may contain the payload: expose only the typed error.
            throw ConnectorException(AthenaError.INVALID_DATA)
        }
    }

    /** Check nesting before org.json allocates/recurses; braces inside strings are not nesting. */
    private fun validateJsonBounds(body: String) {
        if (body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) throw ConnectorException(AthenaError.INVALID_DATA)
        var depth = 0
        var inString = false
        var escaped = false
        for (char in body) {
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else when (char) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > 8) throw ConnectorException(AthenaError.INVALID_DATA)
                }
                '}', ']' -> {
                    depth--
                    if (depth < 0) throw ConnectorException(AthenaError.INVALID_DATA)
                }
            }
        }
        if (depth != 0 || inString) throw ConnectorException(AthenaError.INVALID_DATA)
    }

    companion object {
        const val HOST = "api.open-meteo.com"
        const val MAX_RESPONSE_BYTES = 65_536
    }
}
