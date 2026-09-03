# Crypto Chief crypto-processing SDK for Kotlin / JVM

[![Maven Central](https://img.shields.io/maven-central/v/com.crypto-chief/cryptochief-crypto-processing-kotlin.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.crypto-chief/cryptochief-crypto-processing-kotlin)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0.21-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Kotlin / JVM SDK for the [Crypto Chief](https://crypto-chief.com/processing/) crypto-processing API.

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.crypto-chief:cryptochief-crypto-processing-kotlin:0.8.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.crypto-chief:cryptochief-crypto-processing-kotlin:0.8.0'
}
```

### Maven

```xml
<dependency>
  <groupId>com.crypto-chief</groupId>
  <artifactId>cryptochief-crypto-processing-kotlin</artifactId>
  <version>0.8.0</version>
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
| `client.transactions` | sign, execute, info, history + EVM/TRON/Solana/TON helpers |
| `client.payIns` | create, info, history, cancel, selectAsset, resetAsset |
| `client.wallets` | generate, list, info, history, freeze, rebindMaster, setCallbackUrl, clearCallbackUrl, setLabel, clearLabel, decryptPrivateKey |
| `client.sweeps` | force, history, walletHistory, settings, updateSettings |
| `client.withdrawals` | info, history |
| `client.staticDeposits` | info, history |
| `client.blockchain` | contractsAvailable, contractsList, blockchainsList, walletBalance, transactionStatus |
| `client.currencies` | fiatToCrypto, cryptoToFiat, fiats, cryptos |
| `client.credits` | balance, topup |

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
transaction is out and not yet confirmed, `SweepStatus.COMPLETED` means confirmed, with
`sweepConfirmations` filled in. Earlier platform versions reported `completed` at
broadcast, so a sweep could read as settled while its transaction was still unconfirmed.

> **`completedAt` is not proof the sweep settled.** The sweeper stamps it at every terminal
> outcome, failures included — a `failed` sweep is no more in flight than a `completed`
> one, so it carries a time too. What says the funds moved is `sweepConfirmations` above
> zero, or `confirmedAt` off the `sweep.confirmed` webhook, which exists as a separate
> field for exactly this reason.

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

val terminal = client.waitForPayout(
    uuid    = payout.uuid,
    options = PollOptions(interval = Duration.ofSeconds(5), timeout = Duration.ofMinutes(10)),
)
```

## Webhook handling

```kotlin
import com.cryptochief.processing.webhook.PayoutWebhookEvent
import com.cryptochief.processing.webhook.WebhookHandler
import com.cryptochief.processing.webhook.WebhookSignatureException

try {
    val event = WebhookHandler.handle<PayoutWebhookEvent>(
        apiKey = apiKey,
        body = rawBody,
        signatureHeader = request.header("Signature"),
    )
    println("payout ${event.uuid} → ${event.status}")
} catch (e: WebhookSignatureException) {
    response.status = 401
}
```

IP allowlist:

```kotlin
import com.cryptochief.processing.webhook.WebhookVerifier

if (request.remoteAddress !in WebhookVerifier.SENDER_IPS) {
    response.status = 403
    return
}
```

Typed events: `PayoutWebhookEvent`, `TransactionWebhookEvent`, `PayInWebhookEvent`, `StaticDepositWebhookEvent`.

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
    baseUrl           = "https://staging-api.crypto-chief.com"
    requestTimeout    = Duration.ofSeconds(30)
    maxRetries        = 5
    initialRetryDelay = Duration.ofMillis(250)
    maxRetryDelay     = Duration.ofSeconds(10)
    userAgent         = "my-app/1.2.3"
    httpClient        = myPreconfiguredOkHttpClient
}
```

A caller-supplied `httpClient` is not closed by the SDK.

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
        ErrorCode.ORDER_ALREADY_EXIST   -> // idempotent retry
        else                             -> throw e
    }
} catch (e: NetworkException) {
    // already retried up to options.maxRetries
}
```

5xx is retried with exponential backoff and full jitter. 4xx is not retried.

## Other SDKs

SDKs for other languages are listed at [docs-sdk.crypto-chief.com/processing/processing](https://docs-sdk.crypto-chief.com/processing/processing).

## License

[MIT](LICENSE) © 2026 Crypto Chief
