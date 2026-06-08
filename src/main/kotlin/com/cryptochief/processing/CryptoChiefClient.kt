package com.cryptochief.processing

import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.services.BlockchainService
import com.cryptochief.processing.services.CurrenciesService
import com.cryptochief.processing.services.PayInsService
import com.cryptochief.processing.services.PayoutsService
import com.cryptochief.processing.services.StaticDepositsService
import com.cryptochief.processing.services.SweepsService
import com.cryptochief.processing.services.TransactionsService
import com.cryptochief.processing.services.WalletsService
import com.cryptochief.processing.services.WithdrawalsService
import com.cryptochief.processing.ton.TonRpcClient
import java.io.Closeable

/** Entry point to the Crypto Chief processing API. */
public class CryptoChiefClient(
    public val options: Options,
) : Closeable {

    internal val transport: HttpTransport = HttpTransport(options)

    private val ownsHttpClient: Boolean = options.httpClient == null

    public val payouts: PayoutsService = PayoutsService(transport)
    public val transactions: TransactionsService = TransactionsService(this, transport)
    public val payIns: PayInsService = PayInsService(transport)
    public val wallets: WalletsService = WalletsService(this, transport)
    public val sweeps: SweepsService = SweepsService(transport)
    public val withdrawals: WithdrawalsService = WithdrawalsService(transport)
    public val staticDeposits: StaticDepositsService = StaticDepositsService(transport)
    public val blockchain: BlockchainService = BlockchainService(transport)
    public val currencies: CurrenciesService = CurrenciesService(transport)

    public val merchantId: String get() = options.merchantId

    public val baseUrl: String get() = options.baseUrl

    internal val tonRpc: TonRpcClient by lazy {
        TonRpcClient(
            merchantId = options.merchantId,
            baseUrl = options.tonRpcBaseUrl,
            http = transport.http,
            userAgent = options.userAgent,
        )
    }

    override fun close() {
        if (!ownsHttpClient) return
        transport.http.dispatcher.executorService.shutdown()
        transport.http.connectionPool.evictAll()
    }

    public companion object {
        @JvmStatic
        public fun create(merchantId: String, apiKey: String): CryptoChiefClient =
            CryptoChiefClient(
                Options.builder().apply {
                    this.merchantId = merchantId
                    this.apiKey = apiKey
                }.build(),
            )

        public inline fun create(block: Options.Builder.() -> Unit): CryptoChiefClient =
            CryptoChiefClient(Options.builder().apply(block).build())
    }
}
