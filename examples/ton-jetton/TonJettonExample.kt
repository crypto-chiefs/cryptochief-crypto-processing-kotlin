package examples.tonjetton

import com.cryptochief.processing.Amount
import com.cryptochief.processing.Chain
import com.cryptochief.processing.CryptoChiefClient
import com.cryptochief.processing.PollOptions
import com.cryptochief.processing.poll.waitForTransaction
import kotlinx.coroutines.runBlocking
import java.time.Duration

private const val USDT_JETTON_MASTER_TON =
    "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs"

fun main(args: Array<String>): Unit = runBlocking {
    val merchantId = System.getenv("CRYPTO_CHIEF_MERCHANT_ID")
        ?: error("set CRYPTO_CHIEF_MERCHANT_ID")
    val apiKey = System.getenv("CRYPTO_CHIEF_API_KEY")
        ?: error("set CRYPTO_CHIEF_API_KEY")
    val fromAddress = args.getOrNull(0) ?: error("arg 1: sender TON wallet")
    val toAddress = args.getOrNull(1) ?: error("arg 2: recipient TON wallet")

    CryptoChiefClient.create {
        this.merchantId = merchantId
        this.apiKey = apiKey
    }.use { client ->
        val signed = client.transactions.jettonTransfer(
            network = Chain.TON_MAINNET,
            fromAddress = fromAddress,
            jettonMaster = USDT_JETTON_MASTER_TON,
            recipient = toAddress,
            amount = Amount.toBase("12.50", 6),
            memo = "Order #4242",
            urlCallback = "https://example.com/webhooks/transaction",
        )
        println("signed: uuid=${signed.uuid} expires=${signed.expiresAt}")

        val executed = client.transactions.execute(signed.uuid)
        println("broadcasting: status=${executed.status}")

        val final = client.waitForTransaction(
            uuid = executed.uuid,
            options = PollOptions(timeout = Duration.ofMinutes(2)),
        )
        println("final: status=${final.status} hash=${final.txHash.orEmpty()}")
    }
}
