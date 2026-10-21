package com.unyx.atlas.data.smartspace

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.unyx.atlas.data.di.ApplicationScope
import com.unyx.atlas.athena.AtlasAthenaBridge
import com.unyx.athena.core.GeographicRegion
import com.unyx.athena.core.WorldQueryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Current conditions for the smartspace widget's weather slot. */
data class CurrentWeather(
    val temperatureC: Double,
    val weatherCode: Int,
    /** Reverse-geocoded locality ("Espoo"); null when no geocoder backend or no name resolves. */
    val city: String?,
    val fetchedAt: Long,
)

/**
 * ATLAS-owned location/presentation facade. Only a coarse region crosses the ATHENA bridge.
 * The runtime owns provider access and freshness; personal geocoding stays on this side.
 */
@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val athena: AtlasAthenaBridge,
) {
    private val presentation = WeatherPresentationStore()
    val weather: StateFlow<CurrentWeather?> = presentation.weather

    fun currentRegion(): GeographicRegion? = if (hasPermission()) presentation.currentRegion() else null

    private val refreshMutex = Mutex()
    private val cityMutex = Mutex()
    private var expiryJob: Job? = null

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun refresh() {
        if (!hasPermission()) {
            presentation.clear()
            return
        }
        scope.launch(Dispatchers.IO) {
            getFresh()
        }
    }

    /** Waits for this query's valid result; a previous non-null StateFlow value is not enough. */
    suspend fun getFresh(maxAgeMillis: Long = REFRESH_INTERVAL_MS): CurrentWeather? = withContext(Dispatchers.IO) {
        require(maxAgeMillis in 1..REFRESH_INTERVAL_MS)
        refreshMutex.withLock {
            if (!hasPermission()) {
                presentation.clear()
                return@withLock null
            }
            val location = lastKnownLocation()
            if (location == null) {
                presentation.clear()
                return@withLock null
            }
            val region = GeographicRegion(
                Math.round(location.latitude * 100).toInt(), Math.round(location.longitude * 100).toInt(),
            )
            val previous = presentation.currentFor(region)
            val age = previous?.let { System.currentTimeMillis() - it.fetchedAt }
            if (age == null || age !in 0..maxAgeMillis) presentation.clear()
            val response = try {
                athena.weather(region, maxAge = Duration.ofMillis(maxAgeMillis))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (response !is WorldQueryResult.Success || !hasPermission()) {
                presentation.clear()
                return@withLock null
            }
            val event = response.event
            val result = CurrentWeather(
                event.weather.temperatureC, event.weather.weatherCode,
                previous?.city, event.observedAt.toEpochMilli(),
            )
            val generation = presentation.show(region, result)
            expiryJob?.cancel()
            expiryJob = scope.launch {
                delay((event.expiresAt.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1))
                presentation.expire(generation)
            }
            // Geocoding is presentation enrichment, not part of the query deadline. A slow
            // Android geocoder must not delay the chat's already validated weather result.
            if (previous?.fetchedAt != result.fetchedAt || result.city == null) {
                scope.launch(Dispatchers.IO) {
                    cityMutex.withLock cityLock@{
                        if (!presentation.isCurrent(generation) || !hasPermission()) {
                            return@cityLock
                        }
                        val city = resolveCity(location.latitude, location.longitude, region.latitude, region.longitude)
                        if (hasPermission()) presentation.enrichCity(generation, city)
                    }
                }
            }
            result
        }
    }

    /** A cached fix is plenty for weather — no active location request, no battery cost. */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        // Newest fix wins regardless of provider — provider order alone let a stale network fix
        // beat a fresh GPS one and pin the weather to a previous town.
        return providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** Last successfully geocoded name + the rounded area it belongs to: a transient geocoder
     *  failure must not blank a name the user was already seeing, but a clearly different area
     *  must not keep showing the previous town. Guarded by [cityMutex] (single writer). */
    private var lastCity: String? = null
    private var lastCityAreaKey: String? = null
    private var lastCityAtMs = 0L

    private fun resolveCity(exactLat: Double, exactLon: Double, roundedLat: Double, roundedLon: Double): String? {
        val areaKey = "%.2f,%.2f".format(Locale.US, roundedLat, roundedLon)
        val fresh = cityName(exactLat, exactLon) ?: networkCityName(roundedLat, roundedLon)
        if (fresh != null) {
            lastCity = fresh
            lastCityAreaKey = areaKey
            lastCityAtMs = System.currentTimeMillis()
            return fresh
        }
        // Same rounded area AND recent enough: a transient failure must not blank the name, but a
        // dead geocoder must not pin a stale name forever either.
        val cacheAlive = System.currentTimeMillis() - lastCityAtMs < CITY_CACHE_MAX_AGE_MS
        return if (areaKey == lastCityAreaKey && cacheAlive) lastCity else null
    }

    /** Locality via the platform Geocoder — works worldwide on devices with a geocoder backend
     *  (localized names); null elsewhere. Checks several results and fields: older OEM backends
     *  (e.g. Samsung A40) may leave locality empty and put the usable name in another field.
     *  The sync call is fine on this IO thread. Logs never include coordinates. */
    private fun cityName(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) {
            Log.d(TAG, "Reverse geocoding skipped: no geocoder backend")
            return null
        }
        return try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 3).orEmpty()
            if (addresses.isEmpty()) {
                Log.d(TAG, "Reverse geocoding returned no addresses")
                return null
            }
            val name = addresses.firstNotNullOfOrNull { a ->
                PlaceNames.preferred(listOf(a.locality, a.subLocality, a.subAdminArea, a.adminArea, a.featureName))
            }
            if (name == null) Log.d(TAG, "Reverse geocoding found no usable name field")
            name
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocoding failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Network reverse-geocode fallback for devices whose platform Geocoder has no usable backend
     *  (the Samsung A40 symptom): BigDataCloud's keyless client API. Client-side calls with the
     *  device's own current fix are exactly its permitted use. Sends only the SAME 2-decimal
     *  rounded coordinates the weather query already sends — the exact fix never leaves the device.
     *  Reached at most once per 30-min weather refresh, and only when the local geocoder failed. */
    private fun networkCityName(roundedLat: Double, roundedLon: Double): String? {
        val url = URL(
            "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.2f&longitude=%.2f&localityLanguage=%s"
                .format(Locale.US, roundedLat, roundedLon, Locale.getDefault().language),
        )
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            PlaceNameFallback.parseCity(body).also {
                if (it == null) Log.d(TAG, "Network reverse geocoding found no usable name")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network reverse geocoding failed: ${e.javaClass.simpleName}")
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 30L * 60 * 1000
        private const val CITY_CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val TAG = "WeatherRepo"
    }
}
