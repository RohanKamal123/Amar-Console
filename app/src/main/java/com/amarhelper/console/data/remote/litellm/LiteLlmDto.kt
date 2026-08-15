package com.amarhelper.console.data.remote.litellm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LiteLlmReadinessDto(
    val status: String? = null,
    val db: String? = null,
    @SerialName("litellm_version") val version: String? = null,
    val cache: String? = null,
)
