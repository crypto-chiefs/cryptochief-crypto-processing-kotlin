# Crypto Chief crypto-processing SDK for Kotlin / JVM

[![Maven Central](https://img.shields.io/maven-central/v/com.crypto-chief/cryptochief-crypto-processing-kotlin.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.crypto-chief/cryptochief-crypto-processing-kotlin)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0.21-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Kotlin / JVM SDK for the [Crypto Chief](https://crypto-chief.com/processing/) crypto-processing API.

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.crypto-chief:cryptochief-crypto-processing-kotlin:0.12.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.crypto-chief:cryptochief-crypto-processing-kotlin:0.12.0'
}
```

### Maven

```xml
<dependency>
  <groupId>com.crypto-chief</groupId>
  <artifactId>cryptochief-crypto-processing-kotlin</artifactId>
  <version>0.12.0</version>
</dependency>
```

JDK 11+ at runtime, JDK 17+ to build.

## Quick start

Credentials come from the dashboard → Integration tab.

```kotlin
import com.cryptochief.processing.Chain
import com.cryptochief.processing.CryptoChiefClient
import com.cryptochief.processing.models.EstimatePayoutRequest
import com.cryptochief.processing.models.ExecutePayoutRequest
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    CryptoChiefClient.create {
        merchantId = "mer_..."
        apiKey     = "sk_..."
    }.use { client ->

        val estimate = client.payouts.estimate(
            EstimatePayoutRequest(
                network   = Chain.ETH_SEPOLIA,
                coin      = "ETH",
                amount    = "0.0001",
                toAddress = "0x...",
            ),
        )
        println("recipient gets ${estimate.amountToReceive}")

        val payout = client.payouts.execute(
            ExecutePayoutRequest(
                orderId     = "order-42",
                userId      = "user-42",
                network     = Chain.ETH_SEPOLIA,
                coin        = "ETH",
                amount      = "0.0001",
                toAddress   = "0x...",
                urlCallback = "https://your.app/webhooks/payout",
            ),
        )
        println("payout: ${payout.uuid} → ${payout.status}")
    }
}
```

## Services

| Service | Endpoints |
| ------- | --------- |
| `client.payouts` | estimate, execute, info, history, batchEstimate, batchExecute |
| `client.transactions` | estimate, sign, execute, info, history + EVM/TRON/Solana/TON helpers |
| `client.payIns` | create, info, history, cancel, selectAsset, resetAsset |
| `client.wallets` | generate, list, info, history, freeze, rebindMaster, setCallbackUrl, clearCallbackUrl, setLabel, clearLabel, decryptPrivateKey |
| `client.sweeps` | force, history, walletHistory, settings, updateSettings |
| `client.withdrawals` | info, history |
| `client.staticDeposits` | info, history |
| `client.blockchain` | contractsAvailable, contractsList, blockchainsList, walletBalance, transactionStatus |
| `client.currencies` | fiatToCrypto, cryptoToFiat, fiats, cryptos |
| `client.credits` | balance, topup |
| `client.energy` | quote, rent, order |
| `client.native` | quote, buy, order |

## Invoices (PayIn)

FIAT mode — customer picks the coin at payment time:

```kotlin
import com.cryptochief.processing.models.CreatePayInRequest
import com.cryptochief.processing.models.PayInMode

val invoice = client.payIns.create(
    CreatePayInRequest(
        orderId    = "order-42",
        userId     = "user-42",
        mode       = PayInMode.FIAT,
        amountFiat = "19.99",
        currency   = "USD",
        lifetimeSec = 3600,
        urlCallback = "https://your.app/webhooks/invoice",
    ),
)
println(invoice.paymentLink)
```

CRYPTO mode — fix the coin and amount up front:

```kotlin
import com.cryptochief.processing.Asset
import com.cryptochief.processing.Chain

val invoice = client.payIns.create(
    CreatePayInRequest(
        orderId      = "order-42",
        userId       = "user-42",
        mode         = PayInMode.CRYPTO,
        amountCrypto = "10",
        asset        = Asset(network = Chain.TRON_MAINNET, coin = "USDT"),
        urlCallback  = "https://your.app/webhooks/invoice",
    ),
)
println("pay to ${invoice.toAddress}")
```

## Wallets

Generate a wallet of any type. `label` names it for whoever reads a list of a hundred
addresses later — it applies to every type, not just static ones, and is capped at 255
characters:

```kotlin
import com.cryptochief.processing.ChainFamily
import com.cryptochief.processing.models.GenerateWalletRequest
import com.cryptochief.processing.models.WalletType

