package examples.invoice

import com.cryptochief.processing.Asset
import com.cryptochief.processing.Chain
import com.cryptochief.processing.CryptoChiefClient
import com.cryptochief.processing.PollOptions
import com.cryptochief.processing.models.CreatePayInRequest
import com.cryptochief.processing.models.PayInMode
import com.cryptochief.processing.poll.waitForPayIn
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID

fun main(args: Array<String>): Unit = runBlocking {
    val merchantId = System.getenv("CRYPTO_CHIEF_MERCHANT_ID")
        ?: error("set CRYPTO_CHIEF_MERCHANT_ID")
    val apiKey = System.getenv("CRYPTO_CHIEF_API_KEY")
        ?: error("set CRYPTO_CHIEF_API_KEY")
    val mode = args.firstOrNull() ?: "fiat"

    CryptoChiefClient.create {
        this.merchantId = merchantId
        this.apiKey = apiKey
    }.use { client ->

        val invoice = when (mode) {
            "fiat" -> client.payIns.create(
                CreatePayInRequest(
                    orderId = "order-${UUID.randomUUID()}",
                    userId = "user-42",
                    mode = PayInMode.FIAT,
                    amountFiat = "19.99",
                    currency = "USD",
                    lifetimeSec = 3600,
                    urlCallback = "https://example.com/webhooks/invoice",
                    urlSuccess = "https://example.com/checkout/success",
                    urlError = "https://example.com/checkout/failed",
                ),
            )

            "crypto" -> client.payIns.create(
                CreatePayInRequest(
                    orderId = "order-${UUID.randomUUID()}",
                    userId = "user-42",
                    mode = PayInMode.CRYPTO,
                    amountCrypto = "10",
                    asset = Asset(network = Chain.TRON_MAINNET, coin = "USDT"),
                    lifetimeSec = 3600,
                    urlCallback = "https://example.com/webhooks/invoice",
                ),
            )

            else -> error("usage: InvoiceExample [fiat|crypto]")
        }

        println("invoice: uuid=${invoice.uuid} status=${invoice.status}")
        println("payment link: ${invoice.paymentLink.orEmpty()}")
        if (invoice.toAddress != null) {
            println("pay to: ${invoice.toAddress} (${invoice.paymentCoin} on ${invoice.paymentNetwork})")
        }

        val terminal = client.waitForPayIn(
            uuid = invoice.uuid,
            options = PollOptions(interval = Duration.ofSeconds(10), timeout = Duration.ofMinutes(15)),
        )
        println("final: status=${terminal.status}")
    }
}
