package examples.payout

import com.cryptochief.processing.Chain
import com.cryptochief.processing.CryptoChiefClient
import com.cryptochief.processing.PollOptions
import com.cryptochief.processing.models.EstimatePayoutRequest
import com.cryptochief.processing.models.ExecutePayoutRequest
import com.cryptochief.processing.poll.waitForPayout
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID

fun main(args: Array<String>): Unit = runBlocking {
    val merchantId = System.getenv("CRYPTO_CHIEF_MERCHANT_ID")
        ?: error("set CRYPTO_CHIEF_MERCHANT_ID")
    val apiKey = System.getenv("CRYPTO_CHIEF_API_KEY")
        ?: error("set CRYPTO_CHIEF_API_KEY")
    val toAddress = args.firstOrNull()
        ?: error("usage: PayoutExample <to_address>")

    CryptoChiefClient.create {
        this.merchantId = merchantId
        this.apiKey = apiKey
    }.use { client ->

        val estimate = client.payouts.estimate(
            EstimatePayoutRequest(
                network = Chain.ETH_SEPOLIA,
                coin = "ETH",
                amount = "0.0001",
                toAddress = toAddress,
            ),
        )
        println("estimate: receive=${estimate.amountToReceive} fee=${estimate.feeInfo?.estimatedFiat} USD")

        val payout = client.payouts.execute(
            ExecutePayoutRequest(
                orderId = "order-${UUID.randomUUID()}",
                userId = "user-42",
                network = Chain.ETH_SEPOLIA,
                coin = "ETH",
                amount = "0.0001",
                toAddress = toAddress,
                urlCallback = "https://example.com/webhooks/payout",
            ),
        )
        println("created: uuid=${payout.uuid} status=${payout.status}")

        val terminal = client.waitForPayout(
            uuid = payout.uuid,
            options = PollOptions(interval = Duration.ofSeconds(5), timeout = Duration.ofMinutes(5)),
        )
        println("final:   status=${terminal.status} txid=${terminal.txid.orEmpty()}")
    }
}