val w = client.wallets.generate(
    GenerateWalletRequest(
        walletType          = WalletType.STATIC,
        chainFamily         = ChainFamily.EVM,
        masterWalletAddress = "0x...",                          // optional
        callbackUrl         = "https://your.app/webhooks/deposit", // static only
        label               = "shop-42 checkout",                // optional
    ),
)
```

`master_wallet_address`, `callback_url` and `label` come back on every wallet response —
generation, info, the list, and the responses of the three update endpoints below. Each is
`null` when the wallet has none: never an empty string, never an absent key.

### Re-pointing a wallet at another master

```kotlin
val w = client.wallets.rebindMaster(address = depositAddress, masterWalletAddress = "0x...")
println(w.masterWalletAddress)
```

It moves no money. It changes where the **next** sweep settles, including sweeps already
queued — the sweeper reads the link when it runs. Anything already swept is on the previous
master and has to be moved from there.

Idempotent: a wallet already bound to that master answers 200 unchanged. Master wallets
cannot be re-pointed, and the new master must be of the same chain family and not frozen.

### Changing a static wallet's deposit webhook

```kotlin
client.wallets.setCallbackUrl(depositAddress, "https://your.app/webhooks/deposit")
client.wallets.clearCallbackUrl(depositAddress)   // stop announcing deposits here
```

An empty URL is a value, not an omission, and the SDK sends it as one. Static wallets only:
a master or transit has no per-deposit callback. A deposit already announced is not
re-announced to the new URL.

### Naming a wallet after the fact

```kotlin
val w = client.wallets.setLabel(depositAddress, "shop-42 checkout")
println(w.label)

client.wallets.clearLabel(depositAddress)   // back to no name at all
```

An empty label is a value, not an omission, and the SDK sends it as one — that is how a
name is cleared. Afterwards the wallet reads back with `label` `null`, not `""`.

Every wallet type can be renamed, unlike a callback URL: a label names the wallet, it does
not describe its role. Capped at 255 characters, longer refused with `LABEL_TOO_LONG`.

### Every pay-in that used one deposit address

```kotlin
import com.cryptochief.processing.models.WalletHistoryQuery

val page = client.wallets.history(
    WalletHistoryQuery(
        address   = depositAddress,
        dateFrom  = "2026-01-01T00:00:00+00:00",   // optional
        dateTo    = "2026-02-01T00:00:00+00:00",   // optional
        page      = 1,                             // default 1
        pageSize  = 50,                            // default 20, max 100
    ),
)
page.items.forEach { println("${it.orderId} → ${it.status}") }
```

The same records `client.payIns.history()` returns, in the same `PayInHistoryResponse`
shape, narrowed to one address — for when a payer says they sent funds and you have the
address but not the order. A deposit wallet serves several orders over its lifetime.

The address is matched case-insensitively, so either spelling of an EVM address works, and
an address that is not your project's yields an empty page rather than an error.

## Auto-sweep settings

A deposit wallet is swept to your master wallet on a policy: as soon as funds arrive, once
the balance reaches an amount, or never on its own (a force sweep still works).

```kotlin
val s = client.sweeps.updateSettings(
    address = depositAddress,
    typeWork = SweepFieldWrite.Set(SweepPolicyMode.THRESHOLD),
    thresholdAmountUsd = SweepFieldWrite.Set("250"),
)
println(s.effective.typeWork)   // what will actually happen
println(s.effective.source)     // which layer decided it
```

The read comes back in three layers — `effective` (what will happen), `override` (what this
wallet decides for itself) and `projectDefault` (what it falls back to) — because only the
three together say whether a value is yours or inherited.

Inheritance is per field: writing the mode leaves the fee mode inherited. `null` leaves a
field alone, `SweepFieldWrite.Inherit` stops overriding it. The four writable fields —
the names that reach the wire's `fields` mask — are `type_work`, `threshold_amount_usd`,
`fee_mode` and `gas_source`.

`feeMode` decides who covers a **shortfall** of gas. A deposit wallet already holding
enough of the chain's native coin pays for its own transfer whatever the mode says; these
three only answer where the difference comes from when it does not.

| `SweepFeeMode` | Where the shortfall comes from |
| -------------- | ------------------------------ |
| `CLIENT` | Your own master wallet. |
| `SERVICE` | The platform supplies it, and **bills the cost to your API credits**. |
| `MIX` | **The default.** `CLIENT` first, falling back to `SERVICE` when the master wallet cannot cover it. |

### Who pays for TRON energy: `gas_source`

`gasSource` answers *what is bought* for the transfer, where `feeMode` answers *who covers
its network fees*. TRON only; every other chain carries the value and ignores it.

| `SweepGasSource` | What happens |
| ---------------- | ------------ |
| `NATIVE` | The wallet burns its own TRX for energy. |
| `RENTED` | **The platform default.** The platform supplies the energy, and bills it to your API credits. |

> **Not setting it is not the same as setting `NATIVE`.** A wallet that has never chosen a
> gas source gets `rented` — so energy is supplied, and billed to your credits, without
> anybody having switched it on. To have the wallet burn its own TRX, send `NATIVE`
> explicitly.

```kotlin
import com.cryptochief.processing.models.SweepGasSource

