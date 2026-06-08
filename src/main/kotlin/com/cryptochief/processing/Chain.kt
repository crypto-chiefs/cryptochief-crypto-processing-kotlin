package com.cryptochief.processing

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Chain code — the `network` / `chain` / `network_code` string used across the API. */
@JvmInline
@Serializable(with = Chain.Serializer::class)
public value class Chain(public val code: String) {

    public fun family(): ChainFamily? = ChainFamily.of(this)

    override fun toString(): String = code

    public companion object {
        public val ETH_MAINNET: Chain = Chain("ETH_MAINNET")
        public val ETH_SEPOLIA: Chain = Chain("ETH_SEPOLIA")
        public val BSC_MAINNET: Chain = Chain("BSC_MAINNET")
        public val BSC_TESTNET: Chain = Chain("BSC_TESTNET")
        public val POLYGON_MAINNET: Chain = Chain("POLYGON_MAINNET")
        public val POLYGON_AMOY: Chain = Chain("POLYGON_AMOY")
        public val ARBITRUM_ONE: Chain = Chain("ARBITRUM_ONE")
        public val ARBITRUM_SEPOLIA: Chain = Chain("ARBITRUM_SEPOLIA")
        public val OPTIMISM_MAINNET: Chain = Chain("OPTIMISM_MAINNET")
        public val OPTIMISM_SEPOLIA: Chain = Chain("OPTIMISM_SEPOLIA")
        public val AVAX_MAINNET: Chain = Chain("AVAX_MAINNET")
        public val AVAX_TESTNET: Chain = Chain("AVAX_TESTNET")

        public val BTC_MAINNET: Chain = Chain("BTC_MAINNET")
        public val BTC_TESTNET_4: Chain = Chain("BTC_TESTNET_4")
        public val LITECOIN_MAINNET: Chain = Chain("LITECOIN_MAINNET")
        public val BITCOIN_CASH_MAINNET: Chain = Chain("BITCOIN_CASH_MAINNET")
        public val DOGECOIN_MAINNET: Chain = Chain("DOGECOIN_MAINNET")

        public val TRON_MAINNET: Chain = Chain("TRON_MAINNET")
        public val TRON_NILE: Chain = Chain("TRON_NILE")

        public val SOLANA_MAINNET: Chain = Chain("SOLANA_MAINNET")
        public val SOLANA_DEVNET: Chain = Chain("SOLANA_DEVNET")

        public val TON_MAINNET: Chain = Chain("TON_MAINNET")
        public val TON_TESTNET: Chain = Chain("TON_TESTNET")

        public val XRP_MAINNET: Chain = Chain("XRP_MAINNET")
        public val XRP_TESTNET: Chain = Chain("XRP_TESTNET")
    }

    public object Serializer : KSerializer<Chain> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("com.cryptochief.processing.Chain", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Chain) {
            encoder.encodeString(value.code)
        }

        override fun deserialize(decoder: Decoder): Chain = Chain(decoder.decodeString())
    }
}
