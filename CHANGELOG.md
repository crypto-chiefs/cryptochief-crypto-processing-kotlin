# Changelog

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