val s = client.sweeps.updateSettings(
    address     = depositAddress,
    networkCode = Chain.TRON_MAINNET,
    gasSource   = SweepFieldWrite.Set(SweepGasSource.NATIVE),
)
println(s.effective.gasSource)   // what will actually happen: always concrete
println(s.override?.gasSource)   // null = this wallet does not decide it
```

`effective.gasSource` is always a concrete value — read that one to see what will happen.
A `null` on `override` means only that this layer does not decide it: the value is
inherited, **not** switched off. `SweepFieldWrite.Inherit` drops the wallet's own choice
and puts it back to inheriting.

### Sweep history

```kotlin
val page = client.sweeps.history(
    SweepHistoryQuery(
        status = SweepStatus.FAILED,          // optional: one status
        search = "0x77EDde",                  // optional: substring
        mode   = SweepMode.AUTO,
    ),
)
```

`status` narrows to a single status; left out, every status comes back — `skipped` among
them, which is a normal outcome (a balance below the wallet's threshold), not a failure.
`search` matches the wallet address, the sweep or gas-pump transaction hash and the task
id; on `walletHistory` the address is already fixed, so it matches the hashes and the task
id only.

A sweep is broadcast first and confirmed after: `SweepStatus.BROADCASTED` means the
transaction is out and not yet final. `sweepConfirmations` grows while the sweep is `broadcasted`.
An older `completed` record can carry `sweepConfirmations` 0; it is not settled.

Settled: `Sweep.isSettled`, i.e. `status == SweepStatus.COMPLETED && (sweepConfirmations ?: 0) >= maxOf(requiredConfirmations ?: 1, 1)`,
or `confirmedAt` of the `sweep.confirmed` webhook.

> `completedAt` is when the sweep was sent; it is already set on `broadcasted` and on `failed`/`skipped`.

## Withdrawals

Manual withdrawals are read-only here: `info` for one, `history` for a page. Withdrawals
have no webhooks.

```kotlin
import com.cryptochief.processing.models.HistoryQuery
import com.cryptochief.processing.models.WithdrawalStatus

val wd = client.withdrawals.info("b0d1f7f9-1eaa-4c2f-8f9b-2b0d1b0b9f11")
when {
    wd.succeeded  -> println("completed: ${wd.txHash} at ${wd.confirmations}/${wd.requiredConfirmations}")
    wd.isTerminal -> println("${wd.status}: ${wd.errorReason}")  // failed
    wd.status == WithdrawalStatus.CONFIRM_CHECK ->
        println("on chain, ${wd.confirmations ?: 0}/${wd.requiredConfirmations} confirmations")
    else -> println("in progress: ${wd.status}")
}

val page = client.withdrawals.history(HistoryQuery(page = 1, pageSize = 50))
```

`history` filters by `dateFrom`/`dateTo` only.

`WithdrawalStatus` (not the same set as payout statuses):

| Status | Meaning |
| ------ | ------- |
| `queue` | Accepted, not started. |
| `refueling` | The source wallet is being topped up with gas. |
| `refuel_confirmed` | Gas is in place or no top-up was needed. The withdrawal transaction is sent next. |
| `broadcasting` | EVM only: queued for broadcast. |
| `sending` | Being signed and sent. |
| `in_mempool` | BTC family only: broadcast, not yet mined. |
| `confirm_check` | Sent, waiting for `requiredConfirmations`. |
| `completed` | `confirmations` reached `requiredConfirmations`. Terminal. |
| `failed` | See `errorReason`. Terminal. |

`WithdrawalStatus.CANCELLED` is not produced by the API.

`requiredConfirmations` is always sent. `confirmations` is absent or 0 while the transaction
is not in a block. `completedAt` is set only on `completed`.

`error`, `confirmedAt`, `contract`, `amountFiat` and `updatedAt` are deprecated and never
sent; use `errorReason` and `completedAt`.

## Blockchain data

```kotlin
// Chains the platform's scanner is connected to right now. A bare JSON array.
client.blockchain.blockchainsList().forEach { println("${it.name} (${it.type})") }

