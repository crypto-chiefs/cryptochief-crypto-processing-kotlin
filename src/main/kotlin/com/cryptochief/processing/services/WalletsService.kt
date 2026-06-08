package com.cryptochief.processing.services

import com.cryptochief.processing.ConfigurationException
import com.cryptochief.processing.CryptoChiefClient
import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.AddressRequest
import com.cryptochief.processing.models.GenerateWalletRequest
import com.cryptochief.processing.models.ListWalletsResponse
import com.cryptochief.processing.models.Wallet
import com.cryptochief.processing.rsa.RsaDecrypt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** Wallet management. */
public class WalletsService internal constructor(
    private val client: CryptoChiefClient,
    private val transport: HttpTransport,
) {

    public suspend fun generate(request: GenerateWalletRequest): Wallet =
        transport.send(
            path = "/v1/wallets/generate",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )

    public suspend fun list(): ListWalletsResponse =
        transport.send(
            path = "/v1/wallets/list",
            requestSerializer = JsonObject.serializer(),
            responseSerializer = serializer(),
            body = JsonObject(emptyMap()),
        )

    public suspend fun info(address: String): Wallet =
        transport.send(
            path = "/v1/wallets/info",
            requestSerializer = serializer<AddressRequest>(),
            responseSerializer = serializer(),
            body = AddressRequest(address),
        )

    public suspend fun freeze(address: String): Wallet =
        transport.send(
            path = "/v1/wallets/freeze",
            requestSerializer = serializer<AddressRequest>(),
            responseSerializer = serializer(),
            body = AddressRequest(address),
        )

    /** Requires [com.cryptochief.processing.Options.rsaPrivateKey] to be set. */
    public fun decryptPrivateKey(encrypted: String): String {
        val key = client.options.rsaPrivateKey
            ?: throw ConfigurationException(
                "cryptochief: RSA private key not configured — set Options.rsaPrivateKey",
            )
        return RsaDecrypt.oaepSha256(key, encrypted)
    }
}
