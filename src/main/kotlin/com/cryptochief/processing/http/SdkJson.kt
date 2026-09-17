package com.cryptochief.processing.http

import kotlinx.serialization.json.Json

/**
 * The single JSON configuration of the SDK: request bodies, API responses, TON RPC and webhook
 * events. Response and webhook decoding must not diverge, so there is one instance.
 */
internal object SdkJson {

    val instance: Json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = false
        coerceInputValues = true
        classDiscriminator = "_type"
    }
}