// Every asset the platform supports, whatever this project has enabled.
val catalogue = client.blockchain.contractsList()

// What THIS project can be paid in right now - the list orders, sweeps and payouts obey.
val enabled = client.blockchain.contractsAvailable(Chain.ETH_SEPOLIA)
```

Both catalogues answer the same item type, `chainFamily` and `isTest` included; `contract`
is an empty string on a native coin, never `null`. `SupportedBlockchain.type` is the
scanner's lower-case protocol family (`evm`, `tron`), unlike the upper-case `chain_family`
carried everywhere else — the two are not the same value.

## Currency lists

```kotlin
// Every fiat the platform can price an order in. Another bare JSON array.
client.currencies.fiats().forEach { println("${it.code} — ${it.name}") }  // SEK — Swedish Krona

// Every crypto ticker it has a rate for, against USDT, by exchange.
val rates = client.currencies.cryptos()
println("${rates.count} tickers against ${rates.quote}")
println(rates.byExchange["binance"]?.size)
```

`fiats()` gives the codes `CreatePayInRequest.currency` and the two convert calls accept.
`cryptos()` is **rate availability only** — a ticker there is one the platform can put a
price on, not one your project can be paid in. That list stays
`client.blockchain.contractsAvailable()`; a picker built from `cryptos()` offers assets the
order will be refused for.

`fiats()`, `cryptos()` and `blockchainsList()` are built from Go slices and maps upstream,
so "nothing to list" reaches the wire as a literal `null` rather than as `[]`. All three
read that as empty — an empty list, or an empty `CryptoCurrencies` — and so does a `null`
standing in for one exchange's tickers inside `byExchange`. A method promising a list
answers with one.

## Estimating the network fee

`transactions.estimate` prices a native or token transfer without signing or broadcasting
anything — the request is `SignTransactionRequest` minus `url_callback`. The call itself is
billed to the credits balance: 10 000 credits (0.001 USD) per estimate:

```kotlin
import com.cryptochief.processing.Amount
import com.cryptochief.processing.Chain
import com.cryptochief.processing.models.EstimateTransactionRequest
import com.cryptochief.processing.models.TxType

val quote = client.transactions.estimate(
    EstimateTransactionRequest(
        network     = Chain.TRON_MAINNET,
        fromAddress = "T...",
        type        = TxType.TOKEN,
        toAddress   = "T...",
        value       = Amount.toBase("12.50", 6).toString(),
        contract    = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",  // USDT
    ),
)
println("fee: ${quote.estimatedFee} TRX (~${quote.estimatedFeeFiat} USD)")
println("the sender must hold ${quote.required} TRX")
```

`estimatedFee` is the network fee in the native coin; `required` is all the native coin the
from-wallet must hold — fee plus `value` for a native transfer, the fee alone for a token.
The `*Fiat` fields are the same in USD and come back empty when no rate is available.
`type = contract` is refused with `CONTRACT_ESTIMATE_UNSUPPORTED`.

On TRON the answer also carries a fee breakdown: `feeExpected` (what the fee is expected to be
with the wallet's current energy pool — staked, delegated, rented — counted in; not a guarantee,
the pool can run out), `feeLimit` (the on-chain cap written into the transaction), `energy`
(units needed) and `energyFee` + `bandwidthFee` + `activationFee`, which add up to the gross
`estimatedFee`. `activationFee` appears only on a native transfer to a not-yet-activated address.
On every other network these fields are absent — `null`, never `""`.

## Renting TRON energy

Renting energy for a transfer is cheaper than burning TRX for it. `energy.quote` prices the
rent against burning — free, nothing billed; `energy.rent` places the rent and bills the credits
balance; `energy.order` reads a rent back by its idempotency key:

```kotlin
import com.cryptochief.processing.models.EnergyQuoteRequest
import com.cryptochief.processing.models.EnergyRentRequest
import com.cryptochief.processing.withIdempotencyKey

