package com.unyx.atlas.data.smartspace

import com.unyx.athena.core.GeographicRegion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** ATLAS-only projection. Generation guards keep late geocoding/expiry from reviving old weather. */
internal class WeatherPresentationStore {
    private var generation = 0L
    private var region: GeographicRegion? = null
    private val state = MutableStateFlow<CurrentWeather?>(null)
    val weather = state.asStateFlow()

    @Synchronized fun currentRegion(): GeographicRegion? = region.takeIf { state.value != null }

    @Synchronized fun currentFor(region: GeographicRegion): CurrentWeather? =
        state.value.takeIf { this.region == region }

    @Synchronized fun show(region: GeographicRegion, value: CurrentWeather): Long {
        generation++
        this.region = region
        state.value = value
        return generation
    }

    @Synchronized fun isCurrent(generation: Long): Boolean = this.generation == generation && state.value != null

    @Synchronized fun enrichCity(generation: Long, city: String?) {
        if (isCurrent(generation)) state.value = state.value?.copy(city = city)
    }

    @Synchronized fun expire(generation: Long) {
        if (this.generation == generation) clear()
    }

    @Synchronized fun clear() {
        generation++
        region = null
        state.value = null
    }
}
