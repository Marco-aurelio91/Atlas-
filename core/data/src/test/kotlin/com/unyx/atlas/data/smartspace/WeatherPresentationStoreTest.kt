package com.unyx.atlas.data.smartspace

import com.unyx.athena.core.GeographicRegion
import org.junit.Assert.*
import org.junit.Test

class WeatherPresentationStoreTest {
    private val store = WeatherPresentationStore()
    private val region = GeographicRegion(-2355, -4663)
    private val weather = CurrentWeather(20.0, 3, null, 1000)

    @Test fun `late geocoder and old timer cannot replace a newer region with identical timestamp`() {
        val old = store.show(region, weather)
        val otherRegion = GeographicRegion(100, 200)
        val current = store.show(otherRegion, weather.copy(temperatureC = 30.0))
        store.enrichCity(old, "Old city")
        store.expire(old)
        assertEquals(30.0, store.weather.value!!.temperatureC, 0.0)
        assertNull(store.currentFor(region))
        store.enrichCity(current, "New city")
        assertEquals("New city", store.currentFor(otherRegion)?.city)
    }

    @Test fun `permission clear or expiry cannot be undone by late enrichment`() {
        val old = store.show(region, weather)
        store.clear()
        store.enrichCity(old, "Late city")
        assertNull(store.weather.value)
        val next = store.show(region, weather)
        store.expire(next)
        store.enrichCity(next, "Late city")
        assertNull(store.weather.value)
    }
}