// The address that will SEND the transfer — the energy is delegated to it.
val quote = client.energy.quote(EnergyQuoteRequest(receiveAddress = "T..."))
println("rent ${quote.priceTrx} TRX vs burn ${quote.burnPriceTrx} — saves ${quote.savingTrx}")

val order = withIdempotencyKey("energy-2026-09-18-0001") {
    client.energy.rent(
        EnergyRentRequest(receiveAddress = "T...", quoteRef = quote.ref),
    )
}
println(order.status)   // "delivered": the energy is already delegated

// Later, or after an "unresolved" reply — read the same order back by its key:
val again = client.energy.order("energy-2026-09-18-0001")
```

`rent` is synchronous: by the time it returns, the energy is delegated or the refusal is known
(`status` is `delivered`, `refused` — see `error` and `errorCode` — or `unresolved`). The
`Idempotency-Key` is **required** and deduplicates the rent, so a retry after a network failure
or a 502 is safe and reads the same order back. A non-2xx answer still returns the order when
one exists: a `refused` order comes back on a 502 — or on a 402 with
`errorCode = INSUFFICIENT_CREDITS` when the credits balance is short — and an `unresolved` one
on a 409 with `needsAttention = true` (do not retry, it needs a human). Only a plain error
envelope (`{"ok":false,...}`, e.g. an expired quote) throws an `ApiException`. On a `refused`
order nothing was billed and `priceUsd`, `credits` and `trxUsd` are `null`.

## Buying native coin

The platform sells the native coin of a network (TRX, ETH, BNB, SOL, TON, ...) from its own
liquidity, billed to your API credits. The price includes the coins at the market rate and the
fee of the platform's own transfer to your address, so `receiveAddress` can be any address —
`totalUsd` is the full sale price and `credits` the exact amount the buy bills. `native.quote`
prices a buy — free, nothing billed; `native.buy` places it and bills the credits balance;
`native.order` reads a buy back by its idempotency key:

```kotlin
import com.cryptochief.processing.Chain
import com.cryptochief.processing.models.NativeBuyRequest
import com.cryptochief.processing.models.NativeQuoteRequest
import com.cryptochief.processing.withIdempotencyKey

// Any receiving address — the merchant pays the transfer fee.
val quote = client.native.quote(
    NativeQuoteRequest(network = Chain.ETH_MAINNET, receiveAddress = "0x...", amount = "0.05"),
)
println("${quote.amount} ETH costs ${quote.totalUsd} USD = ${quote.credits} credits")

val order = withIdempotencyKey("native-2026-09-18-0001") {
    client.native.buy(NativeBuyRequest(quoteRef = quote.ref))
}
println(order.status)   // "delivered": the coins are already sent, see order.txHash

// Later, or after an "unresolved" reply — read the same order back by its key:
val again = client.native.order("native-2026-09-18-0001")
```

`buy` is synchronous and idempotent like `energy.rent`: a quote holds for about 90 seconds and is
single-use (a 409 `QUOTE_EXPIRED` / `QUOTE_ALREADY_USED` error envelope throws an `ApiException`
— quote again), a retry after a network failure or a 502 is safe under the same key, and a
non-2xx answer still returns the order when one exists: a `refused` order on a 502 — or on a 402
with `errorCode = INSUFFICIENT_CREDITS` when the credits balance needs a top-up — and an
`unresolved` one on a 409 with `needsAttention = true`, which must not be retried. On a
`refused` order nothing was billed and `txHash`, `totalUsd`, `credits` and the other billing
fields are `null`.

## Contract calls

EVM / TRON:

```kotlin
import com.cryptochief.processing.Amount

