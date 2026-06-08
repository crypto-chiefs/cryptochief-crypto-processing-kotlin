package com.cryptochief.processing.services

import com.cryptochief.processing.Chain
import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.AvailableContractsResponse
import com.cryptochief.processing.models.NetworkRequest
import com.cryptochief.processing.models.TransactionStatusRequest
import com.cryptochief.processing.models.TxStatusRow
import com.cryptochief.processing.models.WalletBalanceRequest
import com.cryptochief.processing.models.WalletBalanceRow
import kotlinx.serialization.builtins.ListSerializer
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
