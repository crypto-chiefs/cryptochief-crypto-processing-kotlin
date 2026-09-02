# Changelog

## [0.7.0] — 2026-09-02

Six endpoints the platform has always answered and this SDK could not ask, and two
fields it read past — one of which decides who pays for TRON energy.

- `client.wallets.history()` — `POST /v1/wallets/history`: every pay-in that used one deposit address. The same records `client.payIns.history()` returns, in the same `PayInHistoryResponse` shape rather than a parallel one, narrowed to a single wallet — for when a payer says they sent funds and you have the address but not the order, which a deposit wallet may have served several of. The address is matched case-insensitively, so either spelling of an EVM address works, and an address that is not your project's yields an empty page rather than an error
- `client.blockchain.blockchainsList()` — `POST /v1/blockchains/list`: the chains the platform's scanner is connected to right now. Infrastructure, not your catalogue. It answers a **bare JSON array**, so this returns `List<SupportedBlockchain>` and not a response type; `SupportedBlockchain.type` is the scanner's lower-case protocol family (`evm`, `tron`), which is deliberately left a `String` rather than mapped onto the upper-case `ChainFamily` the rest of the API sends
- `client.blockchain.contractsList()` — `POST /v1/blockchain/contracts/list`: every coin and token the platform supports on every network, whatever this project has enabled. Use it to build a "which assets could we turn on" picker; `contractsAvailable()` is still the list orders, sweeps and payouts obey
- `client.currencies.fiats()` and `client.currencies.cryptos()` — `POST /v1/currencies/fiats` and `POST /v1/currencies/cryptos`: the fiat codes the platform can price an order in, and the crypto tickers it has a rate for against USDT, grouped by the exchange each came from. Both are platform-wide, so neither takes an argument; the empty request body is signed like any other. `fiats()` answers a **bare JSON array** and so returns `List<FiatCurrency>` — the second endpoint in this SDK shaped that way, after `blockchainsList()`. `cryptos()` answers `CryptoCurrencies`, whose `byExchange` is a map from exchange name (`binance`, `bybit`, `exmo`, `kucoin`) to that exchange's tickers, with `tickers` the union and `quote` what the rates are quoted against. **Rate availability is not payment availability**: a ticker with a rate is one the platform can put a number on, not one your project can be paid in — `count` runs into the thousands, and `contractsAvailable()` remains the only answer to "can I take payment in this". Build a customer-facing picker from the wrong one and it offers assets the order will be refused for
- `AvailableContract.chainFamily` and `AvailableContract.isTest` — both endpoints have always sent them and this SDK dropped them on the floor, `isTest` being what tells a test-network asset from a real one. Added at the end of the constructor so every existing call site keeps compiling. A native coin's `contract` is the empty string, never `null`
- `SweepHistoryQuery.status` / `.search` and the same two on `SweepWalletHistoryQuery` — filter sweep history by one status, or search a substring of the wallet address, the sweep or gas-pump transaction hash and the task id (on the wallet variant, the hashes and the task id — the address is already fixed). Left out, every status comes back, `skipped` among them
- `gas_source` on auto-sweep settings, which the models dropped entirely: `SweepPolicy.gasSource` on the effective and project-default layers, `SweepOverride.gasSource` on the wallet's own, and `client.sweeps.updateSettings(gasSource = …)` to write it. It answers *what is bought* for a TRON transfer, where `feeMode` answers *who covers its network fees*, and the energy is billed to your API credits whichever fee mode you chose
- **Not setting `gas_source` is not the same as setting `native`.** A wallet that has never chosen one gets the platform default, `rented` — so energy is supplied, and billed to your credits, without anybody having switched it on. `SweepGasSource.NATIVE` has to be sent explicitly to make a wallet burn its own TRX. Read `effective.gasSource`, which is always concrete, to see what will actually happen; a `null` on `override` means only that this layer does not decide it, so the value is inherited and not switched off. `SweepFieldWrite.Inherit` clears the wallet's own choice, and `gas_source` joins `type_work`, `threshold_amount_usd` and `fee_mode` as the names the `fields` mask accepts
- `blockchainsList()`, `fiats()` and `cryptos()` no longer throw `DecodeException` on an empty result. The services build these bodies from Go slices and maps, so "nothing to list" marshals as a literal `null` and not as `[]` — the shape nobody sees until the day the list is empty. All three now read `null` as empty: the two list calls answer an empty `List`, `cryptos()` an empty `CryptoCurrencies`. A method whose signature promises a list returns one
- The same `null` nested one level down, `"by_exchange": {"bybit": null}`, took the whole `cryptos()` response with it — a map value is out of reach of the coercion that fills a missing property in from its default. `CryptoCurrencies.byExchange` now reads a quiet exchange as that exchange with no tickers. The property's type is unchanged
- **`SweepFeeMode` said the wrong thing.** It does not decide who is charged for a sweep: a deposit wallet already holding enough of the chain's native coin pays for its own transfer whatever the mode says, and the three modes only answer where a **shortfall** comes from. `CLIENT` takes it from your own master wallet. `SERVICE` has the platform supply it and **bills the cost to your API credits** — the half the doc comment left out entirely. `MIX` is **the default**, and it is not "the service wallet with the cost reclaimed from the sweep" as this SDK claimed: it tries `CLIENT` first and falls back to `SERVICE` when the master wallet cannot cover it. Documentation only; no behaviour and no signature changed
- **`Sweep.completedAt` is not proof a sweep settled**, and the doc comment saying it is "absent while the sweep is still in flight" invited exactly that reading. A `failed` sweep is no more in flight than a completed one, and the sweeper stamps the field at every terminal outcome — so presence says the task is over, not that the funds moved, and reading it as settlement books a failed sweep as money received. What settles it: `sweepConfirmations` above zero, or `confirmedAt` off the `sweep.confirmed` webhook, which carries a separate timestamp for this reason