val signed = client.transactions.erc20Transfer(
    network       = Chain.ETH_MAINNET,
    fromAddress   = "0x...",
    tokenContract = "0xdAC17F958D2ee523a2206206994597C13D831ec7",
    recipient     = "0x...",
    amount        = Amount.toBase("12.50", 6),
)
val executed = client.transactions.execute(signed.uuid)
```

Custom EVM call:

> **This snippet shows the encoder, not a complete swap.** Uniswap's router
> moves your input token with `transferFrom`, so it needs an ERC-20
> `approve(address,uint256)` on that token first, confirmed before the swap is
> signed — without it the swap reverts and burns the gas. And an `amountOutMin`
> of `0` accepts whatever the pool returns, which on a public mempool hands the
> trade to the first sandwich bot that sees it. Sign and confirm the approve as a
> separate transaction before signing the swap.

```kotlin
val signed = client.transactions.signEvmCall(
    network     = Chain.ETH_SEPOLIA,
    fromAddress = "0x...",
    contract    = "0xUniswapV2Router",
    method      = "swapExactTokensForTokens(uint256,uint256,address[],address,uint256)",
    args        = listOf(amountIn, amountOutMin, path, to, deadline),
)
```

Solana Anchor:

```kotlin
import com.cryptochief.processing.solana.Borsh
import com.cryptochief.processing.models.SolanaAccount

val signed = client.transactions.signAnchorCall(
    network     = Chain.SOLANA_DEVNET,
    fromAddress = "YourWallet...",
    program     = "ProgramId...",
    method      = "transfer",
    args        = listOf(Borsh.u64(1_000_000)),
    accounts    = listOf(SolanaAccount("Acc1", isSigner = true, isWritable = true)),
)
```

TON Jetton:

```kotlin
val signed = client.transactions.jettonTransfer(
    network      = Chain.TON_MAINNET,
    fromAddress  = "EQ...",
    jettonMaster = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs",
    recipient    = "EQ...",
    amount       = Amount.toBase("12.50", 6),
    memo         = "Order #4242",
)
```

## Polling

```kotlin
import com.cryptochief.processing.poll.waitForPayout
import com.cryptochief.processing.PollOptions
import java.time.Duration

val last = client.waitForPayout(
    uuid    = payout.uuid,
    options = PollOptions(interval = Duration.ofSeconds(5), timeout = Duration.ofMinutes(90)),
)
if (!last.isTerminal) println("still ${last.status}")
```

| Helper | Timeout when `PollOptions.timeout` is unset |
| ------ | ------------------------------------------- |
| `waitForPayout` | 90 minutes (`PollOptions.PAYOUT_TIMEOUT`) |
| `waitForTransaction`, `waitForPayIn` | 10 minutes (`PollOptions.DEFAULT_TIMEOUT`) |

On timeout the last snapshot is returned; check `isTerminal`. A payout stays `confirm_check`
until every source reaches `requiredConfirmations`.

## Confirmations

An object reaches its final status when its count reaches `requiredConfirmations`. Decide on
the final state below, not on the count.

| Type | Count | Final state |
| ---- | ----- | ----------- |
| `TransactionInfo`, `TransactionWebhookEvent` | `confirmations` and `requiredConfirmations`, always sent: `confirmations` is 0 until in a block, grows while `broadcasted` | `confirmed` |
| `PayoutInfo`, `PayoutWebhookEvent` | `confirmations`, optional: lowest among `sources` | `paid` |
| `Sweep` | `sweepConfirmations`: grows while `broadcasted` | `completed` and `sweepConfirmations >= requiredConfirmations` (`Sweep.isSettled`) |
| `SweepWebhookEvent` | `sweepConfirmations`, at least `requiredConfirmations` | the event itself |
| `Withdrawal` | `confirmations`, optional: absent or 0 while not in a block | `completed` |

Transaction webhooks are sent only for final statuses.

A payout stays `confirm_check` until every source reaches `requiredConfirmations`, then
becomes `paid`. Each entry of `sources` and `serviceOperations` has its own optional
`confirmations`. On payouts `requiredConfirmations` is optional too.

## Webhook handling

```kotlin
import com.cryptochief.processing.webhook.PayoutWebhookEvent
import com.cryptochief.processing.webhook.WebhookVerificationException
import com.cryptochief.processing.webhook.WebhookVerifier

