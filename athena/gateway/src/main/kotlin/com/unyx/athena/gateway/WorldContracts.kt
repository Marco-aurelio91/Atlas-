package com.unyx.athena.gateway

import kotlinx.serialization.Serializable

@Serializable
enum class WorldIntentDto { CURRENT_WEATHER, SERVICE_STATUS }

@Serializable
data class GeoReferenceDto(val latitude: Double, val longitude: Double)

@Serializable
data class WorldQueryDto(
    val requestId: String,
    val intent: WorldIntentDto,
    val location: GeoReferenceDto? = null,
    val serviceId: String? = null,
)

@Serializable
enum class AnswerStatusDto { SUCCESS, NOT_FOUND, PROVIDER_UNAVAILABLE, INVALID_QUERY, UNAUTHORIZED }

@Serializable
data class EvidenceDto(val sourceId: String, val sourceName: String, val observedAt: String)

@Serializable
data class WorldFactDto(val key: String, val value: String)

@Serializable
data class WorldAnswerDto(
    val requestId: String,
    val status: AnswerStatusDto,
    val facts: List<WorldFactDto> = emptyList(),
    val confidence: Double? = null,
    val freshness: String,
    val evidence: List<EvidenceDto> = emptyList(),
    val generatedAt: String,
)

@Serializable
data class HealthDto(val status: String)
