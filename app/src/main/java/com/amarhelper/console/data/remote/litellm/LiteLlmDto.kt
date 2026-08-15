package com.amarhelper.console.data.remote.litellm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
