package com.cryptochief.processing.models

import com.cryptochief.processing.Chain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public object TxType {
    public const val NATIVE: String = "native"
    public const val TOKEN: String = "token"
    public const val CONTRACT: String = "contract"
}

public object TxStatus {
    public const val SIGNED: String = "signed"
    public const val BROADCASTING: String = "broadcasting"
    public const val BROADCASTED: String = "broadcasted"
    public const val CONFIRMED: String = "confirmed"
    public const val FAILED: String = "failed"
    public const val EXPIRED: String = "expired"

    public val TERMINAL: Set<String> = setOf(CONFIRMED, FAILED, EXPIRED)
}

@Serializable
public data class SolanaAccount(
    @SerialName("pubkey") val pubkey: String,
    @SerialName("is_signer") val isSigner: Boolean,
    @SerialName("is_writable") val isWritable: Boolean,
)

@Serializable
public data class ContractCall(
    @SerialName("to") val to: String,
    @SerialName("value") val value: String? = null,
    @SerialName("data") val data: String,
    @SerialName("accounts") val accounts: List<SolanaAccount>? = null,
    @SerialName("bounce") val bounce: Boolean? = null,
)

@Serializable
public data class SignTransactionRequest(
    @SerialName("network") val network: Chain,
    @SerialName("from_address") val fromAddress: String,
    @SerialName("type") val type: String,
    @SerialName("to_address") val toAddress: String? = null,
    @SerialName("value") val value: String? = null,
    @SerialName("contract") val contract: String? = null,
    @SerialName("calls") val calls: List<ContractCall>? = null,
    @SerialName("url_callback") val urlCallback: String? = null,
)

@Serializable
public data class SignTransactionResponse(
    @SerialName("uuid") val uuid: String,
    @SerialName("status") val status: String,
    @SerialName("signed_tx_hex") val signedTxHex: String,
    @SerialName("tx_hash") val txHash: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("chain_family") val chainFamily: String,
    @SerialName("network") val network: Chain? = null,
)

@Serializable
public data class ExecuteTransactionRequest(
    @SerialName("uuid") val uuid: String,
    @SerialName("signed_tx_hex") val signedTxHex: String? = null,
)

@Serializable
public data class TransactionInfo(
    @SerialName("uuid") val uuid: String,
    @SerialName("status") val status: String,
    @SerialName("network") val network: Chain,
    @SerialName("chain_family") val chainFamily: String? = null,
    @SerialName("from_address") val fromAddress: String,
    @SerialName("to_address") val toAddress: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("value") val value: String? = null,
    @SerialName("coin") val coin: String? = null,
    @SerialName("contract") val contract: String? = null,
    @SerialName("tx_hash") val txHash: String? = null,
    @SerialName("signed_tx_hex") val signedTxHex: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("nonce") val nonce: Long? = null,
    @SerialName("actual_fee") val actualFee: String? = null,
    @SerialName("actual_fee_fiat") val actualFeeFiat: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("error") val error: String? = null,
) {
    public val isTerminal: Boolean get() = status in TxStatus.TERMINAL
    public val succeeded: Boolean get() = status == TxStatus.CONFIRMED
}

@Serializable
public data class TransactionHistoryResponse(
    @SerialName("items") val items: List<TransactionInfo> = emptyList(),
    @SerialName("meta") val meta: HistoryMeta = HistoryMeta(),
)
