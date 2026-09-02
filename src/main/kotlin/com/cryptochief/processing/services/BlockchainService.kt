package com.cryptochief.processing.services

import com.cryptochief.processing.Chain
import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.AvailableContract
import com.cryptochief.processing.models.AvailableContractsResponse
import com.cryptochief.processing.models.NetworkRequest
import com.cryptochief.processing.models.SupportedBlockchain
import com.cryptochief.processing.models.TransactionStatusRequest
import com.cryptochief.processing.models.TxStatusRow
import com.cryptochief.processing.models.WalletBalanceRequest
import com.cryptochief.processing.models.WalletBalanceRow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** Read-only blockchain queries. */
public class BlockchainService internal constructor(private val transport: HttpTransport) {

    public suspend fun contractsAvailable(network: Chain? = null): AvailableContractsResponse {
        return if (network == null) {
            transport.send(
                path = "/v1/blockchain/contracts/available",
                requestSerializer = JsonObject.serializer(),
                responseSerializer = serializer(),
                body = JsonObject(emptyMap()),
            )
        } else {
            transport.send(
                path = "/v1/blockchain/contracts/available",
                requestSerializer = serializer<NetworkRequest>(),
                responseSerializer = serializer(),
                body = NetworkRequest(network),
            )
        }
    }

    /**
     * Every coin and token the platform supports, on every network, whatever this project
     * has enabled. Use [contractsAvailable] for what the project can be paid in right now
     * — that is the list orders, sweeps and payouts obey; this one is for building a
     * "which assets could we turn on" picker.
     *
     * Same item shape as [contractsAvailable], [AvailableContract.chainFamily] and
     * [AvailableContract.isTest] included.
     */
    public suspend fun contractsList(): AvailableContractsResponse =
        transport.send(
            path = "/v1/blockchain/contracts/list",
            requestSerializer = JsonObject.serializer(),
            responseSerializer = serializer(),
            body = JsonObject(emptyMap()),
        )

    /**
     * The chains the platform's scanner is connected to right now.
     *
     * Infrastructure-level: it says which chains the platform can read blocks from, not
     * what this project can be paid in — that is [contractsAvailable].
     *
     * The endpoint answers a **bare JSON array**, not an `items` envelope, which is why
     * this returns a [List] rather than a response type.
     *
     * The service builds that array from a Go slice, so an empty one marshals as a literal
     * `null` and not as `[]`. Both arrive here as the empty list: a method whose signature
     * promises a list answers with one, never with a decode error.
     */
    public suspend fun blockchainsList(): List<SupportedBlockchain> =
        transport.send(
            path = "/v1/blockchains/list",
            requestSerializer = JsonObject.serializer(),
            responseSerializer = ListSerializer(SupportedBlockchain.serializer()).nullable,
            body = JsonObject(emptyMap()),
        ) ?: emptyList()

    public suspend fun walletBalance(
        chain: Chain,
        addresses: List<String>,
        contracts: List<String> = emptyList(),
    ): List<WalletBalanceRow> = transport.send(
        path = "/v1/blockchain/wallet/balance",
        requestSerializer = serializer<WalletBalanceRequest>(),
        responseSerializer = ListSerializer(WalletBalanceRow.serializer()),
        body = WalletBalanceRequest(chain, addresses, contracts.ifEmpty { null }),
    )

    public suspend fun transactionStatus(chain: Chain, hash: String): List<TxStatusRow> =
        transport.send(
            path = "/v1/blockchain/transaction/status",
            requestSerializer = serializer<TransactionStatusRequest>(),
            responseSerializer = ListSerializer(TxStatusRow.serializer()),
            body = TransactionStatusRequest(chain, hash),
        )
}
