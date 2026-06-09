package com.cryptochief.processing.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Canonical JSON encoder used by request signing and webhook verification. */
public object CanonicalJson {

    public val json: Json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = false
        coerceInputValues = true
        classDiscriminator = "_type"
    }

    public fun encode(element: JsonElement?): ByteArray {
        if (element == null) return EMPTY
        return json.encodeToString(JsonElement.serializer(), sortKeysRecursive(element))
            .toByteArray(Charsets.UTF_8)
    }

    public val EMPTY: ByteArray = ByteArray(0)

    public fun sortKeysRecursive(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.entries
                .sortedBy { it.key }
                .forEach { (k, v) -> put(k, sortKeysRecursive(v)) }
        }
        is JsonArray -> buildJsonArray {
            element.forEach { add(sortKeysRecursive(it)) }
        }
        is JsonPrimitive -> element
    }
}
