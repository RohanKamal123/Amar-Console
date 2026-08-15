package com.amarhelper.console.data.remote.litellm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UsageSummaryDto(
    @SerialName("generated_at") val generatedAt: String = "",
    @SerialName("period_days") val periodDays: Int = 30,
    val currency: String = "USD",
    val pricing: UsagePricingDto = UsagePricingDto(),
    val totals: UsageTotalsDto = UsageTotalsDto(),
    @SerialName("by_software") val bySoftware: List<SoftwareUsageDto> = emptyList(),
    @SerialName("by_model") val byModel: List<ModelUsageDto> = emptyList(),
    val recent: List<UsageEventDto> = emptyList(),
)

@Serializable
data class UsagePricingDto(
    @SerialName("input_cache_miss_per_million") val inputCacheMissPerMillion: Double = 0.0,
    @SerialName("input_cache_hit_per_million") val inputCacheHitPerMillion: Double = 0.0,
    @SerialName("output_per_million") val outputPerMillion: Double = 0.0,
    val note: String = "",
)

@Serializable
data class UsageTotalsDto(
    val requests: Long = 0,
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("estimated_cost_usd") val estimatedCostUsd: Double = 0.0,
)

@Serializable
data class SoftwareUsageDto(
    val software: String,
    val requests: Long = 0,
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("estimated_cost_usd") val estimatedCostUsd: Double = 0.0,
    @SerialName("average_duration_ms") val averageDurationMs: Double = 0.0,
)

@Serializable
data class ModelUsageDto(
    val model: String,
    val requests: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("estimated_cost_usd") val estimatedCostUsd: Double = 0.0,
)

@Serializable
data class UsageEventDto(
    @SerialName("created_at") val createdAt: Long,
    val software: String,
    val model: String,
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("estimated_cost_usd") val estimatedCostUsd: Double = 0.0,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("status_code") val statusCode: Int = 0,
)

/**
 * Optional detail pulled from a health response when the deployment happens to send it.
 * Absent fields are simply not shown rather than being invented.
 */
object LiteLlmHealth {

    fun version(body: JsonObject?): String? = body?.string("litellm_version")

    fun status(body: JsonObject?): String? = body?.string("status") ?: body?.string("db")

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
