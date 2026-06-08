package com.cryptochief.processing.services

import com.cryptochief.processing.Chain
import com.cryptochief.processing.CryptoChiefClient
import com.cryptochief.processing.evm.EvmAbi
import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.ContractCall
import com.cryptochief.processing.models.ExecuteTransactionRequest
import com.cryptochief.processing.models.HistoryQuery
import com.cryptochief.processing.models.SignTransactionRequest
import com.cryptochief.processing.models.SignTransactionResponse
import com.cryptochief.processing.models.SolanaAccount
import com.cryptochief.processing.models.TransactionHistoryResponse
import com.cryptochief.processing.models.TransactionInfo
import com.cryptochief.processing.models.TxType
import com.cryptochief.processing.models.UuidRequest
import com.cryptochief.processing.solana.Anchor
import com.cryptochief.processing.solana.Borsh
import com.cryptochief.processing.ton.TonAddress
import com.cryptochief.processing.ton.TonMessages
import kotlinx.serialization.serializer
import java.math.BigInteger
import java.util.Base64

/** Two-phase sign/execute plus high-level EVM, Solana, TON helpers. */
public class TransactionsService internal constructor(
    private val client: CryptoChiefClient,
    private val transport: HttpTransport,
) {

    public suspend fun sign(request: SignTransactionRequest): SignTransactionResponse =
        transport.send(
            path = "/v1/transaction/signature",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )

    public suspend fun execute(request: ExecuteTransactionRequest): TransactionInfo =
        transport.send(
            path = "/v1/transaction/execute",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )

    public suspend fun execute(uuid: String): TransactionInfo =
        execute(ExecuteTransactionRequest(uuid = uuid))

    public suspend fun info(uuid: String): TransactionInfo =
        transport.send(
            path = "/v1/transaction/info",
            requestSerializer = serializer<UuidRequest>(),
            responseSerializer = serializer(),
            body = UuidRequest(uuid),
        )

    public suspend fun history(query: HistoryQuery = HistoryQuery()): TransactionHistoryResponse =
        transport.send(
            path = "/v1/transaction/history",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = query,
        )

    public suspend fun signEvmCall(
        network: Chain,
        fromAddress: String,
        contract: String,
        method: String,
        args: List<Any?> = emptyList(),
        value: String = "0",
        urlCallback: String? = null,
    ): SignTransactionResponse {
        val data = EvmAbi.encodeCallHex(method, *args.toTypedArray())
        return sign(
            SignTransactionRequest(
                network = network,
                fromAddress = fromAddress,
                type = TxType.CONTRACT,
                urlCallback = urlCallback,
                calls = listOf(ContractCall(to = contract, value = value, data = data)),
            ),
        )
    }

    public suspend fun signTronCall(
        network: Chain,
        fromAddress: String,
        contract: String,
        method: String,
        args: List<Any?> = emptyList(),
        value: String = "0",
        urlCallback: String? = null,
    ): SignTransactionResponse = signEvmCall(network, fromAddress, contract, method, args, value, urlCallback)

    public suspend fun erc20Transfer(
        network: Chain,
        fromAddress: String,
        tokenContract: String,
        recipient: String,
        amount: BigInteger,
        urlCallback: String? = null,
    ): SignTransactionResponse = signEvmCall(
        network = network,
        fromAddress = fromAddress,
        contract = tokenContract,
        method = "transfer(address,uint256)",
        args = listOf(recipient, amount),
        urlCallback = urlCallback,
    )

    public suspend fun signAnchorCall(
        network: Chain,
        fromAddress: String,
        program: String,
        method: String,
        args: List<Borsh>,
        accounts: List<SolanaAccount>,
        urlCallback: String? = null,
    ): SignTransactionResponse {
        val data = Anchor.encodeInstruction(method, *args.toTypedArray())
        return sign(
            SignTransactionRequest(
                network = network,
                fromAddress = fromAddress,
                type = TxType.CONTRACT,
                urlCallback = urlCallback,
                calls = listOf(
                    ContractCall(
                        to = program,
                        data = Base64.getEncoder().encodeToString(data),
                        accounts = accounts,
                    ),
                ),
            ),
        )
    }

    public suspend fun signSolanaCall(
        network: Chain,
        fromAddress: String,
        program: String,
        instructionData: ByteArray,
        accounts: List<SolanaAccount>,
        urlCallback: String? = null,
    ): SignTransactionResponse = sign(
        SignTransactionRequest(
            network = network,
            fromAddress = fromAddress,
            type = TxType.CONTRACT,
            urlCallback = urlCallback,
            calls = listOf(
                ContractCall(
                    to = program,
                    data = Base64.getEncoder().encodeToString(instructionData),
                    accounts = accounts,
                ),
            ),
        ),
    )

    public suspend fun signTonCall(
        network: Chain,
        fromAddress: String,
        contract: String,
        bodyCell: ByteArray,
        value: String = "0",
        bounce: Boolean? = null,
        urlCallback: String? = null,
    ): SignTransactionResponse = sign(
        SignTransactionRequest(
            network = network,
            fromAddress = fromAddress,
            type = TxType.CONTRACT,
            urlCallback = urlCallback,
            calls = listOf(
                ContractCall(
                    to = contract,
                    value = value,
                    data = Base64.getEncoder().encodeToString(bodyCell),
                    bounce = bounce,
                ),
            ),
        ),
    )

    public suspend fun jettonTransfer(
        network: Chain,
        fromAddress: String,
        jettonMaster: String,
        recipient: String,
        amount: BigInteger,
        jettonWalletAddress: String? = null,
        responseDestination: String? = null,
        attachedTonNanos: BigInteger? = null,
        forwardTonNanos: BigInteger? = null,
        memo: String? = null,
        queryId: Long = 0,
        urlCallback: String? = null,
    ): SignTransactionResponse {
        require(recipient.isNotEmpty()) { "recipient is required" }
        require(jettonMaster.isNotEmpty() || jettonWalletAddress != null) {
            "jettonMaster or jettonWalletAddress is required"
        }
        val rpc = client.tonRpc
        val senderWallet = jettonWalletAddress ?: rpc.lookupJettonWallet(jettonMaster, fromAddress)

        val dest = TonAddress.parse(recipient)
        val respAddr = TonAddress.parse(responseDestination ?: fromAddress)
        val forwardPayload = memo?.takeIf { it.isNotEmpty() }?.let(TonMessages::textCommentCell)
        val forwardAmount = forwardTonNanos
            ?: if (memo.isNullOrEmpty()) BigInteger.ZERO else BigInteger.ONE

        val attached = attachedTonNanos ?: run {
            val recipientHasWallet = jettonMaster.isNotEmpty() && rpc.hasJettonWallet(jettonMaster, recipient)
            if (recipientHasWallet) BigInteger.valueOf(70_000_000L) else BigInteger.valueOf(150_000_000L)
        }

        val body = TonMessages.jettonTransferBody(
            queryId = queryId,
            amount = amount,
            destination = dest,
            responseDestination = respAddr,
            customPayload = null,
            forwardTon = forwardAmount,
            forwardPayload = forwardPayload,
        )
        return signTonCall(
            network = network,
            fromAddress = fromAddress,
            contract = senderWallet,
            bodyCell = body,
            value = attached.toString(),
            bounce = true,
            urlCallback = urlCallback,
        )
    }

    public suspend fun nftTransfer(
        network: Chain,
        fromAddress: String,
        nftItem: String,
        newOwner: String,
        responseDestination: String? = null,
        attachedTonNanos: BigInteger = BigInteger.valueOf(50_000_000L),
        forwardTonNanos: BigInteger = BigInteger.ZERO,
        queryId: Long = 0,
        urlCallback: String? = null,
    ): SignTransactionResponse {
        require(nftItem.isNotEmpty()) { "nftItem is required" }
        require(newOwner.isNotEmpty()) { "newOwner is required" }
        val owner = TonAddress.parse(newOwner)
        val respAddr = TonAddress.parse(responseDestination ?: fromAddress)
        val body = TonMessages.nftTransferBody(
            queryId = queryId,
            newOwner = owner,
            responseDestination = respAddr,
            forwardTon = forwardTonNanos,
        )
        return signTonCall(
            network = network,
            fromAddress = fromAddress,
            contract = nftItem,
            bodyCell = body,
            value = attachedTonNanos.toString(),
            bounce = true,
            urlCallback = urlCallback,
        )
    }

    public suspend fun sendTonComment(
        network: Chain,
        fromAddress: String,
        recipient: String,
        text: String,
        amountTonNanos: BigInteger = BigInteger.ZERO,
        urlCallback: String? = null,
    ): SignTransactionResponse {
        require(recipient.isNotEmpty()) { "recipient is required" }
        val body = TonMessages.textCommentBody(text)
        return signTonCall(
            network = network,
            fromAddress = fromAddress,
            contract = recipient,
            bodyCell = body,
            value = amountTonNanos.toString(),
            bounce = false,
            urlCallback = urlCallback,
        )
    }
}