## [0.6.0] — 2026-09-02

Three things about a wallet that could only be decided when it was minted are now
changeable afterwards, over the API: its name, its deposit webhook, and the master
it settles to.

- Wallets carry a `label` — a human-readable name. `GenerateWalletRequest.label` sets one at creation and `Wallet.label` comes back on every response that describes a wallet: generation, info, the list, and the three update endpoints below. Every wallet type takes one, not just static ones — a label names the wallet, it does not describe its role. At most 255 characters, counted as characters rather than bytes so a label in a non-Latin script measures the way its author would count it
- `client.wallets.setLabel()` and `clearLabel()` — `POST /v1/wallets/label`: name a wallet after the fact, or take the name away. Renaming used to mean the dashboard, one wallet at a time, which is no use to an integration holding a list of a hundred addresses — exactly where a name earns its keep. An empty label is a value, not an omission — it means "this wallet has no name" — and the SDK sends it as one; afterwards the wallet reads back with `label` `null`, never `""`
- `client.wallets.setCallbackUrl()` and `clearCallbackUrl()` — `POST /v1/wallets/callback-url`: move a static wallet's deposit webhook, or stop announcing deposits for it. A callback could be chosen when the address was minted and never again, which left nothing for the two cases that matter: addresses imported from a platform that created them elsewhere, and an endpoint that moved. Static wallets only — a master or transit has no per-deposit callback — and a deposit already announced is not re-announced to the new URL
- `client.wallets.rebindMaster()` — `POST /v1/wallets/rebind-master`: re-point a transit or static wallet at another master of the same project. It moves no money. It changes where the NEXT sweep settles, including sweeps already queued, since the sweeper reads the link when it runs rather than capturing it on the task; anything already swept is on the previous master and has to be moved from there. This is the way back from a wallet minted without a master, which went to the project's oldest master of the family — on an installation serving several merchants, somebody else's wallet. Idempotent, and refused for a master wallet, for a master of a different chain family, and for a frozen master
- `ErrorCode.LABEL_TOO_LONG` — a label over 255 characters. A real machine code from the gateway, unlike the upstream refusals that arrive as `SERVICE_ERROR` with the detail in the message

## [0.4.0] — 2026-08-28

Same API surface as the Go SDK v0.4.0; the version numbers across the SDK family
line up again.

- Auto-sweep settings: `client.sweeps.settings()` and `client.sweeps.updateSettings()` — read and write the policy that decides when a deposit wallet is swept (on arrival, above a USD threshold, or never). The read returns three layers — effective, override and project default — because only the three together say whether a value is the wallet's own or inherited, and inheritance is per field
- `SweepFieldWrite.Set` writes a value and `SweepFieldWrite.Inherit` stops overriding a field; `null` leaves it alone. `null` could not carry both meanings
- Sweep records now carry what the platform has always sent and this SDK dropped on the floor: the trigger (`typeWork`), the fee breakdown (estimated and actual), the gas-pump transaction hash, and the new confirmation fields
- Sweep status tells a broadcast sweep from a settled one. `broadcasted` means the transaction is out and not yet confirmed; `completed` means the chain confirmed it, with the confirmation count and settlement time filled in. Earlier platform versions reported `completed` at broadcast, so a sweep could read as settled while its transaction was unconfirmed or dropped
- Pay-in create accepts `environment` (`mainnet` / `testnet`), which constrains the asset the platform picks in fiat mode and for `ANY` networks — the case where an unconstrained pick could put a real payment on a test chain
- Pay-in create and select-asset accept `master_wallet_address`, pinning the order's deposit wallet to one of the project's master wallets
- `BuildInfo.VERSION`, used in the User-Agent header, was still `0.1.0`. Corrected
- The README's dependency snippets pinned `0.1.0`, a full release behind. Corrected

## [0.2.0] — 2026-08-18

- `client.credits.balance()` — `POST /v1/credits/balance` (billing-exempt): current credits/USD balance, postpaid flag, debt limit, and gas-operations gate status
- `client.credits.topup()` — `POST /v1/credits/topup` (billing-exempt): create a hosted-payment-link invoice to top up credits with USDT/USDC, optional success/error redirect URLs

## [0.1.0] — 2026-06-07

Initial release.

- `CryptoChiefClient` with DSL and builder construction
- Services: Payouts, Transactions, PayIns, Wallets, Sweeps, Withdrawals, StaticDeposits, Blockchain, Currencies
- Two-phase sign/execute on EVM, TRON, Solana, TON, XRP, UTXO; batch payouts
- High-level helpers: `signEvmCall`, `erc20Transfer`, `signAnchorCall` + `Borsh`, `signSolanaCall`, `signTonCall`, `jettonTransfer`, `nftTransfer`, `sendTonComment`
- TON cell + BoC encoder; TEP-74 / TEP-62 / op-0 message builders
- TON, TRON, Solana address parsing
- Keccak-256 and EVM ABI encoder
- Webhook verification with typed event classes
- RSA-OAEP / SHA-256 decryption with PKCS#1 and PKCS#8 PEM loaders
- Polling: `waitForPayout`, `waitForTransaction`, `waitForPayIn`
- Maven Central publishing via `com.vanniktech.maven.publish`
