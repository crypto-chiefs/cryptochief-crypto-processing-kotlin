package com.cryptochief.processing

import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.services.BlockchainService
import com.cryptochief.processing.services.CreditsService
import com.cryptochief.processing.services.CurrenciesService
import com.cryptochief.processing.services.PayInsService
import com.cryptochief.processing.services.PayoutsService
import com.cryptochief.processing.services.StaticDepositsService
import com.cryptochief.processing.services.WebhooksService
import com.cryptochief.processing.services.SweepsService
import com.cryptochief.processing.services.TransactionsService
import com.cryptochief.processing.services.WalletsService
import com.cryptochief.processing.services.WithdrawalsService
import com.cryptochief.processing.ton.TonRpcClient
import kotlinx.serialization.DeserializationStrategy
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
    public val credits: CreditsService = CreditsService(transport)
    public val webhooks: WebhooksService = WebhooksService(transport)

    public val merchantId: String get() = options.merchantId

    public val baseUrl: String get() = options.baseUrl

    /**
     * Sends a signed request with [method] to [path] and returns the response body as received.
     * The low-level entry point behind every service method: same signing, retries, clock
     * correction and error envelope.
     *
     * Use it for a route this SDK has no method for, on any Crypto Chief API that takes the same
     * credentials — the energy API answers two of them on GET:
     *
     * ```
     * val raw = client.request("GET", "/v1/balance")
     * ```
     *
     * [method] is signed and sent with `a`–`z` in upper case. [path] starts with `/` and holds
     * the route without the base URL; a query goes on it as `?a=1&b=2` and is signed as written,
     * while the path itself is signed with its `%`-sequences decoded — the form the server reads.
     * [body] is sent as is and the signature covers those bytes; `GET` and `HEAD` take none.
     * `Idempotency-Key` comes from the coroutine context, as it does for a service call.
     *
     * @throws ApiException
     * @throws NetworkException
     * @throws IllegalArgumentException if [method] is empty, [path] does not start with `/` or
     * holds an invalid `%`-escape, or [body] is not empty on a method that takes none.
     */
    public suspend fun request(method: String, path: String, body: ByteArray = ByteArray(0)): ByteArray =
        transport.request(method, path, body)

    /**
     * [request], with the response decoded by [responseSerializer]:
     *
     * ```
     * val balance: EnergyBalance = client.request("GET", "/v1/balance", responseSerializer = serializer())
     * ```
     *
     * @throws ApiException
     * @throws NetworkException
     * @throws DecodeException if the response body is empty or does not decode.
     */
    public suspend fun <T> request(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        responseSerializer: DeserializationStrategy<T>,
    ): T = transport.request(method, path, body, responseSerializer)

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