// exchange: com.sun.net.httpserver.HttpExchange
try {
    val event = WebhookVerifier.parse<PayoutWebhookEvent>(apiKey, rawBody, exchange.requestHeaders)
    println("payout ${event.uuid} → ${event.status}")
} catch (e: WebhookVerificationException) {
    exchange.sendResponseHeaders(401, -1)
}
```

`rawBody` is the request body as received, before JSON parsing.

| Header | Value |
|---|---|
| `X-Webhook-Delivery` | delivery id, 1–128 characters `[A-Za-z0-9_-]`; the same on every attempt and resend of one delivery |
| `X-CC-Timestamp` | Unix time of the attempt, seconds |
| `X-CC-Signature` | `v1=` + 64 hex characters |

String to sign, lines joined with `\n`, no trailing newline:

```
CC-HMAC-SHA256-WEBHOOK-V1
<X-CC-Timestamp>
<X-Webhook-Delivery>
<lowercase hex SHA-256 of the body bytes>
```

`X-CC-Signature = "v1=" + hex(HMAC-SHA256(key = apiKey, message = stringToSign))`

| Exception | Reason |
|---|---|
| `WebhookHeadersException` | a header is missing, repeated, contains CR or LF, or is malformed |
| `WebhookTimestampException` | `X-CC-Timestamp` differs from the current time by more than the tolerance, 300 s by default |
| `WebhookSignatureException` | the signature does not match |

All three extend `WebhookVerificationException`; answer them with 401. Spaces and tabs around
header values are removed; `X-CC-Timestamp` is a decimal number without a leading zero; the
signature is compared in constant time, hex in any case. An `apiKey` that is empty or holds only
spaces and tabs throws `IllegalArgumentException`; a body that does not decode as the requested
type throws `DecodeException`.

Headers are a map of name to every value received under it; names are case-insensitive:

```kotlin
import com.cryptochief.processing.webhook.WebhookVerifier
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

// com.sun.net.httpserver.HttpExchange
WebhookVerifier.verify(apiKey, body, exchange.requestHeaders)
// okhttp3.Headers
WebhookVerifier.verify(apiKey, body, headers.toMultimap())
// tolerance and clock
WebhookVerifier.verify(apiKey, body, headers.toMultimap(), tolerance = 600.seconds, now = { Instant.now() })
// verify and decode
val sweep = WebhookVerifier.parse<SweepWebhookEvent>(apiKey, body, exchange.requestHeaders)
```

An overload takes a header function instead. It returns one value per name, so a repeated header
is not visible to it and is not rejected; pass the map where the server exposes one:

```kotlin
// jakarta.servlet.http.HttpServletRequest
WebhookVerifier.verify(apiKey, body) { name -> request.getHeader(name) }
```

From Java, tolerance and clock are `java.time.Duration` and `java.time.Clock`; the event type is
given by its serializer:

```java
WebhookVerifier.verify(apiKey, body, exchange.getRequestHeaders(), Duration.ofSeconds(600), Clock.systemUTC());
PayoutWebhookEvent event = WebhookVerifier.parse(apiKey, body, exchange.getRequestHeaders(),
        PayoutWebhookEvent.Companion.serializer());
```

A retry or resend carries the same `X-Webhook-Delivery` and a new `X-CC-Timestamp`; use the
delivery id to skip events already processed.

`RequestSigner.webhookV1StringToSign()` and `RequestSigner.signWebhookV1()` compute the same values
outside the verifier.

IP allowlist:

```kotlin
import com.cryptochief.processing.webhook.WebhookVerifier

if (request.remoteAddress !in WebhookVerifier.SENDER_IPS) {
    response.status = 403
    return
}
```

Typed events: `PayoutWebhookEvent`, `TransactionWebhookEvent`, `PayInWebhookEvent`, `StaticDepositWebhookEvent`, `SweepWebhookEvent`.

## Wallet private key decryption

Upload an RSA public key in the dashboard (Project Settings → RSA Key), then
configure the client with the matching private key:

```kotlin
import com.cryptochief.processing.rsa.RsaKeyLoader

val client = CryptoChiefClient.create {
    merchantId    = "mer_..."
    apiKey        = "sk_..."
    rsaPrivateKey = RsaKeyLoader.loadPrivateKeyFromFile("/path/to/key.pem")
}

val wallet = client.wallets.generate(...)
val rawHex = client.wallets.decryptPrivateKey(wallet.privateKeyEncrypted!!)
```

PKCS#1 and PKCS#8 PEM both supported.

## Configuration

```kotlin
import java.time.Duration

