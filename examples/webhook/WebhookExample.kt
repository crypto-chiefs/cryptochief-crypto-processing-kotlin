package examples.webhook

import com.cryptochief.processing.webhook.PayoutWebhookEvent
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
    server.start()
    println("listening on http://localhost:8080/webhook")
}
