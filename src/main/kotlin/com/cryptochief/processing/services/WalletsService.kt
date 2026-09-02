package com.cryptochief.processing.services

import com.cryptochief.processing.ConfigurationException
import com.cryptochief.processing.CryptoChiefClient
import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.AddressRequest
import com.cryptochief.processing.models.GenerateWalletRequest
import com.cryptochief.processing.models.ListWalletsResponse
import com.cryptochief.processing.models.PayInHistoryResponse
import com.cryptochief.processing.models.RebindMasterRequest
import com.cryptochief.processing.models.SetCallbackUrlRequest
import com.cryptochief.processing.models.SetLabelRequest
import com.cryptochief.processing.models.Wallet
import com.cryptochief.processing.models.WalletHistoryQuery
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

    /**
     * Every pay-in that used one deposit address, newest page first — the same records
     * [com.cryptochief.processing.services.PayInsService.history] returns, narrowed to a
     * single wallet, and in the same [PayInHistoryResponse] shape.
     *
     * What it is for: a payer says they sent funds and you have the address but not the
     * order. A deposit wallet serves several orders over its lifetime, and this is the
     * list of them.
     *
     * The address is matched case-insensitively, so either spelling of an EVM address
     * works, and only this project's orders come back — an address that is not yours
     * yields an empty page rather than an error.
     */
    public suspend fun history(query: WalletHistoryQuery): PayInHistoryResponse =
        transport.send(
            path = "/v1/wallets/history",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = query,
        )

    public suspend fun freeze(address: String): Wallet =
        transport.send(
            path = "/v1/wallets/freeze",
            requestSerializer = serializer<AddressRequest>(),
            responseSerializer = serializer(),
            body = AddressRequest(address),
        )

    /**
     * Re-point a transit or static wallet at another master of the same project. Returns
     * the wallet as it stands afterwards.
     *
     * **It moves no money.** It changes where the NEXT sweep settles — including sweeps
     * already queued, since the sweeper reads the link when it runs rather than capturing
     * it on the task. Anything already swept is sitting on the previous master and has to
     * be moved from there.
     *
     * The master link used to be decided once, at creation: a wallet minted without
     * [GenerateWalletRequest.masterWalletAddress] went to the project's oldest master of
     * the family, which on an installation serving several merchants is somebody else's
     * wallet. This is the way back.
     *
     * Idempotent — a wallet already bound to [masterWalletAddress] answers 200 unchanged.
     * Refused for a master wallet (it is a destination, not a dependant), for a master of
     * a different chain family, and for a frozen master. Both addresses are resolved
     * against the calling project, so one that is not yours reads as not found rather
     * than admitting it exists.
     */
    public suspend fun rebindMaster(address: String, masterWalletAddress: String): Wallet =
        transport.send(
            path = "/v1/wallets/rebind-master",
            requestSerializer = serializer<RebindMasterRequest>(),
            responseSerializer = serializer(),
            body = RebindMasterRequest(address, masterWalletAddress),
        )

    /**
     * Set — or, with an empty [callbackUrl], clear — the deposit webhook of a STATIC
     * wallet after it was created. Returns the wallet as it stands afterwards.
     *
     * Until this existed a callback could be chosen when the address was minted and never
     * again, which left nothing for the two cases that matter: addresses imported from a
     * platform that created them elsewhere, and an endpoint that moved.
     *
     * An empty string is a value, not an omission — it means "stop announcing deposits
     * here" — and it is sent as such. Use [clearCallbackUrl] to say so plainly.
     *
     * Static wallets only: a master or transit has no per-deposit callback and answers
     * 400. A deposit already announced is not re-announced to the new URL; the change
     * applies to what arrives next.
     */
    public suspend fun setCallbackUrl(address: String, callbackUrl: String): Wallet =
        transport.send(
            path = "/v1/wallets/callback-url",
            requestSerializer = serializer<SetCallbackUrlRequest>(),
            responseSerializer = serializer(),
            body = SetCallbackUrlRequest(address, callbackUrl),
        )

    /** [setCallbackUrl] with an empty URL: deposits to [address] stop being announced. */
    public suspend fun clearCallbackUrl(address: String): Wallet = setCallbackUrl(address, "")

    /**
     * Set — or, with an empty [label], clear — a wallet's human-readable name. Returns the
     * wallet as it stands afterwards.
     *
     * A name could be chosen when the address was minted and, over the API, never again:
     * renaming meant a panel, one wallet at a time. That is no use to an integration
     * holding a list of a hundred addresses, which is exactly where a name earns its keep.
     *
     * Every wallet type takes one, unlike [setCallbackUrl] — a label names the wallet, it
     * does not describe its role, so a master is as nameable as a static deposit address.
     *
     * An empty string is a value, not an omission — it means "this wallet has no name" —
     * and it is sent as such. Use [clearLabel] to say so plainly. Afterwards the wallet
     * reads back with [Wallet.label] `null`, not `""`.
     *
     * At most 255 characters, counted as characters rather than bytes so a label in a
     * non-Latin script measures the way its author would count it; longer is refused with
     * `LABEL_TOO_LONG`.
     */
    public suspend fun setLabel(address: String, label: String): Wallet =
        transport.send(
            path = "/v1/wallets/label",
            requestSerializer = serializer<SetLabelRequest>(),
            responseSerializer = serializer(),
            body = SetLabelRequest(address, label),
        )

    /** [setLabel] with an empty label: [address] goes back to having no name. */
    public suspend fun clearLabel(address: String): Wallet = setLabel(address, "")

    /** Requires [com.cryptochief.processing.Options.rsaPrivateKey] to be set. */
    public fun decryptPrivateKey(encrypted: String): String {
        val key = client.options.rsaPrivateKey
            ?: throw ConfigurationException(
                "cryptochief: RSA private key not configured — set Options.rsaPrivateKey",
            )
        return RsaDecrypt.oaepSha256(key, encrypted)
    }
}
