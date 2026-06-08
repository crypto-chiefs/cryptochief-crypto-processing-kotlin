package com.cryptochief.processing.ton

import java.math.BigInteger

internal object TonOps {
    const val JETTON_TRANSFER: Int = 0x0f8a7ea5.toInt()
    const val NFT_TRANSFER: Int = 0x5fcc3d14.toInt()
    const val TEXT_COMMENT: Int = 0x00000000
}

/** Builders for the BoC bodies used by TON contract calls. */
public object TonMessages {

    /** TEP-74 Jetton transfer body. */
    public fun jettonTransferBody(
        queryId: Long,
        amount: BigInteger,
        destination: TonAddress,
        responseDestination: TonAddress?,
        customPayload: Cell? = null,
        forwardTon: BigInteger = BigInteger.ZERO,
        forwardPayload: Cell? = null,
    ): ByteArray {
        require(amount.signum() >= 0) { "jetton amount must be non-negative" }
        val builder = CellBuilder()
            .storeUInt(TonOps.JETTON_TRANSFER.toLong() and 0xFFFFFFFFL, 32)
            .storeUInt(queryId, 64)
            .storeCoins(amount)
            .storeAddress(destination)
            .storeAddress(responseDestination)
            .storeMaybeRef(customPayload)
            .storeCoins(forwardTon)
        if (forwardPayload != null) {
            builder.storeBit(true).storeRef(forwardPayload)
        } else {
            builder.storeBit(false)
        }
        return builder.endCell().toBoc()
    }

    /** TEP-62 NFT transfer body. */
    public fun nftTransferBody(
        queryId: Long,
        newOwner: TonAddress,
        responseDestination: TonAddress?,
        customPayload: Cell? = null,
        forwardTon: BigInteger = BigInteger.ZERO,
        forwardPayload: Cell? = null,
    ): ByteArray {
        val builder = CellBuilder()
            .storeUInt(TonOps.NFT_TRANSFER.toLong() and 0xFFFFFFFFL, 32)
            .storeUInt(queryId, 64)
            .storeAddress(newOwner)
            .storeAddress(responseDestination)
            .storeMaybeRef(customPayload)
            .storeCoins(forwardTon)
        if (forwardPayload != null) {
            builder.storeBit(true).storeRef(forwardPayload)
        } else {
            builder.storeBit(false)
        }
        return builder.endCell().toBoc()
    }

    /** Op-0 text comment cell. */
    public fun textCommentCell(text: String): Cell =
        CellBuilder()
            .storeUInt(TonOps.TEXT_COMMENT.toLong(), 32)
            .storeStringSnake(text)
            .endCell()

    public fun textCommentBody(text: String): ByteArray = textCommentCell(text).toBoc()
}