val client = CryptoChiefClient.create {
    merchantId        = "..."
    apiKey            = "..."
    baseUrl           = "https://api-processing.crypto-chief.com" // the default; override for a white-label installation
    requestTimeout    = Duration.ofSeconds(30)
    maxRetries        = 5
    initialRetryDelay = Duration.ofMillis(250)
    maxRetryDelay     = Duration.ofSeconds(10)
    userAgent         = "my-app/1.2.3"
    httpClient        = myPreconfiguredOkHttpClient
}
```

A caller-supplied `httpClient` is not closed by the SDK. `baseUrl` is an origin: a path in it is
sent but not signed (see [Request signing](#request-signing)).

## Request signing

Requests are signed with HMAC-SHA256 v1. The body is sent as serialized, without
canonicalization; the signature covers the bytes sent.

| Header | Value |
|---|---|
| `Merchant` | merchant ID |
| `X-CC-Timestamp` | Unix time, seconds |
| `X-CC-Nonce` | 32 lowercase hex characters (16 random bytes) |
| `X-CC-Signature` | `v1=` + 64 lowercase hex characters |

String to sign, lines joined with `\n`, no trailing newline:

```
CC-HMAC-SHA256-REQ-V1
<X-CC-Timestamp>
<X-CC-Nonce>
<METHOD>
<path, e.g. /v1/payout/execute>
<query without "?", or empty>
<Merchant>
<Idempotency-Key, or empty>
<lowercase hex SHA-256 of the body bytes>
```

`X-CC-Signature = "v1=" + hex(HMAC-SHA256(key = apiKey, message = stringToSign))`

`path` is the route (`/v1/payout/execute`) without the base URL prefix, so `baseUrl` is an origin:
a proxy that serves the API under a path of its own has to strip that path before the request
reaches the gateway, which signs the path it received.

The path is signed with its `%`-sequences decoded — `/v1/orders/payout%2F8814` goes on the wire as
written and is signed as `/v1/orders/payout/8814`, the form the server reads. The query is signed
as sent, without decoding. `METHOD` is upper-cased over `a`–`z` only.

Timestamp, nonce and signature are computed for every attempt. On
`SIGNATURE_TIMESTAMP_OUT_OF_RANGE` the client sets its clock offset from `server_time` once
and repeats the request.

`RequestSigner.hmacV1StringToSign()` and `RequestSigner.signHmacV1()` compute the same values
outside the client; `signHmacV1()` returns the full `X-CC-Signature` header value (`v1=` + hex),
set it as is.

### Low-level request

`client.request(method, path, body)` sends a signed request with any method and returns the
response bytes; pass `responseSerializer` to decode it. Signing, retries, clock correction and the
error envelope are the ones the service methods use, so a route this SDK has no method for — a GET
with a query, say — is one call:

```kotlin
val raw = client.request("GET", "/v1/balance?asset=USDT")
```

### Idempotency key

`Idempotency-Key` is optional and part of the string to sign, on service calls and on
`client.request()` alike. `withIdempotencyKey` sets it for every call made inside the block:

```kotlin
import com.cryptochief.processing.withIdempotencyKey

val payout = withIdempotencyKey("payout-2026-09-16-0001") {
    client.payouts.execute(request)
}
```

It is a coroutine context element, so `withContext(IdempotencyKey("payout-2026-09-16-0001"))`
does the same, and the innermost value wins.

The key must be printable ASCII with no space at either edge; anything else throws
`IllegalArgumentException` before the request is sent. Adding the header from an OkHttp
interceptor instead leaves it out of the signature, and the gateway answers 401
`INVALID_SIGNATURE`.

The platform keeps the value in the billing record of the call, up to 255 bytes. It does not
deduplicate payouts — `ExecutePayoutRequest.orderId` does.

## Errors

```kotlin
import com.cryptochief.processing.ApiException
import com.cryptochief.processing.ErrorCode
import com.cryptochief.processing.NetworkException

try {
    client.payouts.execute(req)
} catch (e: ApiException) {
    when (e.code) {
        ErrorCode.INSUFFICIENT_FUNDS    -> // top up the master wallet
        ErrorCode.ASSET_NOT_ENABLED     -> // enable the coin in the project
        else                             -> throw e
    }
} catch (e: NetworkException) {
    // already retried up to options.maxRetries
}
```

`e.code` is taken from the gateway envelope (`error`, or `msg` when `error` is `SERVICE_ERROR`)
and from the white-label platform envelope (`error.details.code`, else `error.name`). A body
without a code gives `HTTP_<status>`.

5xx is retried with exponential backoff and full jitter. 4xx is not retried. The exception is
one repeat after `SIGNATURE_TIMESTAMP_OUT_OF_RANGE`, with the clock offset taken from
`server_time`.

## Other SDKs

SDKs for other languages are listed at [docs-sdk.crypto-chief.com/processing/processing](https://docs-sdk.crypto-chief.com/processing/processing).

## License

[MIT](LICENSE) © 2026 Crypto Chief
