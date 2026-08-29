package examples.webhook

import com.cryptochief.processing.webhook.PayoutWebhookEvent
import com.cryptochief.processing.webhook.SweepWebhookEvent
import com.cryptochief.processing.webhook.WebhookHandler
import com.cryptochief.processing.webhook.WebhookSignatureException
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

fun main() {
    val apiKey = System.getenv("CRYPTO_CHIEF_API_KEY")
        ?: error("set CRYPTO_CHIEF_API_KEY")

    val server = HttpServer.create(InetSocketAddress(8080), 0)
    server.createContext("/webhook") { exchange ->
        if (exchange.requestMethod != "POST") {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
            return@createContext
        }
        val body = exchange.requestBody.readAllBytes()
        val signature = exchange.requestHeaders.getFirst("Signature")
        try {
            val event = WebhookHandler.handle<PayoutWebhookEvent>(apiKey, body, signature)
            println("payout webhook: uuid=${event.uuid} status=${event.status}")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        } catch (e: WebhookSignatureException) {
            System.err.println("rejected: ${e.message}")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        } catch (e: Throwable) {
            System.err.println("decode failed: ${e.message}")
            exchange.sendResponseHeaders(400, -1)
            exchange.close()
        }
    }
    // Sweep - your money finishing its move into your own custody.
    //
    // A static_deposit.paid told you a customer paid. THIS says the funds have
    // been swept off the deposit address and the sweep is confirmed on chain.
    // Until it fires the balance still sits on the deposit wallet, so treasury
    // reporting and "available to pay out" should key off this, not the deposit.
    // Sweeps run on static deposit wallets and on per-order transit wallets
    // alike; both arrive here.
    server.createContext("/webhook/sweep") { exchange ->
        if (exchange.requestMethod != "POST") {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
            return@createContext
        }
        val body = exchange.requestBody.readAllBytes()
        val signature = exchange.requestHeaders.getFirst("Signature")
        try {
            val event = WebhookHandler.handle<SweepWebhookEvent>(apiKey, body, signature)
            println(
                "sweep ${event.taskId}: ${event.amountHuman} ${event.assetSymbol} " +
                    "${event.walletAddress} -> ${event.toAddress} " +
                    "tx=${event.sweepTxHash} confirmations=${event.sweepConfirmations} " +
                    "trigger=${event.typeWork} fee_usd=${event.totalFeeUsd}",
            )

            // taskId is the idempotency key: one sweep settles once. Seeing it
            // twice means a redelivery - acknowledge and stop.
            // if (treasury.alreadyRecorded(event.taskId)) return@createContext

            // The event only ever arrives confirmed, but apply your own finality
            // policy here if you have one - "confirmed" is not the same number
            // on every chain.
            // treasury.recordSettled(event.taskId, event.assetSymbol, event.amountHuman, event.sweepTxHash)
            // costs.record(event.taskId, event.totalFeeUsd)  // sweeps are not free

            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        } catch (e: WebhookSignatureException) {
            System.err.println("rejected: ${e.message}")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        } catch (e: Throwable) {
            System.err.println("decode failed: ${e.message}")
            exchange.sendResponseHeaders(400, -1)
            exchange.close()
        }
    }

    server.start()
    println("listening on http://localhost:8080/webhook")
    println("sweep events on http://localhost:8080/webhook/sweep")
}
