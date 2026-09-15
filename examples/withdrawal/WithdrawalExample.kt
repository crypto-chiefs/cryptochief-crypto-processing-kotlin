package examples.withdrawal

import com.cryptochief.processing.CryptoChiefClient
import com.cryptochief.processing.models.HistoryQuery
import com.cryptochief.processing.models.Withdrawal
import com.cryptochief.processing.models.WithdrawalStatus
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>): Unit = runBlocking {
    val merchantId = System.getenv("CRYPTO_CHIEF_MERCHANT_ID")
        ?: error("set CRYPTO_CHIEF_MERCHANT_ID")
    val apiKey = System.getenv("CRYPTO_CHIEF_API_KEY")
        ?: error("set CRYPTO_CHIEF_API_KEY")

    CryptoChiefClient.create {
        this.merchantId = merchantId
        this.apiKey = apiKey
    }.use { client ->

        // One withdrawal, when a uuid is given.
        args.firstOrNull()?.let { uuid -> println(describe(client.withdrawals.info(uuid))) }

        // The latest page. Withdrawal history filters by date only.
        val page = client.withdrawals.history(HistoryQuery(page = 1, pageSize = 20))
        println("${page.meta.total} withdrawals")
        page.items.forEach { println(describe(it)) }
    }
}

private fun describe(wd: Withdrawal): String = when {
    wd.succeeded -> "${wd.uuid} completed tx=${wd.txHash} confirmations=${wd.confirmations}/${wd.requiredConfirmations}"
    wd.isTerminal -> "${wd.uuid} ${wd.status}: ${wd.errorReason.orEmpty()}"
    // confirmations is null or 0 while the transaction is not in a block.
    wd.status == WithdrawalStatus.CONFIRM_CHECK && (wd.confirmations ?: 0) == 0 ->
        "${wd.uuid} sent tx=${wd.txHash}, not in a block yet (needs ${wd.requiredConfirmations} confirmations)"
    wd.status == WithdrawalStatus.CONFIRM_CHECK ->
        "${wd.uuid} in a block tx=${wd.txHash} confirmations=${wd.confirmations}/${wd.requiredConfirmations}"
    else -> "${wd.uuid} in progress: ${wd.status}"
}
