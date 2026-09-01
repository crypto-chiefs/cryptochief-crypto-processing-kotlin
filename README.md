# Crypto Chief crypto-processing SDK for Kotlin / JVM

[![Maven Central](https://img.shields.io/maven-central/v/com.crypto-chief/cryptochief-crypto-processing-kotlin.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.crypto-chief/cryptochief-crypto-processing-kotlin)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0.21-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Kotlin / JVM SDK for the [Crypto Chief](https://crypto-chief.com/processing/) crypto-processing API.

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.crypto-chief:cryptochief-crypto-processing-kotlin:0.5.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.crypto-chief:cryptochief-crypto-processing-kotlin:0.5.0'
}
```

### Maven

```xml
<dependency>
  <groupId>com.crypto-chief</groupId>
  <artifactId>cryptochief-crypto-processing-kotlin</artifactId>
  <version>0.5.0</version>
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
| `client.wallets` | generate, list, info, freeze, rebindMaster, setCallbackUrl, clearCallbackUrl, setLabel, clearLabel, decryptPrivateKey |
| `client.sweeps` | force, history, walletHistory, settings, updateSettings |
| `client.withdrawals` | info, history |
| `client.staticDeposits` | info, history |
| `client.blockchain` | contractsAvailable, walletBalance, transactionStatus |
| `client.currencies` | fiatToCrypto, cryptoToFiat |
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
field alone, `SweepFieldWrite.Inherit` stops overriding it.

A sweep is broadcast first and confirmed after: `SweepStatus.BROADCASTED` means the
transaction is out and not yet confirmed, `SweepStatus.COMPLETED` means confirmed, with
`sweepConfirmations` and `completedAt` filled in. Earlier platform versions reported
`completed` at broadcast, so a sweep could read as settled while its transaction was still
unconfirmed.

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
