package com.cryptochief.processing

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Protocol family for a [Chain] — the `chain_family` value in API responses. */
@JvmInline
@Serializable(with = ChainFamily.Serializer::class)
public value class ChainFamily(public val code: String) {

    /** True for EVM, TRON, Solana, TON. */
    public fun supportsContractCalls(): Boolean = this in CONTRACT_FAMILIES

    override fun toString(): String = code

    public companion object {
        public val EVM: ChainFamily = ChainFamily("EVM")
        public val TRON: ChainFamily = ChainFamily("TRON")
        public val SOLANA: ChainFamily = ChainFamily("SOLANA")
        public val XRP_LEDGER: ChainFamily = ChainFamily("XRP_LEDGER")
        public val TON: ChainFamily = ChainFamily("TON")
        public val BTC_UTXO: ChainFamily = ChainFamily("BTC_UTXO")
        public val BTC_UTXO_TESTNET: ChainFamily = ChainFamily("BTC_UTXO_TESTNET")
        public val DOGECOIN_UTXO: ChainFamily = ChainFamily("DOGECOIN_UTXO")
        public val BTC_CASH_UTXO: ChainFamily = ChainFamily("BTC_CASH_UTXO")
        public val LITECOIN_UTXO: ChainFamily = ChainFamily("LITECOIN_UTXO")

        private val CONTRACT_FAMILIES = setOf(EVM, TRON, SOLANA, TON)

        private val CHAIN_TO_FAMILY: Map<Chain, ChainFamily> = mapOf(
            Chain.ETH_MAINNET to EVM,
            Chain.ETH_SEPOLIA to EVM,
            Chain.BSC_MAINNET to EVM,
            Chain.BSC_TESTNET to EVM,
            Chain.POLYGON_MAINNET to EVM,
            Chain.POLYGON_AMOY to EVM,
            Chain.ARBITRUM_ONE to EVM,
            Chain.ARBITRUM_SEPOLIA to EVM,
            Chain.OPTIMISM_MAINNET to EVM,
            Chain.OPTIMISM_SEPOLIA to EVM,
            Chain.AVAX_MAINNET to EVM,
            Chain.AVAX_TESTNET to EVM,

            Chain.BTC_MAINNET to BTC_UTXO,
            Chain.BTC_TESTNET_4 to BTC_UTXO_TESTNET,
            Chain.LITECOIN_MAINNET to LITECOIN_UTXO,
            Chain.BITCOIN_CASH_MAINNET to BTC_CASH_UTXO,
            Chain.DOGECOIN_MAINNET to DOGECOIN_UTXO,

            Chain.TRON_MAINNET to TRON,
            Chain.TRON_NILE to TRON,
            Chain.SOLANA_MAINNET to SOLANA,
            Chain.SOLANA_DEVNET to SOLANA,
            Chain.TON_MAINNET to TON,
            Chain.TON_TESTNET to TON,
            Chain.XRP_MAINNET to XRP_LEDGER,
            Chain.XRP_TESTNET to XRP_LEDGER,
        )

        public fun of(chain: Chain): ChainFamily? = CHAIN_TO_FAMILY[chain]
    }

    public object Serializer : KSerializer<ChainFamily> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("com.cryptochief.processing.ChainFamily", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: ChainFamily) {
            encoder.encodeString(value.code)
        }

        override fun deserialize(decoder: Decoder): ChainFamily = ChainFamily(decoder.decodeString())
    }
}
